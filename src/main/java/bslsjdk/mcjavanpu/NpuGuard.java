package bslsjdk.mcjavanpu;

import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps the NPU from making the game slower.
 *
 * An optimisation mod has to earn its keep continuously, not just in a benchmark.
 * This watches the real cost of every NPU call and backs off when the cost stops
 * being worth paying, then quietly comes back when it is worth paying again.
 *
 * Two independent trip conditions:
 *
 *   latency   - rolling p99 of submit() exceeds the budget. p99 rather than an
 *               average, because the spikes are what a player actually feels.
 *   failures  - consecutive IPC failures, i.e. the service went away.
 *
 * While degraded, callers get told "no" immediately. They fall back to whatever
 * the vanilla path was, so the game keeps running normally. Recovery is gradual:
 * one probe call is allowed through periodically, and only a clean run re-arms.
 *
 * This is deliberately not a config toggle someone has to remember. It is on
 * whenever guardEnabled is true, which is the default.
 */
public final class NpuGuard {

    /** Rolling window of recent submit() costs, in microseconds. */
    private static final int WINDOW = 64;
    private static final long[] us = new long[WINDOW];
    private static int cursor;
    private static int filled;
    private static final Object LOCK = new Object();

    /**
     * Samples are ignored until this many calls have been seen.
     *
     * The first calls build the graphs for a shape and measured ~17 ms against an 8 ms budget. With
     * a permanent p99 trip, that one cold sample condemned the feature for the rest of the session:
     * guard degraded -> every request rejected -> p99 never improves. 8231 requests submitted, 0
     * processed, while the service was up. Warm-up samples are therefore not evidence.
     */
    private static final int WARMUP_CALLS = 24;
    private static int observed;

    /** Above this, one call is not worth it for a per-frame feature. */
    private static volatile long budgetUs = 8_000L;   // 8 ms
    private static volatile int failBudget = 3;

    private static volatile boolean degraded;
    private static volatile String reason = "";
    private static volatile long degradedAtMs;
    private static volatile long lastProbeMs;
    private static final long PROBE_INTERVAL_MS = 15_000L;

    public static final AtomicLong totalCalls = new AtomicLong();
    public static final AtomicLong rejectedCalls = new AtomicLong();
    public static final AtomicLong degradedEvents = new AtomicLong();

    private NpuGuard() {}

    /** True when callers should skip the NPU for now. */
    public static boolean isDegraded() { return degraded; }

    public static String reason() { return reason; }

    /** Ask whether an NPU call is currently advisable. Cheap: no IPC, no lock contention. */
    public static boolean allow() {
        if (!NpuConfig.get().guardEnabled) return true;
        if (!degraded) return true;
        // Let one probe through now and then, so a recovered service is noticed.
        long now = System.currentTimeMillis();
        if (now - lastProbeMs >= PROBE_INTERVAL_MS) {
            lastProbeMs = now;
            return true;
        }
        rejectedCalls.incrementAndGet();
        return false;
    }

    /** Record the wall time of a completed submit(), in microseconds. */
    public static void recordUs(long micros) {
        totalCalls.incrementAndGet();
        if (!NpuConfig.get().guardEnabled) return;

        // A call that came back inside budget is evidence the path works; take that as a reason to
        // recover rather than staying degraded until an arbitrary timeout. Without this, a single
        // slow cold call kept the guard degraded for the whole session.
        if (micros <= budgetUs && degraded) {
            degraded = false;
            reason = "";
            synchronized (LOCK) {
                filled = 0;
                cursor = 0;
            }
        }

        long p99;
        synchronized (LOCK) {
            observed++;
            // Ignore the warm-up window: those samples are dominated by graph construction and say
            // nothing about steady-state cost.
            if (observed <= WARMUP_CALLS) return;
            us[cursor] = micros;
            cursor = (cursor + 1) % WINDOW;
            if (filled < WINDOW) filled++;
            p99 = percentileLocked(0.99);
        }

        if (p99 > budgetUs && !degraded) {
            degrade(String.format(Locale.ROOT,
                    "p99 %dus over budget %dus", p99, budgetUs));
        } else if (degraded && p99 <= budgetUs / 2) {
            // Hysteresis: recover only at half the trip level, otherwise it flaps.
            recover();
        }
    }

    /**
     * Record a failed call.
     *
     * Not every failure means the service is unhealthy. A caller asking for a
     * shape that cannot be expressed, or passing a short buffer, is a bug in the
     * caller and must not disable the NPU for everything else. Only transport and
     * availability failures count towards backing off.
     */
    public static void recordFailure(String why) {
        totalCalls.incrementAndGet();
        if (!NpuConfig.get().guardEnabled) return;
        if (degraded) return;

        String e = why == null ? "" : why;
        boolean transport = e.startsWith("SERVICE_")
                || e.startsWith("MCNPU_OFFLINE")
                || e.contains("SocketTimeout")
                || e.contains("ConnectException")
                || e.contains("closed");
        if (!transport) {
            // Caller-side error: worth logging once, not worth disabling anything.
            if (NpuConfig.get().debugLog) NpuLog.warn("guard: non-transport failure ignored: " + e);
            return;
        }

        lastProbeMs = System.currentTimeMillis();
        degrade("transport failure: " + e);
    }

    private static void degrade(String why) {
        degraded = true;
        reason = why;
        degradedAtMs = System.currentTimeMillis();
        degradedEvents.incrementAndGet();
        NpuLog.warn("guard DEGRADED: " + why + " - features fall back to CPU until it recovers");
    }

    private static void recover() {
        degraded = false;
        reason = "";
        NpuLog.log("guard recovered, NPU re-enabled");
    }

    /** Rolling p99 over the window, in microseconds. */
    public static long p99Us() {
        synchronized (LOCK) { return percentileLocked(0.99); }
    }

    public static long p50Us() {
        synchronized (LOCK) { return percentileLocked(0.50); }
    }

    private static long percentileLocked(double q) {
        if (filled == 0) return 0;
        long[] c = Arrays.copyOf(us, filled);
        Arrays.sort(c);
        int i = (int) Math.min(c.length - 1, Math.ceil(q * c.length) - 1);
        return c[Math.max(0, i)];
    }

    public static void setBudgetUs(long v) { budgetUs = Math.max(100L, v); }
    public static long budgetUs() { return budgetUs; }

    /** Reset counters and state. Used when switching worlds. */
    public static void reset() {
        synchronized (LOCK) { cursor = 0; filled = 0; }
        degraded = false;
        reason = "";
    }

    public static String summary() {
        return String.format(Locale.ROOT,
                "guard %s p50=%dus p99=%dus budget=%dus calls=%d rejected=%d trips=%d %s",
                degraded ? "DEGRADED" : "ok", p50Us(), p99Us(), budgetUs(),
                totalCalls.get(), rejectedCalls.get(), degradedEvents.get(), reason);
    }
}
