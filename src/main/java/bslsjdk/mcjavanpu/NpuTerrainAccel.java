package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Terrain noise on the NPU, in the shape MC actually uses.
 *
 * Minecraft evaluates terrain noise on a coarse lattice (cellWidth 4, cellHeight 8)
 * and then interpolates to every block. Splitting it the same way is what makes it
 * batchable:
 *
 *   CPU  -> one Perlin evaluation per lattice CORNER  (cheap: a few thousand per chunk)
 *   NPU  -> the interpolation to every sample point   (heavy: ~100k per chunk)
 *
 * The interpolation is linear, so for a batch of points inside the same set of
 * lattice cells it is exactly
 *
 *     value(point) = sum over the 8 cell corners of  w_corner(point) * cornerValue
 *
 * which is one matmul:  A = (points x 8*cells) weights,  B = (8*cells x octaves).
 * A is block diagonal and every row holds exactly 8 non-zeros, so the batch has the
 * same sparse-neighbourhood shape as the light operator - the shape the NPU is good at.
 *
 * This class only does the maths. Whether it is worth calling is a separate question,
 * and what the counters in NpuStats are there to answer.
 */
public final class NpuTerrainAccel {

    /** Matches Minecraft's default terrain lattice. */
    public static final int CELL_W = 4;
    public static final int CELL_H = 8;

    public static final float OP_Q = 64.0f;      // weight quantisation
    public static final float CORNER_Q = 0.25f;  // corner-value quantisation

    private NpuTerrainAccel() {}

    public static final class Result {
        public final boolean ok;
        public final String error;
        public final float[] values;   // points * octaves, row-major (V=points, P=octaves)
        public final int points, cells, octaves;
        public final long npuUs, cpuUs;
        Result(boolean ok, String error, float[] v, int pts, int c, int o, long n, long h) {
            this.ok = ok; this.error = error; this.values = v;
            this.points = pts; this.cells = c; this.octaves = o; this.npuUs = n; this.cpuUs = h;
        }
        public String summary() {
            if (!ok) return "FAILED " + error;
            return String.format(Locale.ROOT, "points=%d cells=%d octaves=%d npu=%dus cpu=%dus x%.2f",
                    points, cells, octaves, npuUs, cpuUs,
                    npuUs <= 0 ? 0.0 : cpuUs / (double) npuUs);
        }
    }

    /**
     * Smoothstep, i.e. what Minecraft uses as the interpolation curve.
     */
    private static float fade(float t) { return t * t * (3.0f - 2.0f * t); }

    /**
     * Interpolate a batch.
     *
     * @param cornerValues 8 values per cell, laid out [cell*8 + corner], corner order is
     *                     x fastest, then y, then z (matching the weight builder below)
     * @param pointCell    cell index per point, length V
     * @param localX/Y/Z   position inside the cell per point, in [0,1), length V
     * @param cellCount    number of distinct cells, so columns = 8 * cellCount
     * @param octaves      how many corner channels; columns of B
     */
    public static Result interpolate(float[] cornerValues, int[] pointCell,
                                     float[] localX, float[] localY, float[] localZ,
                                     int cellCount, int octaves) {
        int V = pointCell.length;
        int cols = 8 * cellCount;
        if (cols <= 0 || V <= 0) return new Result(false, "EMPTY", null, V, cellCount, octaves, 0, 0);

        long t0 = System.nanoTime();
        byte[] A = new byte[V * cols];
        for (int i = 0; i < V; i++) {
            int c = pointCell[i];
            float fx = fade(localX[i]), fy = fade(localY[i]), fz = fade(localZ[i]);
            int base = i * cols + c * 8;
            int k = 0;
            for (int kz = 0; kz < 2; kz++) {
                float wz = kz == 0 ? (1 - fz) : fz;
                for (int ky = 0; ky < 2; ky++) {
                    float wy = ky == 0 ? (1 - fy) : fy;
                    for (int kx = 0; kx < 2; kx++) {
                        float wx = kx == 0 ? (1 - fx) : fx;
                        int q = Math.round(wx * wy * wz * OP_Q);
                        if (q > 127) q = 127; if (q < -128) q = -128;
                        A[base + k] = (byte) q;
                        k++;
                    }
                }
            }
        }

        byte[] B = new byte[cols * octaves];
        for (int c = 0; c < cellCount; c++) {
            for (int j = 0; j < 8; j++) {
                float v = cornerValues[c * 8 + j];
                int q = Math.round(v * CORNER_Q);
                if (q > 127) q = 127; if (q < -128) q = -128;
                for (int p = 0; p < octaves; p++) {
                    // same corner value on every octave channel for now; callers that carry
                    // per-octave corners can fill this differently
                    B[(c * 8 + j) * octaves + p] = (byte) q;
                }
            }
        }

        int[] sh = NpuDispatcher.planShape(V, cols, octaves);
        NpuRuntime.MatMulResult r = NpuDispatcher.submit(A, B, V, cols, octaves);
        long npuUs = (System.nanoTime() - t0) / 1000;
        if (!r.ok()) return new Result(false, r.error(), null, V, cellCount, octaves, npuUs, 0);

        long t1 = System.nanoTime();
        float[] out = new float[sh[0] * sh[2]];
        byte[] cb = r.c();
        for (int i = 0; i < sh[0] * sh[2]; i++) {
            out[i] = cb[i] * r.scaleC() / (NpuLightAccel.REF_Q) / (OP_Q * CORNER_Q);
        }
        long hostUs = (System.nanoTime() - t1) / 1000;

        NpuStats.NOISE.record((long) V * cols, npuUs, hostUs);
        return new Result(true, null, out, V, cellCount, octaves, npuUs, hostUs);
    }
}
