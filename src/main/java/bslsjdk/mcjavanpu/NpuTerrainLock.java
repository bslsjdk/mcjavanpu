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

    /**
     * Misses where vanilla was allowed to generate instead of the sentinel.
     *
     * Counted separately on purpose. A sentinel miss is evidence about the NPU. A vanilla
     * fallback is not - it says the NPU was already known to be unusable, so the chunk was
     * never ours to lose. Mixing the two would hide a dead pipeline behind normal terrain.
     */
    private static final AtomicLong VANILLA_FALLBACKS = new AtomicLong();

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
        if (seed == NpuChunkWork.SEED_UNKNOWN) {
            // World seed not known yet (chunk load hooks have not fired). Defer rather than
            // locking under a placeholder - a lock recorded now would silently outlive the
            // moment it was made for.
            return isMode(cfg.chunkMode) ? cfg.chunkMode.toLowerCase(Locale.ROOT) : "vanilla";
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
        long n = FAILURES.incrementAndGet();
        lastFailure = why;
        // A silent miss is the one thing this mode must never be: it would look exactly like a
        // successful takeover. The first one is logged in full; after that it is rate limited by
        // time rather than by count.
        //
        // "Every 50th" was the old rule, and at 180000 chunks it still emitted 3600 lines. The
        // point of the line is to say the pipeline is failing, and one line a second says that
        // just as well while leaving room for the rest of the log. The suppressed count rides
        // along so the magnitude is not lost with the individual lines.
        if (n == 1) {
            NpuLog.error("TAKEOVER MISS #" + n + " at chunk: " + why
                    + " - wrote the missing-volume sentinel, NOT vanilla terrain", null);
        } else {
            NpuLog.throttledError("takeover-miss", NpuLog.DEFAULT_THROTTLE_MS,
                    "TAKEOVER MISS #" + n + " at chunk: " + why
                    + " - wrote the missing-volume sentinel, NOT vanilla terrain");
        }
    }

    public static long failures() { return FAILURES.get(); }

    public static long vanillaFallbacks() { return VANILLA_FALLBACKS.get(); }

    /**
     * Record a miss that was handed back to vanilla rather than marked missing.
     *
     * Only for the case where the NPU is already known to be unusable - guard degraded, or the
     * service not answering. In that state a sentinel would not measure anything, it would just
     * destroy a world for a fault that is already logged elsewhere.
     */
    public static void recordVanillaFallback(String why) {
        long n = VANILLA_FALLBACKS.incrementAndGet();
        lastFailure = "vanilla: " + why;
        String msg = "TAKEOVER FALLBACK #" + n + ": " + why
                + " - NPU already known unusable, vanilla generated this chunk";
        if (n == 1) {
            NpuLog.warn(msg);
        } else {
            NpuLog.throttledWarn("takeover-fallback", NpuLog.DEFAULT_THROTTLE_MS, msg);
        }
    }

    /**
     * True when the NPU cannot possibly produce a volume right now.
     *
     * This is the difference between "the pipeline owes us a chunk and did not deliver" - which
     * deserves the sentinel, because it is a measurement - and "the pipeline is not running" -
     * which deserves vanilla, because writing holes there teaches us nothing and ruins the world.
     */
    public static boolean npuKnownUnusable() {
        return NpuGuard.isDegraded() || !NpuServiceClient.isAvailable();
    }

    public static void resetFailures() { FAILURES.set(0); lastFailure = "none"; }

    public static String summary() {
        return "world_lock=" + (lockedMode == null ? "none yet" : lockedMode)
                + " takeover_misses=" + FAILURES.get()
                + " vanilla_fallbacks=" + VANILLA_FALLBACKS.get()
                + " last=" + lastFailure;
    }
}
