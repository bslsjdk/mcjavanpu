package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Measures the real cost of batching terrain interpolation, at MC's real sizes.
 *
 * The trap this exists to expose: a block-diagonal operator for V points over C cells
 * needs V x (8*C) entries. At one chunk's real numbers (V = 98304, and the cell count
 * that comes out of a 16 x height x 16 volume) that matrix is far beyond anything worth
 * shipping over a socket, no matter how fast the multiply is.
 *
 * So this walks the scaling curve instead of assuming, and prints bytes-per-call next to
 * the timings. If the bytes explode before the time drops, the approach is dead and we
 * should stop pretending otherwise.
 */
public final class NpuTerrainBench {

    private NpuTerrainBench() {}

    public static String run() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.ROOT, "%-10s %-8s %-9s %-12s %-10s %-10s%n",
                "points", "cells", "cols", "A_bytes", "npu_us", "verdict"));

        int[] points = {4096, 16384, 65536, 98304};
        int[] cells  = {16, 64, 256, 1024};

        for (int v : points) {
            for (int c : cells) {
                long aBytes = (long) v * 8L * c;
                if (aBytes > 64L * 1024 * 1024) {
                    sb.append(String.format(Locale.ROOT, "%-10d %-8d %-9d %-12s %-10s %-10s%n",
                            v, c, 8 * c, human(aBytes), "-", "SKIP too big"));
                    continue;
                }
                float[] cornerValues = new float[c * 8];
                int[] pointCell = new int[v];
                float[] lx = new float[v], ly = new float[v], lz = new float[v];
                for (int i = 0; i < c * 8; i++) cornerValues[i] = (i % 17) - 8;
                for (int i = 0; i < v; i++) {
                    pointCell[i] = i % c;
                    lx[i] = ((i * 37) % 97) / 97.0f;
                    ly[i] = ((i * 53) % 89) / 89.0f;
                    lz[i] = ((i * 71) % 83) / 83.0f;
                }
                NpuLightAccel.Result unused = null;
                NpuTerrainAccel.Result r = NpuTerrainAccel.interpolate(cornerValues, pointCell, lx, ly, lz, c, 1);
                String verdict = r.ok ? "ok" : ("fail " + r.error);
                sb.append(String.format(Locale.ROOT, "%-10d %-8d %-9d %-12s %-10d %-10s%n",
                        v, c, 8 * c, human(aBytes), r.npuUs, verdict));
            }
        }
        NpuLog.log("terrain bench\n" + sb);
        return sb.toString();
    }

    private static String human(long b) {
        if (b < 1024) return b + "B";
        if (b < 1024 * 1024) return (b / 1024) + "K";
        return (b / (1024 * 1024)) + "M";
    }
}
