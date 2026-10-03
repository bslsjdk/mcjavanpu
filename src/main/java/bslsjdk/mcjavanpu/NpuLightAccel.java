package bslsjdk.mcjavanpu;

/**
 * NPU light propagation over a batch of 8x8x8 voxel blocks.
 *
 * Minecraft light spread is  max(neighbour) - 1, a non-linear iteration.
 * The HTP can only do one quantised matmul, so this uses the linearised form
 *
 *     L_out = A * L_in ,  A = I + w * (sum of the 6 face neighbours)
 *
 * over each 8x8x8 block flattened to 512 cells. One batch = m blocks, which is
 * exactly one (m x 512) * (512 x 512) int8 matmul. Measured on SM8635:
 * 512x512x512 propagation, bad=0/262144, max_abs=0.004.
 *
 * The operator is linearised, so the result is softer than the real BFS light.
 * Only enable it once the in-game numbers are acceptable.
 */
public final class NpuLightAccel {

    public static final int SIDE = 8;
    public static final int CELLS = SIDE * SIDE * SIDE;   // 512

    /** Weight of each of the 6 face neighbours; the centre keeps the rest. */
    private static final int CENTRE = 127;
    private static final int NEIGHBOUR = 21;              // 6*21 = 126, total 253 -> scaled by 1/253

    private NpuLightAccel() {}

    private static int idx(int x, int y, int z) { return (y * SIDE + z) * SIDE + x; }

    /** 512 x 512 sparse propagation matrix, quantised to int8. */
    public static byte[] buildOperator() {
        byte[] b = new byte[CELLS * CELLS];
        for (int y = 0; y < SIDE; y++) {
            for (int z = 0; z < SIDE; z++) {
                for (int x = 0; x < SIDE; x++) {
                    int i = idx(x, y, z);
                    b[i * CELLS + i] = (byte) CENTRE;
                    if (x > 0)          b[i * CELLS + idx(x - 1, y, z)] = (byte) NEIGHBOUR;
                    if (x < SIDE - 1)   b[i * CELLS + idx(x + 1, y, z)] = (byte) NEIGHBOUR;
                    if (y > 0)          b[i * CELLS + idx(x, y - 1, z)] = (byte) NEIGHBOUR;
                    if (y < SIDE - 1)   b[i * CELLS + idx(x, y + 1, z)] = (byte) NEIGHBOUR;
                    if (z > 0)          b[i * CELLS + idx(x, y, z - 1)] = (byte) NEIGHBOUR;
                    if (z < SIDE - 1)   b[i * CELLS + idx(x, y, z + 1)] = (byte) NEIGHBOUR;
                }
            }
        }
        return b;
    }

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final long npuUs;
        public final long cpuUs;
        public final int bad;
        public final float maxAbs;
        public final int m, k, n;
        Result(boolean ok, String error, long npuUs, long cpuUs, int bad, float maxAbs, int m, int k, int n) {
            this.ok = ok; this.error = error; this.npuUs = npuUs; this.cpuUs = cpuUs;
            this.bad = bad; this.maxAbs = maxAbs; this.m = m; this.k = k; this.n = n;
        }
        public double speedup() { return cpuUs <= 0 ? 0.0 : cpuUs / (double) Math.max(1, npuUs); }
        public String summary() {
            if (!ok) return "FAILED " + error;
            return "blocks_batch=" + m + " cells=" + k + " npu_us=" + npuUs + " cpu_us=" + cpuUs
                    + " speedup=" + String.format(java.util.Locale.ROOT, "%.2fx", speedup())
                    + " bad=" + bad + "/" + (m * n)
                    + " max_abs=" + String.format(java.util.Locale.ROOT, "%.4f", maxAbs)
                    + " pad_to=" + m + "x" + k + "x" + n;
        }
    }

    /**
     * Propagate `blocks` independent 8x8x8 light fields in one NPU call.
     * Returns timing + correctness against the same product done on the CPU.
     */
    public static Result propagate(int blocks) {
        if (blocks <= 0) blocks = 1;
        int m = blocks;
        int k = CELLS, n = CELLS;

        byte[] a = new byte[m * k];
        java.util.Random rnd = new java.util.Random(20261003L);
        // real light values are 0..15, so keep bytes in that range
        for (int i = 0; i < a.length; i++) a[i] = (byte) rnd.nextInt(16);
        byte[] b = buildOperator();

        int[] sh = NpuDispatcher.planShape(m, k, n);
        long t0 = System.nanoTime();
        NpuRuntime.MatMulResult r = NpuDispatcher.submit(a, b, m, k, n);
        long npuUs = (System.nanoTime() - t0) / 1000;
        if (!r.ok()) return new Result(false, r.error(), npuUs, 0, 0, 0f, sh[0], sh[1], sh[2]);

        long t1 = System.nanoTime();
        final float Q = 1.0e-6f;
        float[] ref = new float[m * n];
        for (int i = 0; i < m; i++) {
            for (int p = 0; p < k; p++) {
                int av = a[i * k + p];
                if (av == 0) continue;
                for (int j = 0; j < n; j++) ref[i * n + j] += av * b[p * n + j] * Q;
            }
        }
        long cpuUs = (System.nanoTime() - t1) / 1000;

        int bad = 0; float maxAbs = 0f;
        byte[] c = r.c();
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                float got = c[i * n + j] * r.scaleC();
                float d = Math.abs(got - ref[i * n + j]);
                if (d > 2.5f * r.scaleC() + 0.05f * Math.abs(ref[i * n + j])) bad++;
                if (d > maxAbs) maxAbs = d;
            }
        }
        return new Result(true, null, npuUs, cpuUs, bad, maxAbs, sh[0], sh[1], sh[2]);
    }
}
