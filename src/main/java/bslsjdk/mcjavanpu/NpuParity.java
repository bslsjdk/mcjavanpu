package bslsjdk.mcjavanpu;

import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Parity harness: the evidence NpuTerrainGate is waiting for.
 *
 * The gate refuses to let the interpreter output replace Minecraft's, for a good reason - our
 * maths is a re-implementation of final_density.json, not the game's own sampler, and terrain
 * that is close but not equal poisons everything built on top of it. This class is what turns
 * that from an opinion into a measurement.
 *
 * How it gets a clean comparison. Minecraft fills a DensityBuffer for every chunk volume it
 * generates. We let it. Once sampleVolume has returned - and only while the gate is still closed,
 * so the buffer genuinely holds vanilla numbers - we snapshot it, evaluate the same volume with
 * our own tree on a background thread, and compare point for point.
 *
 * Why point by point instead of a checksum: a single wrong node (one misread field, one wrong
 * clamp bound) shows up as a few points differing by a large amount, which any average hides. So
 * the report carries the worst absolute difference, the count of points outside the budget, and
 * the index of the first offender.
 *
 * Reading the numbers:
 *   bad == 0 and max_abs <= 1e-4  - the interpreter and the game agree to float precision * anything else                - a real mismatch, with a location attached *
 * Sampling is rare (one chunk in EVERY) and capped, and it runs off-thread at low priority, for
 * the same reason the prefetcher is bounded: evidence must never cost the player frames.
 */
public final class NpuParity {

    /** One specimen every this many chunk volumes. */
    private static final int EVERY = 64;

    /** Never spend more than this many volumes on evidence. */
    private static final int MAX_RUNS = 3;

    /** Consecutive clean runs required before the gate may open. */
    private static final int MIN_PASSES = 3;

    /** A difference above this counts as a mismatched point. */
    private static final double BAD_EPS = 1e-3;

    /** A run is clean only when every point is within this. */
    private static final double GOOD_EPS = 1e-4;

    private static final AtomicBoolean BUSY = new AtomicBoolean();

    private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcnpu-parity");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static final Object LOCK = new Object();
    private static long seen;
    private static int runs;
    private static int passes;
    private static volatile String last = "not run";

    private NpuParity() {}

    public static void resetStats() {
        synchronized (LOCK) { seen = 0; runs = 0; passes = 0; }
        last = "not run";
    }

    public static String summary() {
        synchronized (LOCK) {
            return "parity: runs=" + runs + "/" + MAX_RUNS + " passes=" + passes + "/" + MIN_PASSES
                    + " | " + last;
        }
    }

    /**
     * Called after vanilla has filled the buffer for a chunk volume. Decides whether this one is
     * worth keeping, and if so hands the comparison to the background thread.
     */
    public static void offer(DensityBuffer buffer, DensityVolume volume) {
        try {
            if (buffer == null || volume == null) return;
            if (!NpuConfig.get().enabled) return;

            // Only meaningful while the gate is closed - afterwards the buffer is our own output
            // and comparing it with ourselves would prove nothing.
            if (NpuTerrainGate.isTakeoverAllowed()) return;

            if (!NpuTerrainVanilla.ready()) return;
            if (NpuDfJson.lastUnsupported() != 0) return;

            final int run;
            synchronized (LOCK) {
                seen++;
                if (seen % EVERY != 0) return;
                if (runs >= MAX_RUNS) return;
                run = ++runs;
            }

            final int size = buffer.size();
            final float[] vanilla = new float[size];
            for (int i = 0; i < size; i++) vanilla[i] = buffer.get(i);

            final int sx = volume.sizeX(), sy = volume.sizeY(), sz = volume.sizeZ();
            final int ox = volume.minBlockX(), oy = volume.minBlockY(), oz = volume.minBlockZ();
            final int stx = Math.max(1, volume.stepBlockX());
            final int sty = Math.max(1, volume.stepBlockY());
            final int stz = Math.max(1, volume.stepBlockZ());

            if (!BUSY.compareAndSet(false, true)) return;
            THREAD.execute(() -> {
                try {
                    compare(run, sx, sy, sz, ox, oy, oz, stx, sty, stz, vanilla);
                } catch (Throwable t) {
                    NpuLog.error("parity run failed", t);
                } finally {
                    BUSY.set(false);
                }
            });
        } catch (Throwable t) {
            NpuLog.error("parity offer failed", t);
        }
    }

    private static void compare(int run, int sx, int sy, int sz,
                                int ox, int oy, int oz,
                                int stx, int sty, int stz, float[] vanilla) {
        long t0 = System.nanoTime();
        float[] mine = NpuTerrainVanilla.fill(sx, sy, sz, ox, oy, oz,
                NpuTerrainVanilla.currentSeed(), stx, sty, stz);
        long us = (System.nanoTime() - t0) / 1000;

        if (mine == null) {
            last = "run #" + run + " skipped: fill returned null";
            NpuLog.log("parity run #" + run + " skipped: " + NpuTerrainVanilla.summary());
            return;
        }

        int n = Math.min(mine.length, vanilla.length);
        double maxAbs = 0;
        double sumAbs = 0;
        long bad = 0;
        int firstBad = -1;
        double firstVanilla = 0, firstMine = 0;

        for (int i = 0; i < n; i++) {
            double a = vanilla[i];
            double b = mine[i];
            double d = a > b ? a - b : b - a;
            if (d > maxAbs) maxAbs = d;
            sumAbs += d;
            if (d > BAD_EPS) {
                bad++;
                if (firstBad < 0) {
                    firstBad = i;
                    firstVanilla = a;
                    firstMine = b;
                }
            }
        }

        double meanAbs = n == 0 ? 0 : sumAbs / n;
        boolean clean = (bad == 0 && maxAbs <= GOOD_EPS);

        StringBuilder sb = new StringBuilder(160);
        sb.append("parity run #").append(run)
          .append(" vol=").append(sx).append('x').append(sy).append('x').append(sz)
          .append(" step=").append(stx).append('/').append(sty).append('/').append(stz)
          .append(" points=").append(n)
          .append(" max_abs=").append(String.format(java.util.Locale.ROOT, "%.3e", maxAbs))
          .append(" mean_abs=").append(String.format(java.util.Locale.ROOT, "%.3e", meanAbs))
          .append(" bad=").append(bad);
        if (firstBad >= 0) {
            sb.append(" first_bad@").append(firstBad)
              .append(" vanilla=").append(firstVanilla)
              .append(" mine=").append(firstMine);
        }
        sb.append(" our_us=").append(us)
          .append(" per_sample_us=").append(n == 0 ? 0 : us / n)
          .append(" | ").append(NpuTerrainVanilla.summary());
        last = sb.toString();
        NpuLog.log(sb.toString());

        if (!clean) {
            NpuLog.log("parity: MISMATCH - the interpreter does not reproduce vanilla; gate stays closed");
            return;
        }

        int p;
        synchronized (LOCK) {
            passes++;
            p = passes;
        }
        if (p >= MIN_PASSES) {
            NpuLog.log("parity: " + p + " clean runs, worst difference " + maxAbs
                    + " over " + n + " points - opening the terrain gate");
            NpuTerrainGate.setTakeoverAllowed(true);
        } else {
            NpuLog.log("parity: clean run " + p + "/" + MIN_PASSES + " (" + (MAX_RUNS - runs)
                    + " specimen(s) left)");
        }
    }
}
