package bslsjdk.mcjavanpu;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Freezes one generation mode per world.
 *
 * Why this exists: the whole point of a benchmark is that the two things being compared stay
 * separate. If the mod can quietly fall back to vanilla halfway through - cache miss, a slow
 * submit, a service restart - then the world you end up with is a mixture of two generators.
 * Timings taken from it are meaningless, because you cannot tell which chunks came from which
 * path, and the terrain itself becomes inconsistent (a vanilla cliff welded to an NPU cliff).
 *
 * So the mode is chosen ONCE per world, at the first density sample, and stored against the
 * world seed. Everything after that reads the frozen value. Changing the setting in the menu
 * afterwards affects the next world, not this one - and the menu says so.
 *
 * The seed is the identity because it is the only stable handle on "which world" available
 * without adding new mixins into level loading. Two saves sharing a seed is rare enough to
 * accept, and the consequence is only that they share a mode.
 */
public final class NpuTerrainLock {

    /** Chosen once per world and then never changed. */
    private static volatile long lockedSeed = Long.MIN_VALUE;
    private static volatile String lockedMode = null;

    private static final AtomicLong FAILURES = new AtomicLong();
    private static volatile String lastFailure = "none";

    private NpuTerrainLock() {}

    /** Sentinel used when takeover is locked but the pipeline produced nothing. See takeoverFill. */
    public static final float MISSING = -2.0f;

    /**
     * Returns the mode this world must use, creating the lock on first call.
     *
     * Called on the worldgen path, so it must stay cheap: after the first call it is a
     * volatile read pair. The save() only ever happens once per world.
     */
    public static String acquire(long seed) {
        if (lockedSeed == seed && lockedMode != null) return lockedMode;

        NpuConfig cfg = NpuConfig.get();
        if (seed == Long.MIN_VALUE) {
            // World seed not known yet (chunk load hooks have not fired). Defer rather than
            // locking under a placeholder - a lock recorded now would silently outlive the
            // moment it was made for.
            return cfg.chunkMode;
        }

        String m = cfg.worldLocks.get(seed);
        if (m == null || !isMode(m)) {
            m = isMode(cfg.chunkMode) ? cfg.chunkMode : "vanilla";
            cfg.worldLocks.put(seed, m);
            cfg.save();
            NpuLog.log("world lock: seed=" + seed + " mode=" + m
                    + " (frozen now; later menu changes apply to new worlds only)");
        }
        lockedSeed = seed;
        lockedMode = m;
        return m;
    }

    /** Mode frozen for the world currently being generated, or null before the first sample. */
    public static String current() { return lockedMode; }

    public static long lockedSeed() { return lockedSeed; }

    public static boolean isLocked() { return lockedMode != null; }

    public static boolean isMode(String m) {
        if (m == null) return false;
        String s = m.toLowerCase(Locale.ROOT);
        return "vanilla".equals(s) || "npu".equals(s) || "assist".equals(s);
    }

    public static void recordFailure(String why) {
        FAILURES.incrementAndGet();
        lastFailure = why;
    }

    public static long failures() { return FAILURES.get(); }

    public static void resetFailures() { FAILURES.set(0); lastFailure = "none"; }

    public static String summary() {
        return "world_lock=" + (lockedMode == null ? "none yet" : lockedMode)
                + " takeover_misses=" + FAILURES.get()
                + " last=" + lastFailure;
    }
}
