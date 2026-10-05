package bslsjdk.mcjavanpu;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
     * Samples needed before the guard is allowed to trip.
     *
     * With a window of one, the first sample IS the p99, so a single cold call
     * decided the session. Log evidence: p99 161370us (161 ms) against an 8 ms
     * budget, tripped on the first observation after warm-up, and never recovered -
     * every later request was rejected, so no new sample could ever improve the
     * window. The result was 8231 requests submitted, 0 processed, service up.
     *
     * Requiring a real sample set means one outlier is diluted by the calls around
     * it instead of defining them.
     */
    private static final int MIN_SAMPLES = 8;

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

    /**
     * Above this median, a call is not worth paying for.
     *
     * The old default of 8 ms was below the measured steady cost of the shape the game
     * actually uses. Field log 2026-10-05: the light path submits m=128 k=512 n=512 and
     * the service reports npu_service_us of 5490 / 6037 / 8028 / 9949 / 10005 / 10111 for
     * it - a median near 9 ms - while the budget was 8 ms. The guard therefore tripped on
     * a median of 8624us against an 8000us budget, i.e. it condemned a path for costing
     * what it costs. A tick is 50 ms, so 20 ms still leaves the caller well inside a
     * frame, and it is above the real cost instead of barely under it.
     *
     * Configurable because the right number depends on which shapes a world uses.
     */
    private static volatile long budgetUs = 20_000L;   // 20 ms
    private static volatile int failBudget = 3;

    /**
     * True while we are deliberately measuring or rebuilding rather than producing.
     *
     * Warm-up, the bench sweep and the post-flush re-warm all submit on purpose. Their
     * timings are dominated by graph construction and by CPU reference work that production
     * never pays, so they say nothing about steady-state cost - and worse, they can trip
     * the guard, which then blocks the very re-warm that would restore the hot graph.
     *
     * Field log 2026-10-05: warm-up and boot put 13 samples into the window with a median
     * of 8624us and a p99 of 99554us, the guard degraded, and `rewarm: FAILED
     * GUARD_DEGRADED` followed. The flush had just dropped the production graph, so every
     * later probe paid a cold rebuild of ~99 ms, which is far over budget, which kept the
     * guard degraded. 26,000+ chunks then went to vanilla and the NPU was never used.
     *
     * Marking calibration explicitly is stronger than skipping the first N calls: the
     * caller knows what it is doing, and calibration calls must never be refused.
     */
    private static volatile boolean calibrating;

    private static volatile boolean degraded;
    private static volatile String reason = "";
    private static volatile long degradedAtMs;
    private static volatile long lastProbeMs;
    private static final long PROBE_INTERVAL_MS = 15_000L;
    /** Absolute backstop: never remain degraded longer than this without retrying. */
    private static final long FORCED_RETRY_MS = 60_000L;

    public static final AtomicLong totalCalls = new AtomicLong();
    public static final AtomicLong rejectedCalls = new AtomicLong();
    public static final AtomicLong degradedEvents = new AtomicLong();

    private NpuGuard() {}

    /** True when callers should skip the NPU for now. */
    public static boolean isDegraded() { return degraded; }

    public static String reason() { return reason; }

    /** Ask whether an NPU call is currently advisable. Cheap: no IPC, no lock contention. */
    /** Mark a stretch of deliberate measurement or rebuilding. Always paired with a reset. */
    public static void setCalibrating(boolean on) {
        calibrating = on;
        if (on) {
            // Start the health window empty so the first production samples after
            // calibration are judged on their own, not alongside cold builds.
            synchronized (LOCK) { filled = 0; cursor = 0; }
        }
    }

    public static boolean isCalibrating() { return calibrating; }

    public static boolean allow() {
        if (!NpuConfig.get().guardEnabled) return true;
        // Calibration submits on purpose and must never be refused. Refusing them is what
        // left the production graph unrebuilt after a flush, and an unrebuilt graph keeps
        // every later probe cold, which keeps the guard tripped.
        if (calibrating) return true;
        if (!degraded) return true;
        // Let one probe through now and then, so a recovered service is noticed.
        long now = System.currentTimeMillis();
        if (now - lastProbeMs >= PROBE_INTERVAL_MS) {
            lastProbeMs = now;
            return true;
        }
        // Last resort: never stay degraded forever. If the window has been unable
        // to refill for a long time - because most calls are being rejected, so
        // few samples arrive - clear it and take another look. Without this a trip
        // caused by stale samples can never be undone.
        if (now - degradedAtMs > FORCED_RETRY_MS) {
            degradedAtMs = now;
            lastProbeMs = now;
            synchronized (LOCK) { filled = 0; cursor = 0; }
            return true;
        }
        rejectedCalls.incrementAndGet();
        return false;
    }

    /** Record the wall time of a completed submit(), in microseconds. */
    public static void recordUs(long micros) {
        totalCalls.incrementAndGet();
        // Deliberately does NOT clear the per-signature failure streaks.
        //
        // A success here proves some call worked. It does not prove that the shape which has
        // failed three times now works, and clearing on any success is exactly what let 47
        // consecutive BIN_SUBMIT_FAILED submits go unnoticed while other shapes succeeded in
        // between. Streaks are cleared by recovery instead, which is the point at which we
        // have actually re-established that the path works.
        if (!NpuConfig.get().guardEnabled) return;
        // Calibration timings are not evidence about production cost. Letting them in is
        // what degraded the guard at boot on 2026-10-05 and disabled the NPU for the
        // whole session.
        if (calibrating) return;

        // A call that came back inside budget is evidence the path works; take that as a reason to
        // recover rather than staying degraded until an arbitrary timeout. Without this, a single
        // slow cold call kept the guard degraded for the whole session.
        if (micros <= budgetUs && degraded) {
            // A single in-budget call is not proof the path recovered - it may be
            // the one fast call in a slow stretch. Re-arm the window and let the
            // median decide on the next few samples rather than trusting this one.
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

        // Not enough history yet to call this a trend. A single cold sample is not
        // a percentile, and tripping on it disables the feature for the whole session.
        if (filled < MIN_SAMPLES) return;

        // Trip on the median, not the tail.
        //
        // p99 over a 64-sample window is mathematically the maximum:
        // ceil(0.99 * 64) - 1 = 63, the last sorted element. So "p99 over budget"
        // meant "one single slow call occurred", not "the path got slower". The log
        // shows exactly that shape - a median around 1.2 ms with occasional 79 ms
        // and 190 ms runs - and the guard tripped on those outliers every time.
        //
        // The median is immune to them. One slow call is a hiccup; a slow median
        // means the path really did regress, which is what deserves backing off.
        long p50 = percentileLocked(0.50);

        if (p50 > budgetUs && !degraded) {
            degrade(String.format(Locale.ROOT,
                    "median %dus over budget %dus (n=%d, p99=%dus)", p50, budgetUs, filled, p99));
        } else if (degraded && p50 <= budgetUs / 2) {
            // Hysteresis: recover only at half the trip level, otherwise it flaps.
            recover();
        }
    }

    /**
     * Record a failed call.
     *
     * One failure proves very little: a caller asking for a shape that cannot be expressed,
     * or passing a short buffer, is a bug in that caller and must not disable the NPU for
     * everything else. So a single failure of any kind is just a log line.
     *
     * What matters is repetition. Streaks are counted per error signature and are cleared
     * only when the guard recovers, so a fault that keeps coming back is eventually treated
     * as the path being broken whatever its name says.
     */
    private static final java.util.concurrent.atomic.AtomicLong consecutiveFailsDegraded =
            new java.util.concurrent.atomic.AtomicLong();
    /** Failures in a row before the path is treated as broken whatever the error says. */
    private static final int FAIL_RUN_LIMIT = 3;

    /**
     * Consecutive failures, tracked per error signature rather than globally.
     *
     * A single global streak was reset by any success anywhere, which is why 47 straight
     * BIN_SUBMIT_FAILED submits never tripped anything: unrelated calls on the light path
     * kept zeroing the counter between them. A streak keyed by the error text cannot be
     * diluted by successes on other shapes or other paths - if the same error keeps coming
     * back, that path is broken and nothing else succeeding proves otherwise.
     *
     * Streaks are cleared only by recovery (a successful probe), not by unrelated success.
     */
    private static final Map<String, java.util.concurrent.atomic.AtomicInteger> FAIL_RUNS =
            new ConcurrentHashMap<>();
    /** Bound the map so a pathological variety of error texts cannot grow it forever. */
    private static final int MAX_TRACKED_SIGNATURES = 64;

    /**
     * Collapse an error to a signature: the leading token with digits stripped.
     *
     * "ERR BIN_SUBMIT_FAILED rc=14001" and "ERR BIN_SUBMIT_FAILED rc=1002" are the same
     * failure for this purpose - the point is that it keeps happening, not which code it
     * reported. Keeping the codes would split one repeating fault into many single ones.
     */
    private static String signature(String e) {
        String head = e.trim();
        int sp = head.indexOf(' ');
        if (sp > 0 && head.startsWith("ERR ")) {
            head = head.substring(4);
            sp = head.indexOf(' ');
        }
        if (sp > 0) head = head.substring(0, sp);
        return head.replaceAll("\\d+", "#");
    }

    public static void recordFailure(String why) {
        NpuDiagnostics.fail("guard." + (why == null ? "unknown"
                : (why.indexOf(' ') > 0 ? why.substring(0, why.indexOf(' ')) : why)));
        totalCalls.incrementAndGet();
        if (!NpuConfig.get().guardEnabled) return;
        if (degraded) return;

        String e = why == null ? "" : why;
        boolean transport = e.startsWith("SERVICE_")
                || e.startsWith("MCNPU_OFFLINE")
                || e.contains("SocketTimeout")
                || e.contains("ConnectException")
                || e.contains("closed");

        // Repetition matters more than the error text.
        //
        // A native submit failure like BIN_SUBMIT_FAILED is not a transport fault, so it
        // used to be logged and ignored. But "ignored" meant "retried", and the device log
        // shows it firing dozens of times a second on the light path, each attempt costing
        // hundreds of milliseconds on the game thread - the tick that measured 428 ms, long
        // enough to feel like a hang. Classifying the error did not help; the caller kept
        // paying for a call that cannot succeed.
        //
        // So a single failure of any kind is still just a log line, but a run of them is
        // treated as the path being broken regardless of the reason. Three in a row of the
        // same signature is well past coincidence, and backing off then costs nothing that
        // was going to work.
        String sig = signature(e);
        if (FAIL_RUNS.size() >= MAX_TRACKED_SIGNATURES && !FAIL_RUNS.containsKey(sig)) {
            FAIL_RUNS.clear();
        }
        int run = FAIL_RUNS.computeIfAbsent(sig,
                k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();

        if (!transport) {
            if (run >= FAIL_RUN_LIMIT) {
                consecutiveFailsDegraded.incrementAndGet();
                lastProbeMs = System.currentTimeMillis();
                degrade("repeated " + sig + " x" + run + " (last: " + e + ")");
                return;
            }
            if (NpuConfig.get().debugLog) NpuLog.warn("guard: non-transport failure noted: " + e
                    + " (run " + sig + "=" + run + "/" + FAIL_RUN_LIMIT + ")");
            return;
        }

        lastProbeMs = System.currentTimeMillis();
        degrade("transport failure: " + e);
    }

    /** Worst current failure streak, for diagnostics: "BIN_SUBMIT_FAILED=5". */
    public static String worstFailureRun() {
        String worstSig = null;
        int worst = 0;
        for (Map.Entry<String, java.util.concurrent.atomic.AtomicInteger> en : FAIL_RUNS.entrySet()) {
            int v = en.getValue().get();
            if (v > worst) { worst = v; worstSig = en.getKey(); }
        }
        return worst == 0 ? "-" : worstSig + "=" + worst;
    }

    private static void degrade(String why) {
        degraded = true;
        reason = why;
        degradedAtMs = System.currentTimeMillis();
        degradedEvents.incrementAndGet();
        NpuDiagnostics.count("guard.degraded");
        NpuLog.warn("guard DEGRADED: " + why + " - features fall back to CPU until it recovers");
    }

    private static void recover() {
        degraded = false;
        reason = "";
        // A clean probe is real evidence the path works again, so the accumulated failure
        // streaks no longer describe the current state and must not trip again immediately.
        FAIL_RUNS.clear();
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
        FAIL_RUNS.clear();
    }

    public static String summary() {
        return String.format(Locale.ROOT,
                "guard %s p50=%dus p99=%dus budget=%dus calls=%d rejected=%d trips=%d runs=%s %s",
                degraded ? "DEGRADED" : "ok", p50Us(), p99Us(), budgetUs(),
                totalCalls.get(), rejectedCalls.get(), degradedEvents.get(),
                worstFailureRun(), reason);
    }
}
