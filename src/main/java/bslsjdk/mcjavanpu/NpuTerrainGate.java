package bslsjdk.mcjavanpu;

/**
 * Decides whether an NPU-produced density volume is allowed to replace vanilla.
 *
 * This gate is the single most important piece of safety in the terrain path.
 *
 * Why it exists: the terrain currently comes from a hand-written interpreter of
 * final_density.json (NpuDfJson -> NpuDf -> NpuNoise). It is not Minecraft's
 * DensitySampler. It is close enough to look plausible and wrong enough to be a
 * different world - and when that result is written over the vanilla sampler's
 * buffer, the world downstream (lighting, carving, surface, water) is being
 * built on numbers the game never produced. That is how a "terrain accelerator"
 * turns into a crash that looks unrelated to terrain.
 *
 * So nothing reaches a DensityBuffer until every condition below holds. Any one
 * failing means the vanilla sampler runs, exactly as it would without the mod.
 *
 * Default is CLOSED. It stays closed until the parity harness proves the
 * interpreter matches Minecraft's own sampler to within an error budget. Turning
 * it on is a deliberate act, not a default.
 */
public final class NpuTerrainGate {

    /** Master allow. Off by default: correctness is not yet proven. */
    private static volatile boolean takeoverAllowed = false;

    /** Reasons the last check refused, for the log. */
    private static volatile String lastReason = "not evaluated yet";

    private static final java.util.concurrent.atomic.AtomicLong CHECKED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ALLOWED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong REFUSED = new java.util.concurrent.atomic.AtomicLong();

    private NpuTerrainGate() {}

    public static boolean isTakeoverAllowed() { return takeoverAllowed; }

    public static void setTakeoverAllowed(boolean v) {
        takeoverAllowed = v;
        NpuLog.log("terrain gate: takeover " + (v ? "ENABLED" : "DISABLED")
                + (v ? " - interpreter output will replace vanilla" : " - vanilla always wins"));
    }

    public static String lastReason() { return lastReason; }

    /**
     * Is it worth spending any CPU computing a volume at all?
     *
     * This is the counterpart to allowWrite, and it is the one that actually saves
     * frame time.
     *
     * allowWrite() only decides whether a RESULT may be used. By the time it is
     * called the work is already done. But the whole terrain prefetch pipeline -
     * requestWorkSet queuing up to 81 chunks, the background worker, and
     * NpuTerrainVanilla.fill() evaluating the complete density tree in Java - runs
     * to produce that result. With the gate closed (the default) every bit of that
     * is spent and then thrown away, because allowWrite refuses the write.
     *
     * So the pipeline has to ask THIS before it starts, not just before it writes.
     * Closed gate -> no queueing, no background evaluation, no IPC. The mod costs
     * nothing until someone proves the interpreter matches vanilla and opens it.
     */
    public static boolean worthComputing() {
        if (!takeoverAllowed) return false;
        NpuConfig cfg = NpuConfig.get();
        // Explicit npu mode is the experimental HTP terrain path. It intentionally
        // does not wait for the CPU parity interpreter to be built.
        if (cfg != null && "npu".equalsIgnoreCase(cfg.chunkMode)) return true;
        return NpuTerrainVanilla.ready() && NpuDfJson.lastUnsupported() == 0;
    }

    /**
     * Can this volume be written over the vanilla result?
     *
     * Called immediately before any ci.cancel() in the density sampler mixin.
     * Must stay cheap: it runs on the worldgen path.
     */
    public static boolean allowWrite(int cx, int cz, float[] volume, int expectedSize) {
        CHECKED.incrementAndGet();

        if (!takeoverAllowed) return refuse("gate closed (correctness unproven)");
        if (volume == null) return refuse("volume null");
        if (expectedSize > 0 && volume.length < expectedSize) {
            return refuse("short volume " + volume.length + "<" + expectedSize);
        }

        NpuConfig cfg = NpuConfig.get();
        boolean npuMode = cfg != null && "npu".equalsIgnoreCase(cfg.chunkMode);
        // npu mode deliberately does not claim vanilla parity. Its result is produced by
        // the experimental HTP lattice generator, so the parity tree is not a prerequisite.
        if (!npuMode) {
            if (!NpuTerrainVanilla.ready()) return refuse("tree not ready: " + NpuTerrainVanilla.failReason());

            int unsupported = NpuDfJson.lastUnsupported();
            if (unsupported > 0) return refuse(unsupported + " unsupported density nodes");
        }

        // Any non-finite value poisons everything downstream.
        for (int i = 0; i < volume.length; i++) {
            float v = volume[i];
            if (v != v || v == Float.POSITIVE_INFINITY || v == Float.NEGATIVE_INFINITY) {
                return refuse("non-finite value at " + i);
            }
        }

        ALLOWED.incrementAndGet();
        lastReason = "ok";
        return true;
    }

    private static boolean refuse(String why) {
        REFUSED.incrementAndGet();
        lastReason = why;
        return false;
    }

    public static String summary() {
        return "gate=" + (takeoverAllowed ? "OPEN" : "CLOSED")
                + (worthComputing() ? " computing" : " idle(no compute)")
                + " checked=" + CHECKED.get()
                + " allowed=" + ALLOWED.get()
                + " refused=" + REFUSED.get()
                + " last=" + lastReason;
    }

    public static void resetStats() {
        CHECKED.set(0); ALLOWED.set(0); REFUSED.set(0);
    }
}
