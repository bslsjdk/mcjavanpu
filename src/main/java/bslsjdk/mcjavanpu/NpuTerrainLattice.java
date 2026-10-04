package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Terrain density at lattice resolution, then interpolated - the shape vanilla itself uses.
 *
 * The earlier attempt fed the NPU every block position of a chunk: 16 x 384 x 16 = 98304 points,
 * which needs a 3 MB feature matrix per chunk. At ~60ms of transfer plus a fixed call cost, that
 * can never beat the CPU, and that is exactly what the measurements kept saying.
 *
 * Vanilla does not do that. Its final_density node is:
 *
 *   "type": "minecraft:interpolated", "cell_size_xz": 4, "cell_size_y": 8
 *
 * i.e. it evaluates the expensive maths on a coarse lattice and fills the volume by trilinear
 * interpolation. For one chunk that lattice is
 *
 *   (16/4) x (384/8) x (16/4) = 4 x 48 x 4 = 768 points
 *
 * 768 points of 32 features is a 24 KB matrix - 128x smaller than the all-positions version. The
 * NPU call overhead finally amortises, and the interpolation that remains is work vanilla was
 * going to do anyway.
 *
 * So this class is the piece that makes terrain viable: match vanilla's own sampling structure
 * instead of fighting it.
 */
public final class NpuTerrainLattice {

    /** Tensor element budget reported by the service (CAPABILITIES max_elements). */
    public static final int MAX_ELEMENTS = 16384;

    /**
     * Lattice spacing.
     *
     * Vanilla uses 4/8, which for one chunk is 5 x 49 x 5 = 1225 lattice points. At K=16 that is
     * 19600 elements, which does NOT fit the service budget of 16384 and therefore needs two
     * submissions - and a submission that cannot be batched with anything else pays the fixed
     * cost every time.
     *
     * Widening to 8/16 gives 3 x 25 x 3 = 225 points, i.e. 3600 elements per chunk, so four
     * chunks fit in a single submission. That is the trade this file makes: somewhat softer
     * terrain detail in exchange for amortising the per-call cost over four chunks instead of
     * paying it per chunk. For an assist path that is the right way round - detail is a quality
     * knob, throughput is the whole point.
     */
    public static final int CELL_XZ = 8;
    public static final int CELL_Y = 16;
    /**
     * Features per lattice point.
     *
     * Bounded by the service: CAPABILITIES reports max_elements=16384, and one chunk's lattice
     * is 768 points, so the feature count must satisfy 768 * K <= 16384 -> K <= 21. Sixteen is the
     * largest power of two that fits, and it also keeps the int8 product inside range.
     */
    public static final int K = 16;
    private static final int OCTAVES = 4;

    private NpuTerrainLattice() {}

    public static final class Result {
        public final float[] density;      // full volume, MC buffer order (z, x, y)
        public final int sx, sy, sz;
        public final int latticePoints;
        public final long npuUs, interpUs;
        public final boolean usedNpu;
        public final String note;

        Result(float[] d, int sx, int sy, int sz, int pts, long npuUs, long interpUs,
               boolean npu, String note) {
            this.density = d; this.sx = sx; this.sy = sy; this.sz = sz;
            this.latticePoints = pts; this.npuUs = npuUs; this.interpUs = interpUs;
            this.usedNpu = npu; this.note = note;
        }

        public String summary() {
            return String.format(Locale.ROOT,
                "lattice %dx%dx%d pts=%d npu=%s npu_us=%d interp_us=%d volume=%d%s",
                sx, sy, sz, latticePoints, usedNpu ? "yes" : "no", npuUs, interpUs,
                sx * sy * sz, note.isEmpty() ? "" : " | " + note);
        }
    }

    /** Feature row for a lattice point. Bounded to [-1,1] so int8 keeps its precision. */
    private static void features(float[] dst, int off, float wx, float wy, float wz,
                                 float s1, float s2) {
        int f = 0;
        dst[off + f++] = 0.5f;
        dst[off + f++] = tri(wx * 0.03125f + s1);
        dst[off + f++] = tri(wz * 0.03125f + s2);
        dst[off + f++] = tri((wx + wz) * 0.015625f + s1 * 0.5f);
        dst[off + f++] = wy * 0.00390625f - 0.5f;          // vertical ramp
        dst[off + f++] = (wy * 0.00390625f) * tri(wx * 0.03125f);
        dst[off + f++] = (wy * 0.00390625f) * tri(wz * 0.03125f);
        dst[off + f++] = tri(wx * 0.0078125f + s2 * 0.25f);
        for (int o = 0; o < OCTAVES; o++) {
            float g = (1 << o) * 0.25f;
            float p1 = (wx + wz) * g * 0.125f + s1 * 8f;
            float p2 = wy * g * 0.0625f + s2 * 4f;
            dst[off + f++] = T.sin(p1) * T.cos(p2);
            dst[off + f++] = T.cos(p1) * T.sin(p2);
        }
        while (f < K) dst[off + f++] = 0f;
    }

    private static float tri(float v) {
        float x = (v - (float) Math.floor(v * 0.25f) * 4f) * 0.25f;
        return 4f * Math.abs(x - 0.5f) - 1f;
    }

    private static float[] weights(long seed) {
        float[] b = new float[K];
        java.util.Random r = new java.util.Random(seed * 0x9E3779B9L);
        for (int i = 0; i < 8; i++) b[i] = (r.nextFloat() - 0.5f) * 1.2f;
        for (int i = 8; i < K; i++) b[i] = (r.nextFloat() - 0.5f) * 1.6f;
        b[4] = -1.2f;    // wy ramp dominates: solid below, air above
        b[5] = 0.9f;
        b[6] = 0.9f;
        return b;
    }

    /**
     * How many chunks of this shape can share one submission under the element budget.
     * Used by the batch builder; at least 1 so a single chunk can always be served.
     */
    public static int maxChunksPerSubmit(int sx, int sy, int sz) {
        int perChunk = latticePoints(sx, sy, sz) * K;
        return Math.max(1, MAX_ELEMENTS / perChunk);
    }

    public static int latticePoints(int sx, int sy, int sz) {
        int lx = sx / CELL_XZ + 1, lz = sz / CELL_XZ + 1, ly = sy / CELL_Y + 1;
        return lx * ly * lz;
    }

    /**
     * Generates one chunk volume: lattice through the NPU, then trilinear fill on the CPU.
     * Output order matches DensitySampler.sampleVolumeNaive (z outer, x middle, y inner).
     */
    public static Result generate(int sx, int sy, int sz, int ox, int oy, int oz, long seed) {
        long tStart = System.nanoTime();
        int lx = sx / CELL_XZ + 1;
        int lz = sz / CELL_XZ + 1;
        int ly = sy / CELL_Y + 1;
        int pts = lx * ly * lz;

        float[] a = new float[pts * K];
        float s1 = ((seed >>> 3) % 997) / 997f;
        float s2 = ((seed >>> 11) % 991) / 991f;

        // Lattice coordinates in block space, x fastest, matching the interpolation loop below.
        int pi = 0;
        for (int iy = 0; iy < ly; iy++) {
            for (int iz = 0; iz < lz; iz++) {
                for (int ix = 0; ix < lx; ix++) {
                    features(a, pi * K, ox + ix * CELL_XZ, oy + iy * CELL_Y, oz + iz * CELL_XZ, s1, s2);
                    pi++;
                }
            }
        }

        float[] w = weights(seed);

        final float SA = 1f / 127f;
        final float SB = 1f / 127f;
        byte[] ab = new byte[pts * K];
        for (int p = 0; p < ab.length; p++) {
            int q = Math.round(a[p] / SA);
            ab[p] = (byte) (q < -127 ? -127 : (q > 127 ? 127 : q));
        }
        byte[] bb = new byte[K];
        for (int p = 0; p < K; p++) {
            int q = Math.round(w[p] / SB);
            bb[p] = (byte) (q < -127 ? -127 : (q > 127 ? 127 : q));
        }

        // The service caps a single tensor at max_elements (16384). A chunk lattice is 768*16 =
        // 12288, which fits, but the shape planner may pad m upward, so never assume it fits:
        // submit in row blocks and keep every individual tensor inside the budget.
        long prepareUs = (System.nanoTime() - tStart) / 1000;
        long t0 = System.nanoTime();
        float[] lattice = new float[pts];
        boolean usedNpu = false;
        String note = "";
        int rowsPerSubmit = Math.max(1, MAX_ELEMENTS / K);
        int done = 0;
        while (done < pts) {
            int rows = Math.min(rowsPerSubmit, pts - done);
            byte[] chunk = new byte[rows * K];
            System.arraycopy(ab, done * K, chunk, 0, rows * K);
            NpuRuntime.MatMulResult r = NpuDispatcher.submit(chunk, bb, rows, K, 1);
            if (!(r.ok() && r.c() != null)) {
                usedNpu = false;
                note = r.ok() ? "short result" : "npu unavailable, host lattice";
                break;
            }
            usedNpu = true;
            float scale = r.scaleC();
            if (scale == 0f) scale = SA * SB;
            byte[] c = r.c();
            for (int p = 0; p < rows; p++) lattice[done + p] = c[p] * scale;
            done += rows;
        }
        if (!usedNpu) {
            note = note.isEmpty() ? "NPU FAILED: no CPU fallback" : "NPU FAILED: " + note;
            NpuLog.error("TERRAIN_NPU_ONLY_FAIL " + note, null);
            return new Result(null, sx, sy, sz, pts, npuUs, 0, false, note);
        }
        long npuUs = (System.nanoTime() - t0) / 1000;

        long t1 = System.nanoTime();
        float[] out = new float[sx * sy * sz];
        // MC buffer order: z outer, x middle, y inner.
        int oi = 0;
        for (int z = 0; z < sz; z++) {
            int iz = z / CELL_XZ;
            float fz = (z % CELL_XZ) / (float) CELL_XZ;
            for (int x = 0; x < sx; x++) {
                int ix = x / CELL_XZ;
                float fx = (x % CELL_XZ) / (float) CELL_XZ;
                for (int y = 0; y < sy; y++) {
                    int iy = y / CELL_Y;
                    float fy = (y % CELL_Y) / (float) CELL_Y;
                    // trilinear over the eight lattice corners
                    float v000 = L(lattice, lx, ly, lz, ix, iy, iz);
                    float v100 = L(lattice, lx, ly, lz, ix + 1, iy, iz);
                    float v010 = L(lattice, lx, ly, lz, ix, iy + 1, iz);
                    float v110 = L(lattice, lx, ly, lz, ix + 1, iy + 1, iz);
                    float v001 = L(lattice, lx, ly, lz, ix, iy, iz + 1);
                    float v101 = L(lattice, lx, ly, lz, ix + 1, iy, iz + 1);
                    float v011 = L(lattice, lx, ly, lz, ix, iy + 1, iz + 1);
                    float v111 = L(lattice, lx, ly, lz, ix + 1, iy + 1, iz + 1);
                    float x00 = v000 + fx * (v100 - v000);
                    float x10 = v010 + fx * (v110 - v010);
                    float x01 = v001 + fx * (v101 - v001);
                    float x11 = v011 + fx * (v111 - v011);
                    float y0 = x00 + fy * (x10 - x00);
                    float y1 = x01 + fy * (x11 - x01);
                    out[oi++] = y0 + fz * (y1 - y0);
                }
            }
        }
        long interpUs = (System.nanoTime() - t1) / 1000;

        // One telemetry row per generated chunk: this is the data that decides what to attack
        // next (feature prep, IPC, or the CPU interpolation that follows).
        int[] sh = NpuDispatcher.planShape(pts, K, 1);
        NpuTelemetry.record(pts, K, 1, sh[0], sh[1], sh[2],
                0, prepareUs, npuUs, interpUs,
                (long) pts * K + (long) K, (long) pts);

        return new Result(out, sx, sy, sz, pts, npuUs, interpUs, usedNpu, note);
    }


    /**
     * Generates several chunks in ONE submission.
     *
     * This is the cross-chunk batching the review asked for, and the reason the lattice was
     * widened: with 225 points per chunk at K=16, four chunks cost 14400 elements, which fits the
     * 16384 budget. One call covers four chunks instead of four calls each paying their own fixed
     * cost - which, given that the native side serialises on a global lock, is the only lever that
     * actually raises throughput.
     *
     * All chunks must share the same volume shape; heterogeneous shapes go through the
     * single-chunk path.
     *
     * outNpuUs / outPrepareUs / outInterpUs receive the split timings when non-null, so telemetry
     * can still attribute cost per phase rather than lumping four chunks together.
     */
    public static float[][] generateMulti(int count, int[] cxs, int[] czs, int[] oys, long[] seeds,
                                          int sx, int sy, int sz,
                                          long[] outNpuUs, long[] outPrepareUs, long[] outInterpUs) {
        if (count <= 0) return new float[0][];
        int lx = sx / CELL_XZ + 1, lz = sz / CELL_XZ + 1, ly = sy / CELL_Y + 1;
        int pts = lx * ly * lz;
        int perChunk = pts * K;

        // Fill in row blocks that respect the element budget, batching as many chunks per
        // submission as the budget allows rather than assuming count fits.
        int chunksPerSubmit = Math.max(1, MAX_ELEMENTS / perChunk);
        float[][] out = new float[count][];
        float[] latticeAll = new float[count * pts];
        long totalNpu = 0, totalPrepare = 0;

        for (int start = 0; start < count; start += chunksPerSubmit) {
            int n = Math.min(chunksPerSubmit, count - start);
            long tStart = System.nanoTime();

            float[] feat = new float[n * pts * K];
            for (int ci = 0; ci < n; ci++) {
                int ox = cxs[start + ci] << 4;
                int oz = czs[start + ci] << 4;
                int oy = oys[start + ci];
                long seed = seeds[start + ci];
                float s1 = ((seed >>> 3) % 997) / 997f;
                float s2 = ((seed >>> 11) % 991) / 991f;
                int pi = 0;
                for (int iy = 0; iy < ly; iy++) {
                    for (int iz = 0; iz < lz; iz++) {
                        for (int ix = 0; ix < lx; ix++) {
                            int off = (ci * pts + pi) * K;
                            features(feat, off, ox + ix * CELL_XZ, oy + iy * CELL_Y, oz + iz * CELL_XZ, s1, s2);
                            pi++;
                        }
                    }
                }
            }
            totalPrepare += (System.nanoTime() - tStart) / 1000;

            final float SA = 1f / 127f;
            float[] w = weights(seeds[start]);
            byte[] ab = new byte[feat.length];
            for (int p = 0; p < feat.length; p++) {
                int q = Math.round(feat[p] / SA);
                ab[p] = (byte) (q < -127 ? -127 : (q > 127 ? 127 : q));
            }
            byte[] bb = new byte[K];
            for (int p = 0; p < K; p++) {
                int q = Math.round(w[p] / SA);
                bb[p] = (byte) (q < -127 ? -127 : (q > 127 ? 127 : q));
            }

            long t0 = System.nanoTime();
            NpuRuntime.MatMulResult r = NpuDispatcher.submit(ab, bb, n * pts, K, 1);
            totalNpu += (System.nanoTime() - t0) / 1000;

            if (r.ok() && r.c() != null && r.c().length >= n * pts) {
                float scale = r.scaleC();
                if (scale == 0f) scale = SA * SA;
                byte[] c = r.c();
                for (int p = 0; p < n * pts; p++) latticeAll[start * pts + p] = c[p] * scale;
            } else {
                String why = r.ok() ? "short NPU result" : "NPU submit failed";
                NpuLog.error("TERRAIN_NPU_ONLY_FAIL batch start=" + start + " count=" + n + " reason=" + why, null);
                return null;
            }
        }

        long t1 = System.nanoTime();
        for (int ci = 0; ci < count; ci++) {
            float[] lat = new float[pts];
            System.arraycopy(latticeAll, ci * pts, lat, 0, pts);
            out[ci] = interpolate(lat, lx, ly, lz, sx, sy, sz);
        }
        long interpUs = (System.nanoTime() - t1) / 1000;

        if (outNpuUs != null && outNpuUs.length > 0) outNpuUs[0] = totalNpu;
        if (outPrepareUs != null && outPrepareUs.length > 0) outPrepareUs[0] = totalPrepare;
        if (outInterpUs != null && outInterpUs.length > 0) outInterpUs[0] = interpUs;

        int[] sh = NpuDispatcher.planShape(count * pts, K, 1);
        NpuTelemetry.record(count * pts, K, 1, sh[0], sh[1], sh[2],
                0, totalPrepare, totalNpu, interpUs,
                (long) count * pts * K, (long) count * pts);
        return out;
    }

    /**
     * fy for each offset inside one y cell.
     *
     * A cell is CELL_Y voxels tall, and every voxel in it shares the same two lattice
     * planes - only the fraction changes. So the eight corner fetches and the x/z parts of
     * the interpolation are identical for all CELL_Y of them, and the only thing that
     * varies is this fraction. Precomputing it turns the inner loop into a single
     * multiply-add per output voxel.
     */
    private static final float[] FY = new float[CELL_Y];
    static {
        for (int i = 0; i < CELL_Y; i++) FY[i] = i / (float) CELL_Y;
    }

    /** Trilinear fill in MC buffer order (z, x, y). Shared by both entry points. */
    private static float[] interpolate(float[] lattice, int lx, int ly, int lz,
                                       int sx, int sy, int sz) {
        float[] out = new float[sx * sy * sz];
        int oi = 0;
        // lattice[(iy * lz + iz) * lx + ix], so a step in y moves a whole z*x plane.
        final int strideY = lz * lx;
        for (int z = 0; z < sz; z++) {
            int iz = z / CELL_XZ;
            float fz = (z % CELL_XZ) / (float) CELL_XZ;
            int iz1 = iz + 1 < lz ? iz + 1 : lz - 1;
            for (int x = 0; x < sx; x++) {
                int ix = x / CELL_XZ;
                float fx = (x % CELL_XZ) / (float) CELL_XZ;
                int ix1 = ix + 1 < lx ? ix + 1 : lx - 1;

                // The four (x,z) corner offsets within a plane. Both y planes reuse them,
                // and they are constant across the whole column below.
                final int b00 = iz * lx + ix;    // iy  , iz
                final int b10 = iz * lx + ix1;   // iy  , iz
                final int b01 = iz1 * lx + ix;   // iy  , iz+1
                final int b11 = iz1 * lx + ix1;  // iy  , iz+1

                // Walk y a cell at a time. Inside one cell the eight corners do not move.
                for (int y = 0; y < sy; y += CELL_Y) {
                    int iy = y / CELL_Y;
                    int iy1 = iy + 1 < ly ? iy + 1 : ly - 1;
                    final int p0 = iy * strideY;
                    final int p1 = iy1 * strideY;

                    float v000 = lattice[p0 + b00];
                    float v100 = lattice[p0 + b10];
                    float v010 = lattice[p1 + b00];
                    float v110 = lattice[p1 + b10];
                    float v001 = lattice[p0 + b01];
                    float v101 = lattice[p0 + b11];
                    float v011 = lattice[p1 + b01];
                    float v111 = lattice[p1 + b11];

                    // x, then the y pairs. Identical arithmetic to the straightforward
                    // per-voxel form, just hoisted out of the loop.
                    float x00 = v000 + fx * (v100 - v000);
                    float x10 = v010 + fx * (v110 - v010);
                    float x01 = v001 + fx * (v101 - v001);
                    float x11 = v011 + fx * (v111 - v011);

                    int count = Math.min(CELL_Y, sy - y);
                    for (int dy = 0; dy < count; dy++) {
                        float fy = FY[dy];
                        float y0 = x00 + fy * (x10 - x00);
                        float y1 = x01 + fy * (x11 - x01);
                        out[oi++] = y0 + fz * (y1 - y0);
                    }
                }
            }
        }
        return out;
    }

    private static float L(float[] lat, int lx, int ly, int lz, int ix, int iy, int iz) {
        if (ix >= lx) ix = lx - 1;
        if (iy >= ly) iy = ly - 1;
        if (iz >= lz) iz = lz - 1;
        return lat[(iy * lz + iz) * lx + ix];
    }

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
        private static int ix(float x) { return (int) Math.floor(x * 162.9746617) & 1023; }
        static float sin(float x) { return SIN[ix(x)]; }
        static float cos(float x) { return COS[ix(x)]; }
    }
}
