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

        /**
         * Outcome split. Without it "calls" is unreadable: a path can be called 180000 times
         * and have done nothing at all, and a single count cannot tell that apart from a path
         * that served every chunk.
         *
         *   attempts   - the hook was reached
         *   gated      - short-circuited before any submission could happen (gate, config, mode)
         *   submitted  - a request actually reached the device
         *   served     - a result was written back into the game
         *
         * gated == attempts means the path is pure overhead. submitted > 0 with served == 0
         * means the device answered but the result never landed.
         */
        public final AtomicLong attempted = new AtomicLong();
        public final AtomicLong gated = new AtomicLong();
        public final AtomicLong submitted = new AtomicLong();
        public final AtomicLong served = new AtomicLong();

        Feature(String key, String label, boolean defaultOn) {
            this.key = key; this.label = label; this.defaultOn = defaultOn; this.enabled = defaultOn;
        }

        /** Counts an attempt that was short-circuited before the device could be involved. */
        public void recordGated() {
            attempted.incrementAndGet();
            gated.incrementAndGet();
            calls.incrementAndGet();
        }

        /** Counts an attempt that reached the device and produced a usable result. */
        public void recordServed(long cells, long npuUs, long hostUs) {
            attempted.incrementAndGet();
            submitted.incrementAndGet();
            served.incrementAndGet();
            record(cells, npuUs, hostUs);
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

        /**
         * The outcome fields are the point of this line. "npu=0.0ms" used to be read as
         * "the device never ran", but the field was simply never filled in - the real work
         * happens on a worker thread whose timings never reached here. A timing that is
         * never assigned looks identical to a path that never executed, and the difference
         * decides whether to delete the path or fix it.
         */
        public String line() {
            return String.format(Locale.ROOT,
                    "%s %-6s calls=%-6d served=%-6d submitted=%-6d gated=%-6d cells=%-10d npu=%7.1fms host=%7.1fms x%.2f",
                    enabled ? "[on ]" : "[off]", label, calls.get(), served.get(), submitted.get(),
                    gated.get(), cells.get(), npuUs.get() / 1000.0, hostUs.get() / 1000.0, speedup());
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
