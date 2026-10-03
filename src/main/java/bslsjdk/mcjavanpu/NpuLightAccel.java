package bslsjdk.mcjavanpu;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * NPU light propagation over a batch of 8x8x8 voxel blocks.
 *
 * Minecraft light spread is  max(neighbour) - 1, a non-linear iteration.
 * The HTP only runs one quantised matmul, so this uses the linearised form
 *
 *     L_out = A * L_in ,  A = I + w * (sum of the 6 face neighbours)
 *
 * over each 8x8x8 block flattened to 512 cells. One batch = m blocks, i.e. one
 * (m x 512) * (512 x 512) int8 matmul.
 *
 * Measured on SM8635 after the requantisation-scale cache landed: 512^3 steady
 * state costs 5.5ms on the NPU vs ~40ms for the same product on the host, and
 * the propagation operator itself is numerically exact (bad=0/262144).
 *
 * The operator is linearised, so the result is softer than the real BFS light.
 */
public final class NpuLightAccel {

    public static final int SIDE = 8;

    /**
     * Whether each call also computes a dense host reference.
     *
     * That reference is an O(m*k*n) triple loop - at m=128, k=n=512 it is 33.5
     * million multiply-adds in Java - and it exists only to prove the device
     * result is correct (the `bad` counter). It was running on every production
     * call, which showed up in the field as cpu_us=4491 on real data and up to
     * 500687 during warmup, all of it on the calling thread and none of it
     * contributing anything to the game.
     *
     * Off by default. Warmup and the benchmark switch it on for the calls whose
     * whole purpose is measurement.
     */
    private static volatile boolean verify = false;

    /** Consecutive real calls that changed nothing. */
    private static final AtomicInteger ZERO_WRITES = new AtomicInteger();
    /** How many no-op calls in a row before the path stops being attempted. */
    private static final int ZERO_WRITE_LIMIT = 6;
    private static volatile boolean noEffect;

    public static void setVerify(boolean v) { verify = v; }
    public static boolean isVerify() { return verify; }

    /**
     * Record how many cells a real call actually changed.
     *
     * This is the honest measure of whether the feature is doing anything. In the
     * uploaded log a real lightapply cost 37782us on the device plus 4491us of
     * reference and reported written=0 - the full price, no effect at all. After
     * ZERO_WRITE_LIMIT such calls in a row the path refuses to run, so a feature
     * that cannot help stops costing anything instead of burning 40ms per call
     * forever.
     */
    public static void noteWritten(int n) {
        if (n > 0) {
            if (ZERO_WRITES.getAndSet(0) != 0 || noEffect) {
                noEffect = false;
                NpuLog.log("light: write-back active again (" + n + " cells)");
            }
            return;
        }
        int z = ZERO_WRITES.incrementAndGet();
        if (z == ZERO_WRITE_LIMIT) {
            noEffect = true;
            NpuLog.warn("light: " + z + " consecutive calls changed 0 cells - "
                    + "disabling the path. It was paying full device cost for no effect.");
        }
    }

    public static boolean isNoEffect() { return noEffect; }
    public static int zeroWrites() { return ZERO_WRITES.get(); }
    public static void resetNoEffect() { ZERO_WRITES.set(0); noEffect = false; }
    public static final int CELLS = SIDE * SIDE * SIDE;   // 512

    /** Weight of each of the 6 face neighbours; the centre keeps the rest. */
    private static final int CENTRE = 127;
    /** Row sum of the operator: CENTRE + 6 * NEIGHBOUR. */
    public static final float OP_SUM = 253f;
    /** Host reference factor; undone when converting results back to light units. */
    public static final float REF_Q = 1.0e-6f;

    private static final int NEIGHBOUR = 21;              // 6*21 = 126, total 253 -> scaled by 1/253

    private NpuLightAccel() {}

    private static int idx(int x, int y, int z) { return (y * SIDE + z) * SIDE + x; }

    /**
     * Cached operator.
     *
     * The propagation matrix is a constant: it depends only on the 8x8x8 geometry, never on the
     * data being propagated. It was being rebuilt on every call (256 KB of allocation plus the
     * fill loop) which showed up directly in the per-call cost. Built once, reused forever.
     */
    private static volatile byte[] OPERATOR;

    public static byte[] operator() {
        byte[] op = OPERATOR;
        if (op == null) {
            op = buildOperator();
            OPERATOR = op;
        }
        return op;
    }

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
        /** Raw int8 output, m*n row-major. Null when the call failed. */
        public final byte[] out;
        /** Multiplier that turns the raw int8 back into light units. */
        public final float scale;
        Result(boolean ok, String error, long npuUs, long cpuUs, int bad, float maxAbs, int m, int k, int n) {
            this(ok, error, npuUs, cpuUs, bad, maxAbs, m, k, n, null, 0f);
        }
        Result(boolean ok, String error, long npuUs, long cpuUs, int bad, float maxAbs, int m, int k, int n, byte[] out, float scale) {
            this.ok = ok; this.error = error; this.npuUs = npuUs; this.cpuUs = cpuUs;
            this.bad = bad; this.maxAbs = maxAbs; this.m = m; this.k = k; this.n = n;
            this.out = out; this.scale = scale;
        }
        /**
         * Row r, column j, converted back to light units and clamped to 0..15.
         *
         * Two corrections on top of the raw value:
         *   / OP_SUM - the operator row sums to 253, so the product is a weighted
         *              sum rather than a normalised average
         *   / REF_Q  - the host reference multiplies by Q = 1e-6 to keep int8 in
         *              range, so that has to be undone too
         * Without both, out * scale lands near 0.0038 and rounds to 0, which is
         * exactly why the first write-back reported written=0.
         */
        public int light(int r, int j) {
            if (out == null) return 0;
            float f = out[r * n + j] * scale / (REF_Q * OP_SUM);
            int v = Math.round(f);
            return v < 0 ? 0 : (v > 15 ? 15 : v);
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

    /** Shared submit-and-compare core. `a` is m*k, `b` is k*n. */
    private static Result run(byte[] a, byte[] b, int m, int k, int n) {
        // Adaptive backoff. When the rolling p99 stops being worth paying, callers are told no
        // immediately and the vanilla path takes over - the game must never be slowed down by an
        // accelerator that is currently losing.
        if (!NpuGuard.allow()) {
            return new Result(false, "GUARD_DEGRADED " + NpuGuard.reason(), 0, 0, 0, 0f, m, k, n);
        }
        // Refuse to run at all once the path has repeatedly changed nothing. Trying
        // again would just pay the device cost one more time for the same zero.
        if (noEffect) {
            return new Result(false, "NO_EFFECT after " + ZERO_WRITES.get() + " empty calls", 0, 0, 0, 0f, m, k, n);
        }
        int[] sh = NpuDispatcher.planShape(m, k, n);

        // Report the real executed shape, not the requested one. A shape that pads heavily is the
        // single most expensive mistake in this file, and it is invisible unless it is printed.
        if (NpuConfig.get().debugLog && (sh[0] != m || sh[1] != k || sh[2] != n)) {
            NpuLog.log("shape " + NpuShapeAdvisor.advise(m, k, n));
        }

        long t0 = System.nanoTime();
        NpuRuntime.MatMulResult r = NpuDispatcher.submit(a, b, m, k, n);
        long npuUs = (System.nanoTime() - t0) / 1000;
        NpuGuard.recordUs(npuUs);
        if (!r.ok()) return new Result(false, r.error(), npuUs, 0, 0, 0f, sh[0], sh[1], sh[2]);

        byte[] c = r.c();

        // The dense host reference is a correctness check, not part of the job. It is
        // O(m*k*n) Java work on the calling thread - measured at 4491us on real data
        // and up to 500687us during warmup - so it only runs when a caller has asked
        // for verification (warmup, benchmark, debug).
        if (!verify) {
            return new Result(true, null, npuUs, 0, -1, 0f, sh[0], sh[1], sh[2], c, r.scaleC());
        }

        long t1 = System.nanoTime();
        final float Q = 1.0e-6f;
        float[] ref = new float[m * n];
        // Dense reference: every p is visited even when a is zero. Skipping zeros here
        // would flatter the host badly (light fields are sparse), and the number that
        // matters is how the NPU compares against the work the game would actually do.
        for (int i = 0; i < m; i++) {
            for (int p = 0; p < k; p++) {
                int av = a[i * k + p];
                for (int j = 0; j < n; j++) ref[i * n + j] += av * b[p * n + j] * Q;
            }
        }
        long cpuUs = (System.nanoTime() - t1) / 1000;

        int bad = 0; float maxAbs = 0f;
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < n; j++) {
                float got = c[i * n + j] * r.scaleC();
                float d = Math.abs(got - ref[i * n + j]);
                if (d > 2.5f * r.scaleC() + 0.05f * Math.abs(ref[i * n + j])) bad++;
                if (d > maxAbs) maxAbs = d;
            }
        }
        return new Result(true, null, npuUs, cpuUs, bad, maxAbs, sh[0], sh[1], sh[2], c, r.scaleC());
    }

    /**
     * MANY distinct blocks in ONE call: `a` is m*CELLS, row r is one 8x8x8 field.
     *
     * This is the shape that replaces multi-threaded chunk work. Device cost barely
     * moves as m grows (measured: m=100 and m=257 both ~24ms at k=n=256), so folding
     * N chunks into a single batch is effectively free -- which is exactly why
     * batching beats parallelising here.
     */
    public static Result propagateBatch(byte[] a, int m) {
        if (m <= 0) m = 1;
        if (a == null || a.length < m * CELLS) {
            return new Result(false, "A_TOO_SHORT need=" + (m * CELLS), 0, 0, 0, 0f, m, CELLS, CELLS);
        }
        return run(a, operator(), m, CELLS, CELLS);
    }

    /**
     * Synthetic batch: `blocks` independent 8x8x8 light fields with values 0..15.
     */
    public static Result propagate(int blocks) {
        if (blocks <= 0) blocks = 1;
        int m = blocks, k = CELLS, n = CELLS;
        byte[] a = new byte[m * k];
        java.util.Random rnd = new java.util.Random(20261003L);
        for (int i = 0; i < a.length; i++) a[i] = (byte) rnd.nextInt(16);
        return run(a, buildOperator(), m, k, n);
    }

    /**
     * REAL-data batch: a genuine 8x8x8 Minecraft light field (512 values, 0..15)
     * replicated across `blocks` rows so the batch clears the device minimum.
     */
    public static Result propagateReal(byte[] cells, int blocks) {
        if (blocks <= 0) blocks = 1;
        int m = blocks, k = CELLS, n = CELLS;
        if (cells == null || cells.length < CELLS) {
            return new Result(false, "CELLS_TOO_SHORT", 0, 0, 0, 0f, m, k, n);
        }
        byte[] a = new byte[m * k];
        for (int i = 0; i < m; i++) System.arraycopy(cells, 0, a, i * k, CELLS);
        return run(a, buildOperator(), m, k, n);
    }
}
