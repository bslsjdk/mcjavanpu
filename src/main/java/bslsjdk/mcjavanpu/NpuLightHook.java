package bslsjdk.mcjavanpu;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The bridge between Minecraft's own light engine and the NPU.
 *
 * LightEngine.runLightUpdates() is where the game drains its BFS queues, so it is
 * the one place that sees every light update. The mixin calls into here.
 *
 * Modes:
 *   vanilla - we only count, nothing is touched
 *   assist  - we count and periodically fold the player's neighbourhood through
 *             the NPU as a warm-up sample, then let the game finish
 *   npu     - same fold, but the result is written back
 *
 * Counting first is deliberate: writing into the engine before knowing how often it
 * runs and how big the queues are would be guesswork. The counters below tell us
 * exactly that, and they cost nothing when debug logging is off.
 */
public final class NpuLightHook {

    private static final AtomicLong ENGINE_CALLS = new AtomicLong();
    private static final AtomicLong LAST_REPORT = new AtomicLong();
    private static volatile long lastReportMs;

    private NpuLightHook() {}

    /** Called on every LightEngine.runLightUpdates(). Must stay cheap. */
    public static void onLightUpdate() {
        long n = ENGINE_CALLS.incrementAndGet();
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        if ("vanilla".equalsIgnoreCase(cfg.lightMode)) {
            if (cfg.debugLog && n % 500 == 0) NpuLog.log("light engine ran " + n + " times (vanilla, counted only)");
            return;
        }
        // Non-vanilla modes are counted now; the actual folding is driven from the
        // command path until the fold is proven cheap enough to run inline.
        long now = System.currentTimeMillis();
        if (now - lastReportMs > 10_000L) {
            lastReportMs = now;
            LAST_REPORT.incrementAndGet();
            if (cfg.debugLog) {
                NpuLog.log("light engine calls=" + n + " mode=" + cfg.lightMode + " (fold pending)");
            }
        }
    }

    public static long engineCalls() { return ENGINE_CALLS.get(); }
    public static String summary() {
        return "engine_calls=" + ENGINE_CALLS.get() + " reports=" + LAST_REPORT.get();
    }
}
