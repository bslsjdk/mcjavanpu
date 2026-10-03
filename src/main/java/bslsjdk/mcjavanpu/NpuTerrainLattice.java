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

    public static final int CELL_XZ = 4;
    public static final int CELL_Y = 8;
    public static final int K = 32;
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
        for (int i = 8; i < 8 + OCTAVES * 2; i++) b[i] = (r.nextFloat() - 0.5f) * 1.6f;
        if (K - 2 >= 0) b[K - 2] = -1.2f;    // ground gradient dominates
        if (K - 5 >= 0) b[K - 5] = 0.9f;
        return b;
    }

    /**
     * Generates one chunk volume: lattice through the NPU, then trilinear fill on the CPU.
     * Output order matches DensitySampler.sampleVolumeNaive (z outer, x middle, y inner).
     */
    public static Result generate(int sx, int sy, int sz, int ox, int oy, int oz, long seed) {
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

        long t0 = System.nanoTime();
        NpuRuntime.MatMulResult r = NpuDispatcher.submit(ab, bb, pts, K, 1);
        long npuUs = (System.nanoTime() - t0) / 1000;

        float[] lattice = new float[pts];
        boolean usedNpu = false;
        String note = "";
        if (r.ok() && r.c() != null) {
            usedNpu = true;
            float scale = r.scaleC();
            if (scale == 0f) scale = SA * SB;
            byte[] c = r.c();
            for (int p = 0; p < pts; p++) lattice[p] = c[p] * scale;
        } else {
            note = "npu unavailable, host lattice";
            for (int p = 0; p < pts; p++) {
                float sum = 0f;
                for (int k = 0; k < K; k++) sum += a[p * K + k] * w[k];
                lattice[p] = sum;
            }
        }

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
        return new Result(out, sx, sy, sz, pts, npuUs, interpUs, usedNpu, note);
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
