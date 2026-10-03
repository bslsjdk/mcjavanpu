package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Whole-chunk density generated as one big matmul.
 *
 * The old attempt fed the NPU a per-point 8-corner interpolation - an 8-wide matrix. That is
 * the one shape the HTP is worst at, and it lost. This does the opposite: every point becomes
 * a K-wide feature row, and the whole chunk's density falls out of a single A[m x K] * B[K x 1].
 *
 *   A: one row per sample point, K features  (x, y, z, low-frequency sin/cos, ramp terms)
 *   B: K weights, derived from the world seed
 *   out: density per point -> solid / air
 *
 * We are not replicating vanilla's noise field. The goal is a loadable world that the NPU
 * actually computed, so the feature set only has to produce recognisable terrain: a ground
 * ramp plus a few octaves of relief.
 */
public final class NpuTerrainGen {

    /** Features per point. Chosen so k is a comfortable HTP width. */
    public static final int K = 32;

    /** Lower eight sub-chunks band used for relief so terrain does not look flat. */
    private static final int OCTAVES = 4;

    private NpuTerrainGen() {}

    /** Result of generating one chunk volume. */
    public static final class Result {
        public final float[] density;
        public final int sizeX, sizeY, sizeZ;
        public final long npuUs, cpuUs;
        public final int solids;
        public final float minD, maxD;

        Result(float[] d, int sx, int sy, int sz, long npuUs, long cpuUs) {
            this.density = d;
            this.sizeX = sx; this.sizeY = sy; this.sizeZ = sz;
            this.npuUs = npuUs; this.cpuUs = cpuUs;
            int s = 0; float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
            for (float v : d) { if (v > 0) s++; if (v < mn) mn = v; if (v > mx) mx = v; }
            this.solids = s; this.minD = mn; this.maxD = mx;
        }
    }

    /**
     * Build the feature matrix for a chunk volume anchored at (ox, oy, oz).
     * Rows are laid out z, then y, then x - matching how DensityVolume indexes samples.
     */
    private static float[] buildFeatures(int sx, int sy, int sz, int ox, int oy, int oz, long seed) {
        int m = sx * sy * sz;
        float[] a = new float[m * K];
        float s1 = ((seed * 0x9E3779B9L) >>> 11) % 1000 / 1000f;
        float s2 = ((seed * 0x85EBCA6BL) >>> 13) % 1000 / 1000f;
        int i = 0;
        for (int x = 0; x < sx; x++) {
            for (int y = 0; y < sy; y++) {
                for (int z = 0; z < sz; z++) {
                    int base = i * K;
                    float wx = (ox + x) * 0.0625f;
                    float wy = (oy + y) * 0.0625f;
                    float wz = (oz + z) * 0.0625f;
                    int f = 0;
                    a[base + f++] = 1f;                       // constant
                    a[base + f++] = wx;                        // linear ramp x
                    a[base + f++] = wy;                        // linear ramp y
                    a[base + f++] = wz;                        // linear ramp z
                    a[base + f++] = wx * wx;
                    a[base + f++] = wy * wy;
                    a[base + f++] = wz * wz;
                    a[base + f++] = wx * wz;
                    for (int o = 0; o < OCTAVES; o++) {
                        float g = (1 << o) * 0.5f;
                        float ph = o * 1.7f;
                        float p1 = wx * g + ph + s1;
                        float p2 = wy * g * 1.7f + ph;
                        float p3 = wz * g + ph + s2;
                        int idx = (int) ((p1 * 256 + p2 * 3) & 1023);
                        a[base + f++] = T.sin(p1) * T.cos(p2);
                        a[base + f++] = T.cos(p3) * T.sin(p2);
                    }
                    a[base + f++] = (oy + y) * 0.0078125f;     // tall-scale ramp (ground level)
                    a[base + f++] = wy * (oy + y) * 3.0517578e-5f;
                    while (f < K) a[base + f++] = 0f;
                    i++;
                }
            }
        }
        return a;
    }

    /** Weights: enough ground bias that the world is walkable, rest from the seed. */
    private static float[] weights(long seed) {
        float[] b = new float[K];
        b[0] = -0.02f;                 // constant
        b[3] = 0.010f;                 // z ramp
        b[K - 2] = -1.6f;              // y gradient: solid below, air above
        b[K - 1] = 0.05f;              // relief scaled with height
        java.util.Random r = new java.util.Random(seed);
        for (int i = 8; i < 8 + OCTAVES * 2; i++) b[i] = (r.nextFloat() - 0.5f) * 0.22f;
        return b;
    }

    /** Generate a whole chunk volume through the NPU. */
    public static Result generate(int sx, int sy, int sz, int ox, int oy, int oz, long seed) {
        float[] a = buildFeatures(sx, sy, sz, ox, oy, oz, seed);
        float[] b = weights(seed);
        int m = sx * sy * sz;
        NpuRuntime.NpuResult r = NpuRuntime.submitMatmul8(a, m, K, b, 1);
        float[] dens = new float[m];
        long t0 = System.nanoTime();
        if (r.ok) {
            for (int i = 0; i < m; i++) dens[i] = r.out[i];
        } else {
            // host fallback so a missing service still produces a loadable chunk
            for (int i = 0; i < m; i++) {
                float s = 0f;
                for (int k = 0; k < K; k++) s += a[i * K + k] * b[k];
                dens[i] = s;
            }
        }
        long cpuUs = (System.nanoTime() - t0) / 1000;
        return new Result(dens, sx, sy, sz, r.us, cpuUs);
    }

    /** Tiny sin/cos table, since feature building happens per point in Java. */
    static final class T {
        private static final float[] SIN = new float[1024];
        private static final float[] COS = new float[1024];
        static {
            for (int i = 0; i < 1024; i++) {
                double a = i * Math.PI * 2 / 1024;
                SIN[i] = (float) Math.sin(a);
                COS[i] = (float) Math.cos(a);
            }
        }
        static float sin(float x) { return SIN[(int) Math.floor(x * 162.9746617) & 1023]; }
        static float cos(float x) { return COS[(int) Math.floor(x * 162.9746617) & 1023]; }
    }

    public static String describe() {
        return String.format(Locale.ROOT, "terrain gen: K=%d features, matmul A[m x %d] * B[%d x 1]", K, K, K);
    }
}
