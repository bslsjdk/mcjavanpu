package bslsjdk.mcjavanpu;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-feature accounting, so "is the NPU actually doing anything" stops being a guess.
 *
 * Every feature carries its own switch, its own counters and its own runnable test.
 * The point is that any one of them can be turned on and exercised on its own: if a
 * counter stays at zero while the game is loading chunks, that feature is not wired
 * into anything, no matter what the code looks like.
 */
public final class NpuStats {

    public static final class Feature {
        public final String key;
        public final String label;
        public final boolean defaultOn;
        public volatile boolean enabled;
        public final AtomicLong calls = new AtomicLong();
        public final AtomicLong cells = new AtomicLong();
        public final AtomicLong npuUs = new AtomicLong();
        public final AtomicLong hostUs = new AtomicLong();
        public final AtomicLong lastCells = new AtomicLong();
        public final AtomicLong lastNpuUs = new AtomicLong();

        Feature(String key, String label, boolean defaultOn) {
            this.key = key; this.label = label; this.defaultOn = defaultOn; this.enabled = defaultOn;
        }

        public void record(long cells, long npuUs, long hostUs) {
            calls.incrementAndGet();
            this.cells.addAndGet(cells);
            this.npuUs.addAndGet(npuUs);
            this.hostUs.addAndGet(hostUs);
            this.lastCells.set(cells);
            this.lastNpuUs.set(npuUs);
        }

        public double speedup() {
            long n = npuUs.get(), h = hostUs.get();
            if (n <= 0) return 0.0;
            return h / (double) n;
        }

        public String line() {
            return String.format(Locale.ROOT, "%s %-6s calls=%-6d cells=%-10d npu=%7.1fms host=%7.1fms x%.2f",
                    enabled ? "[on ]" : "[off]", label, calls.get(), cells.get(),
                    npuUs.get() / 1000.0, hostUs.get() / 1000.0, speedup());
        }
    }

    public static final Feature LIGHT  = new Feature("light",  "light",  true);
    public static final Feature CHUNK  = new Feature("chunk",  "chunk",  true);
    public static final Feature NOISE  = new Feature("noise",  "noise",  false);
    /**
     * Terrain feature.
     *
     * This defaulted to false, and the density mixin gates on it - so with chunkMode=npu the whole
     * terrain path returned at the first line and never asked the NPU for anything. The log said it
     * plainly once the heartbeat was extended:
     *
     *   [off] blocks calls=0
     *
     * The mode setting is what should decide whether terrain is accelerated. This flag is just the
     * in-game switch for measurement, so it must not default to off.
     */
    public static final Feature BLOCKS = new Feature("blocks", "blocks", true);

    public static final Feature[] ALL = { LIGHT, CHUNK, NOISE, BLOCKS };

    private NpuStats() {}

    public static Feature byKey(String key) {
        for (Feature f : ALL) if (f.key.equalsIgnoreCase(key)) return f;
        return null;
    }

    /** Total milliseconds the NPU has been busy for, across every feature. */
    public static long totalNpuMs() {
        long t = 0;
        for (Feature f : ALL) t += f.npuUs.get();
        return t / 1000;
    }

    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append("NPU busy total: ").append(totalNpuMs()).append(" ms");
        for (Feature f : ALL) sb.append("\n").append(f.line());
        return sb.toString();
    }

    /**
     * Proof that the density hook is actually live.
     *
     * These counters live here rather than in the mixin because Mixin forbids non-private static
     * members on a mixin class - declaring one there makes the whole injection fail, which is what
     * crashed the game (InvalidMixinException: contains non-private static method).
     */
    public static final java.util.concurrent.atomic.AtomicLong MIXIN_SEEN =
            new java.util.concurrent.atomic.AtomicLong();

    public static void recordMixinSeen() { MIXIN_SEEN.incrementAndGet(); }

    public static long mixinSeen() { return MIXIN_SEEN.get(); }

    public static boolean mixinAnnounced;

    /** Logs once, the first time the game actually hands us a volume. */
    public static void announceMixinOnce() {
        if (mixinAnnounced) return;
        mixinAnnounced = true;
        NpuLog.log("MIXIN_ACTIVE DensitySampler$Bound.sampleVolume called by the game");
    }

}
