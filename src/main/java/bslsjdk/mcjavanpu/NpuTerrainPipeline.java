package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Throughput, not latency.
 *
 * The earlier bench measured a single small interpolation and concluded the NPU loses.
 * That measured the wrong thing. What matters for chunk generation is throughput across a
 * stream of tiles: one chunk is 98304 points, fed as N tiles, each tile a modest matmul.
 * A single tile costs a fixed overhead; a pipeline hides that overhead behind the next
 * tile's preparation.
 *
 * This walks tile sizes for one full chunk and prints points/second, so the comparison
 * against a CPU baseline is apples to apples:
 *
 *   CPU baseline -> points per second doing the same trilinear work in Java
 *   NPU stream   -> points per second across the tile sequence
 *
 * Only then is it meaningful to say whether the NPU helps terrain at all.
 */
public final class NpuTerrainPipeline {

    private NpuTerrainPipeline() {}

    /** How many points a full overworld chunk column contains: 16 * 384 * 16. */
    public static final int CHUNK_POINTS = 16 * 384 * 16;

    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT,
                "chunk points=%d (16x384x16)%n", CHUNK_POINTS));
        sb.append(String.format(Locale.ROOT, "%-8s %-8s %-10s %-12s %-14s%n",
                "tile", "tiles", "npu_ms", "points/sec", "per_point_us"));

        int[] tileSizes = {1024, 4096, 8192, 16384, 32768};
        for (int tile : tileSizes) {
            int tiles = (CHUNK_POINTS + tile - 1) / tile;
            long t0 = System.nanoTime();
            long npuTotalUs = 0;
            long hostTotalUs = 0;
            for (int i = 0; i < tiles; i++) {
                int n = Math.min(tile, CHUNK_POINTS - i * tile);
                // one cell of 8 corners per 128 points keeps the column count sane while
                // still exercising a real matmul shape
                int cells = Math.max(1, n / 128);
                float[] corners = new float[cells * 8];
                int[] cell = new int[n];
                float[] lx = new float[n], ly = new float[n], lz = new float[n];
                for (int k = 0; k < corners.length; k++) corners[k] = (k % 13) - 6;
                for (int k = 0; k < n; k++) {
                    cell[k] = k / 128 % cells;
                    lx[k] = ((k * 37) % 97) / 97.0f;
                    ly[k] = ((k * 53) % 89) / 89.0f;
                    lz[k] = ((k * 71) % 83) / 83.0f;
                }
                NpuTerrainAccel.Result r = NpuTerrainAccel.interpolate(corners, cell, lx, ly, lz, cells, 1);
                npuTotalUs += r.npuUs;
                hostTotalUs += r.cpuUs;
            }
            double wallMs = (System.nanoTime() - t0) / 1e6;
            double perPointUs = wallMs * 1000.0 / CHUNK_POINTS;
            double ptsPerSec = CHUNK_POINTS / (wallMs / 1000.0);
            sb.append(String.format(Locale.ROOT, "%-8d %-8d %-10.1f %-12.0f %-14.4f%n",
                    tile, tiles, wallMs, ptsPerSec, perPointUs));
        }

        long cpuPointsPerSec = cpuBaseline(CHUNK_POINTS);
        sb.append(String.format(Locale.ROOT,
                "%nCPU baseline (same trilinear work, Java): %,d points/sec%n", cpuPointsPerSec));
        return sb.toString();
    }

    /** Java baseline doing exactly the interpolation the NPU is asked to do. */
    private static long cpuBaseline(int points) {
        int cells = Math.max(1, points / 128);
        float[] corners = new float[cells * 8];
        for (int k = 0; k < corners.length; k++) corners[k] = (k % 13) - 6;
        float[] lx = new float[points], ly = new float[points], lz = new float[points];
        int[] cell = new int[points];
        for (int k = 0; k < points; k++) {
            cell[k] = k / 128 % cells;
            lx[k] = ((k * 37) % 97) / 97.0f;
            ly[k] = ((k * 53) % 89) / 89.0f;
            lz[k] = ((k * 71) % 83) / 83.0f;
        }
        // warm up, then time
        for (int warm = 0; warm < 2; warm++) cpuPass(corners, cell, lx, ly, lz, cells, points);
        long best = Long.MAX_VALUE;
        for (int rep = 0; rep < 5; rep++) {
            long t = System.nanoTime();
            cpuPass(corners, cell, lx, ly, lz, cells, points);
            best = Math.min(best, System.nanoTime() - t);
        }
        double ms = best / 1e6;
        return ms <= 0 ? 0 : (long) (points / (ms / 1000.0));
    }

    private static float cpuPass(float[] corners, int[] cell, float[] lx, float[] ly, float[] lz,
                                 int cells, int points) {
        float acc = 0f;
        for (int i = 0; i < points; i++) {
            int c = cell[i];
            float fx = lx[i], fy = ly[i], fz = lz[i];
            fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy); fz = fz * fz * (3 - 2 * fz);
            int k = 0;
            for (int kz = 0; kz < 2; kz++) {
                float wz = kz == 0 ? (1 - fz) : fz;
                for (int ky = 0; ky < 2; ky++) {
                    float wy = ky == 0 ? (1 - fy) : fy;
                    for (int kx = 0; kx < 2; kx++) {
                        float wx = kx == 0 ? (1 - fx) : fx;
                        acc += wx * wy * wz * corners[c * 8 + k];
                        k++;
                    }
                }
            }
        }
        return acc;
    }
}
