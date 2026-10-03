package bslsjdk.mcjavanpu;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Measures what this mod itself costs, separately from anything the NPU does.
 *
 * The question "is the frame rate bad because of us?" needs its own answer. Every
 * path here can be disabled, and the guard and the gate can each decide the work
 * is not worth doing - but until now there was no number saying whether the mod
 * was actually idle or quietly busy in the background.
 *
 * These counters answer it directly:
 *
 *   tick_us     - time spent in our server tick handler
 *   chunk_us    - time spent in the chunk-load hook
 *   mixin_us    - time spent inside the density sampler hook
 *
 * If frame rate is bad and these are near zero, the problem is somewhere else -
 * other mods, the renderer, or the device - and no amount of NPU work will fix
 * it. That is a genuinely useful result, even though it is a negative one.
 *
 * The counters are cheap: two nanoTime reads and a couple of adds per call.
 */
public final class NpuSelfCost {

    private static final AtomicLong TICK_US = new AtomicLong();
    private static final AtomicLong TICK_N = new AtomicLong();
    private static final AtomicLong TICK_MAX_US = new AtomicLong();

    private static final AtomicLong CHUNK_US = new AtomicLong();
    private static final AtomicLong CHUNK_N = new AtomicLong();

    private static final AtomicLong MIXIN_US = new AtomicLong();
    private static final AtomicLong MIXIN_N = new AtomicLong();
    private static final AtomicLong MIXIN_MAX_US = new AtomicLong();

    private static volatile long lastReportMs = System.currentTimeMillis();

    private NpuSelfCost() {}

    public static long tickStart() { return System.nanoTime(); }

    public static void tickEnd(long t0) {
        long us = (System.nanoTime() - t0) / 1000;
        TICK_US.addAndGet(us);
        TICK_N.incrementAndGet();
        if (us > TICK_MAX_US.get()) TICK_MAX_US.set(us);
    }

    public static void chunk(long us) {
        CHUNK_US.addAndGet(us);
        CHUNK_N.incrementAndGet();
    }

    public static void mixin(long us) {
        MIXIN_US.addAndGet(us);
        MIXIN_N.incrementAndGet();
        if (us > MIXIN_MAX_US.get()) MIXIN_MAX_US.set(us);
    }

    private static String avg(AtomicLong total, AtomicLong n) {
        long c = n.get();
        return c == 0 ? "0" : String.valueOf(total.get() / c);
    }

    public static String summary() {
        return "self_cost us: tick avg=" + avg(TICK_US, TICK_N) + " max=" + TICK_MAX_US.get()
                + " n=" + TICK_N.get()
                + " | chunk avg=" + avg(CHUNK_US, CHUNK_N) + " n=" + CHUNK_N.get()
                + " | sampler avg=" + avg(MIXIN_US, MIXIN_N) + " max=" + MIXIN_MAX_US.get()
                + " n=" + MIXIN_N.get();
    }

    /**
     * Called periodically. If our own cost is non-trivial it is worth saying so
     * loudly, because a mod that is supposed to be accelerating must not be a
     * measurable cost when it is doing nothing useful.
     */
    public static void maybeReport() {
        long now = System.currentTimeMillis();
        if (now - lastReportMs < 60_000L) return;
        lastReportMs = now;
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.debugLog) return;
        String s = summary();
        NpuLog.log(s);
        long tickAvg = TICK_N.get() == 0 ? 0 : TICK_US.get() / TICK_N.get();
        if (tickAvg > 1000) {
            NpuLog.warn("our own server-tick cost is " + tickAvg
                    + "us per tick - the mod is a measurable cost, not an accelerator");
        }
    }

    public static void reset() {
        TICK_US.set(0); TICK_N.set(0); TICK_MAX_US.set(0);
        CHUNK_US.set(0); CHUNK_N.set(0);
        MIXIN_US.set(0); MIXIN_N.set(0); MIXIN_MAX_US.set(0);
    }
}
