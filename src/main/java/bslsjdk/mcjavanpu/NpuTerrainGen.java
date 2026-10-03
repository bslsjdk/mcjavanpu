package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Whole-chunk density generated as one big matmul.
 *
 * The old attempt fed the NPU a per-point 8-corner interpolation - an 8-wide matrix. That is
 * the one shape the HTP is worst at. This does the opposite: each point becomes a K-wide
 * feature row and the chunk density falls out of a single A[m x K] * B[K x 1].
 *
 *   A: one row per sample point, K features (ramps, triangle waves, octave sin/cos)
 *   B: K weights derived from the world seed
 *   out: density per point -> solid / air
 *
 * We are deliberately not replicating vanilla noise. The only requirements are that the NPU
 * actually computes it and that the world loads, so the field is normalised and a height
 * gradient is re-added afterwards. That makes the result loadable whatever range the
 * quantised matmul hands back.
 */
public final class NpuTerrainGen {

    /** Features per point. A comfortable HTP width. */
    public static final int K = 32;
    private static final int OCTAVES = 4;

    private NpuTerrainGen() {}

    public static final class Result {
        public final float[] density;
        public final int sizeX, sizeY, sizeZ;
        public final long npuUs, hostUs;
        public final int solids;
        public final float minD, maxD;
        public final boolean usedNpu;
        public final String note;

        Result(float[] d, int sx, int sy, int sz, long npuUs, long hostUs, boolean npu, String note) {
            this.density = d;
            this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz;
            this.npuUs = npuUs; this.hostUs = hostUs;
            this.usedNpu = npu; this.note = note;
            int s = 0; float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (float v : d) { if (v > 0) s++; if (v < mn) mn = v; if (v > mx) mx = v; }
            this.solids = s; this.minD = mn; this.maxD = mx;
        }

        public String summary() {
            return String.format(Locale.ROOT,
                "terrain npu=%s npu_us=%d host_us=%d solids=%d/%d min=%.3f max=%.3f%s",
                usedNpu ? "yes" : "no", npuUs, hostUs, solids,
                sizeX * sizeY * sizeZ, minD, maxD, note.isEmpty() ? "" : " | " + note);
        }
    }

    /** Feature row for one point, values kept inside [-1, 1] so int8 keeps its precision. */
    private static void features(float[] dst, int off, float wx, float wy, float wz,
                                 float s1, float s2) {
        int f = 0;
        dst[off + f++] = 0.5f;
        dst[off + f++] = frac(wx * 0.03125f);
        dst[off + f++] = frac(wz * 0.03125f);
        dst[off + f++] = frac(wx * 0.25f);
        dst[off + f++] = frac(wz * 0.25f);
        dst[off + f++] = tri(wx * 0.0625f + s1);
        dst[off + f++] = tri(wz * 0.0625f + s2);
        dst[off + f++] = tri((wx + wz) * 0.03125f + s1 * 0.5f);
        for (int o = 0; o < OCTAVES; o++) {
            float g = (1 << o) * 0.25f;
            float p1 = wx * g + s1 * 8f;
            float p2 = wz * g + s2 * 8f;
            dst[off + f++] = T.sin(p1) * T.cos(p2);
            dst[off + f++] = T.cos(p1) * T.sin(p2);
        }
        while (f < K) dst[off + f++] = 0f;
    }

    private static float frac(float v) {
        float x = v * 0.25f;
        return (x - (float) Math.floor(x)) * 2f - 1f;
    }

    private static float tri(float v) {
        float x = (v - (float) Math.floor(v * 0.25f) * 4f) * 0.25f;
        return 4f * Math.abs(x - 0.5f) - 1f;
    }

    private static float[] weights(long seed) {
        float[] b = new float[K];
        java.util.Random r = new java.util.Random(seed * 0x9E3779B9L);
        for (int i = 0; i < 8; i++) b[i] = (r.nextFloat() - 0.5f) * 1.2f;
        for (int i = 8; i < 8 + OCTAVES * 2; i++) b[i] = (r.nextFloat() - 0.5f) * 1.8f;
        return b;
    }

    /** Generate a whole chunk volume through the NPU. */
    public static Result generate(int sx, int sy, int sz, int ox, int oy, int oz, long seed) {
        int m = sx * sy * sz;
        float[] feat = new float[m * K];
        float s1 = ((seed >>> 3) % 997) / 997f;
        float s2 = ((seed >>> 11) % 991) / 991f;
        // Index order must match DensitySampler.sampleVolumeNaive: z outer, x middle, y inner.
        int i = 0;
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    features(feat, i * K, ox + x, oy + y, oz + z, s1, s2);
                    i++;
                }
            }
        }
        float[] w = weights(seed);

        final float QA = 1f / 127f;
        final float QB = 1f / 127f;
        byte[] a = new byte[m * K];
        for (int p = 0; p < a.length; p++) {
            int q = Math.round(feat[p] / QA);
            a[p] = (byte) (q < -127 ? -127 : (q > 127 ? 127 : q));
        }
        byte[] b = new byte[K];
        for (int p = 0; p < K; p++) {
            int q = Math.round(w[p] / QB);
            b[p] = (byte) (q < -127 ? -127 : (q > 127 ? 127 : q));
        }

        long t0 = System.nanoTime();
        // A full 16x384x16 chunk is 98304 rows, far above the native 65536 cap.
        // submit() used to return ERR BUF_TOO_LARGE for every single chunk, so the
        // NPU path never ran and the host fallback silently did all the work.
        // submitSplit() runs independent row blocks instead. Rows are independent
        // here by construction: row i depends only on point i.
        NpuRuntime.MatMulResult r = NpuDispatcher.submitSplit(a, b, m, K, 1);
        long npuUs = (System.nanoTime() - t0) / 1000;

        float[] dens = new float[m];
        boolean usedNpu = false;
        String note = "";
        long t1 = System.nanoTime();
        if (r.ok() && r.c() != null) {
            usedNpu = true;
            float scale = r.scaleC();
            if (scale == 0f) scale = QA * QB;
            byte[] c = r.c();
            for (int p = 0; p < m; p++) dens[p] = c[p] * scale;
        } else {
            note = "npu unavailable (" + (r.error() == null ? "?" : r.error()) + "), host path";
            for (int p = 0; p < m; p++) {
                float s = 0f;
                for (int k = 0; k < K; k++) s += feat[p * K + k] * w[k];
                dens[p] = s;
            }
        }

        // Normalise, then add a height gradient: loadable regardless of the matmul range.
        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        for (float v : dens) { if (v < mn) mn = v; if (v > mx) mx = v; }
        float span = (mx - mn) < 1e-9f ? 1f : (mx - mn);
        for (int p = 0; p < m; p++) dens[p] = (dens[p] - mn) / span - 0.5f;

        int idx = 0;
        for (int z = 0; z < sz; z++) {
            for (int x = 0; x < sx; x++) {
                for (int y = 0; y < sy; y++) {
                    dens[idx] += 0.9f - (oy + y) * 0.0055f;
                    idx++;
                }
            }
        }
        long hostUs = (System.nanoTime() - t1) / 1000;
        return new Result(dens, sx, sy, sz, npuUs, hostUs, usedNpu, note);
    }

    /** Tiny sin/cos table, since feature building happens per point in Java. */
    static final class T {
        private static final float[] SIN = new float[1024];
        private static final float[] COS = new float[1024];
        static {
            for (int iq = 0; iq < 1024; iq++) {
                double a = iq * Math.PI * 2 / 1024;
                SIN[iq] = (float) Math.sin(a);
                COS[iq] = (float) Math.cos(a);
            }
        }
        private static int ix(float x) { return (int) Math.floor(x * 162.9746617) & 1023; }
        static float sin(float x) { return SIN[ix(x)]; }
        static float cos(float x) { return COS[ix(x)]; }
    }

    public static String describe() {
        return String.format(Locale.ROOT, "terrain gen: K=%d, A[m x %d] * B[%d x 1]", K, K, K);
    }

    /**
     * Fill a caller array (MC DensityBuffer order) with generated density.
     * Returns the number of points written, or -1 if the NPU path was unavailable.
     */
    public static int fill(float[] out, int sx, int sy, int sz, int ox, int oy, int oz, long seed) {
        Result r = generate(sx, sy, sz, ox, oy, oz, seed);
        if (!r.usedNpu) return -1;
        int n = Math.min(out.length, r.density.length);
        System.arraycopy(r.density, 0, out, 0, n);
        return n;
    }
}
