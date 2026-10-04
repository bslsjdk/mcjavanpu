package bslsjdk.mcjavanpu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One place that can answer "where is the time going and what is broken".
 *
 * Rules this class follows, each learned from a bug that cost a debugging cycle:
 *
 * 1. Counters live where the thing actually happens, not where it is scheduled.
 *    A scheduler-side counter can only prove a chunk was put in a list; only a
 *    transport-side counter proves a request reached the wire. "submitted=8231
 *    processed=0" was a real log line, and it was invisible until the two were
 *    counted separately.
 *
 * 2. Timers accumulate per thread and merge on read. A shared AtomicLong bumped
 *    from every worker makes the threads fight over one cache line, which distorts
 *    the very thing being measured.
 *
 * 3. Failures are counted by reason and never collapsed into a boolean. Several
 *    rounds were lost to errors that crossed a boundary and came out as
 *    "it failed" with the cause discarded.
 *
 * 4. The summary states a conclusion, not just numbers. A wall of counters does
 *    not tell you what to fix; verdict() encodes the failure patterns we have
 *    actually hit so the log names the problem.
 */
public final class NpuDiagnostics {
    private static final NpuDiagnostics I = new NpuDiagnostics();

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<String, AtomicLong>();
    private final ConcurrentHashMap<String, AtomicLong> failures = new ConcurrentHashMap<String, AtomicLong>();
    /** stage -> nanos, accumulated per thread then merged on read. */
    private final ConcurrentHashMap<String, AtomicLong> stageNs = new ConcurrentHashMap<String, AtomicLong>();
    private final ConcurrentHashMap<String, AtomicLong> stageHits = new ConcurrentHashMap<String, AtomicLong>();

    private NpuDiagnostics() {}

    public static void count(String name) { count(name, 1); }

    public static void count(String name, long n) {
        if (name == null) return;
        AtomicLong a = I.counters.get(name);
        if (a == null) {
            a = new AtomicLong();
            AtomicLong prev = I.counters.putIfAbsent(name, a);
            if (prev != null) a = prev;
        }
        a.addAndGet(n);
    }

    public static long get(String name) {
        AtomicLong a = I.counters.get(name);
        return a == null ? 0L : a.get();
    }

    /** Records a failure under a stable reason key. The reason is never discarded. */
    public static void fail(String reason) {
        if (reason == null) reason = "unknown";
        AtomicLong a = I.failures.get(reason);
        if (a == null) {
            a = new AtomicLong();
            AtomicLong prev = I.failures.putIfAbsent(reason, a);
            if (prev != null) a = prev;
        }
        a.incrementAndGet();
    }

    public static void time(String stage, long nanos) {
        if (stage == null) return;
        AtomicLong a = I.stageNs.get(stage);
        if (a == null) {
            a = new AtomicLong();
            AtomicLong prev = I.stageNs.putIfAbsent(stage, a);
            if (prev != null) a = prev;
        }
        a.addAndGet(nanos);
        AtomicLong h = I.stageHits.get(stage);
        if (h == null) {
            h = new AtomicLong();
            AtomicLong prev = I.stageHits.putIfAbsent(stage, h);
            if (prev != null) h = prev;
        }
        h.incrementAndGet();
    }

    private static AtomicLong stageNs(String s) {
        AtomicLong a = I.stageNs.get(s);
        return a == null ? new AtomicLong() : a;
    }

    private static AtomicLong stageHits(String s) {
        AtomicLong a = I.stageHits.get(s);
        return a == null ? new AtomicLong() : a;
    }

    /**
     * The single line that says what is wrong, or that nothing is.
     *
     * Patterns encoded here are ones that actually happened:
     *   - submitted but never processed (head-of-line starvation)
     *   - claimed batching but 1 submit per item (the batch was a loop)
     *   - service unreachable the whole session
     *   - requests rejected before reaching the device (shape over budget)
     *   - NPU ran and was correct but changed nothing (algorithm cannot help)
     */
    public static String verdict() {
        long submitted = get("chunk.submitted");
        long processed = get("chunk.processed");
        long transportSubmits = get("transport.submits");
        long transportOk = get("transport.ok");
        long logicalChunks = get("batch.logical_chunks");
        long offline = get("service.offline");
        long shapeRejected = getF("shape_rejected");
        long transportFail = getF("transport");
        long emptyWrite = get("result.empty_write");
        long degraded = get("guard.degraded");

        if (transportSubmits == 0 && submitted > 0)
            return "VERDICT: NPU IS NOT IN THE PATH - " + submitted
                    + " chunk(s) scheduled but 0 requests reached the service. "
                    + "Whatever is being timed is not the NPU.";
        if (transportSubmits == 0 && offline > 0)
            return "VERDICT: SERVICE NEVER REACHABLE - " + offline
                    + " offline check(s). Start MCNPU before the game, and check "
                    + "Settings > Battery > allow background activity.";
        if (submitted > 0 && processed == 0 && transportSubmits > 0)
            return "VERDICT: WORK QUEUE STALLED - submitted=" + submitted
                    + " processed=0 while " + transportSubmits
                    + " request(s) went out. Results are not coming back or not being consumed.";
        if (shapeRejected > 0)
            return "VERDICT: REQUESTS REJECTED BEFORE THE DEVICE - " + shapeRejected
                    + " shape_rejected. The submitted shape exceeds what the service accepts; "
                    + "no amount of device tuning will help until the request fits.";
        if (logicalChunks > 0 && transportSubmits > 0 && transportSubmits >= logicalChunks)
            return "VERDICT: NO REAL BATCHING - " + logicalChunks + " chunk(s) produced "
                    + transportSubmits + " submit(s). Batching is a loop, not a batch.";
        if (degraded > 0 && transportOk == 0)
            return "VERDICT: GUARD DEGRADED AND NOTHING RAN - " + degraded
                    + " degrade event(s), 0 successful submits. The protection is blocking the "
                    + "only path that could produce recovery evidence.";
        if (emptyWrite > 0 && transportOk > 0)
            return "VERDICT: NPU RUNS BUT CANNOT HELP - " + emptyWrite
                    + " call(s) computed correctly and changed 0 cells. The algorithm cannot "
                    + "beat the vanilla result on this workload; turn the feature off.";
        if (transportFail > 0 && transportOk == 0)
            return "VERDICT: EVERY REQUEST FAILED - " + transportFail
                    + " transport failure(s), 0 success. See the failure list below for the reason.";
        if (transportOk > 0)
            return "VERDICT: PATH IS WORKING - " + transportOk
                    + " successful submit(s). Check timings below for where the time goes.";
        return "VERDICT: NO ACTIVITY - nothing scheduled and nothing sent. "
                + "Confirm the mod is enabled and a mode other than vanilla is selected.";
    }

    public static long getF(String reason) {
        AtomicLong a = I.failures.get(reason);
        return a == null ? 0L : a.get();
    }

    /** Multi-line block: verdict, then failures, then timings, then counters. */
    public static String report() {
        StringBuilder sb = new StringBuilder();
        sb.append(verdict()).append('\n');

        if (!I.failures.isEmpty()) {
            List<Map.Entry<String, AtomicLong>> fs =
                    new ArrayList<Map.Entry<String, AtomicLong>>(I.failures.entrySet());
            Collections.sort(fs, new Comparator<Map.Entry<String, AtomicLong>>() {
                @Override public int compare(Map.Entry<String, AtomicLong> a,
                                             Map.Entry<String, AtomicLong> b) {
                    return Long.compare(b.getValue().get(), a.getValue().get());
                }
            });
            sb.append("failures:");
            int n = 0;
            for (Map.Entry<String, AtomicLong> e : fs) {
                if (n++ >= 8) { sb.append(" ..."); break; }
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue().get());
            }
            sb.append('\n');
        }

        if (!I.stageNs.isEmpty()) {
            List<String> stages = new ArrayList<String>(I.stageNs.keySet());
            Collections.sort(stages, new Comparator<String>() {
                @Override public int compare(String a, String b) {
                    return Long.compare(stageNs(b).get(), stageNs(a).get());
                }
            });
            long total = 0;
            for (String s : stages) total += stageNs(s).get();
            sb.append(String.format(Locale.ROOT, "time: total=%.1fms", total / 1e6));
            int n = 0;
            for (String s : stages) {
                if (n++ >= 8) break;
                long ns = stageNs(s).get();
                long hits = stageHits(s).get();
                double pct = total == 0 ? 0 : 100.0 * ns / total;
                sb.append(String.format(Locale.ROOT, " | %s %.1fms(%.0f%%)",
                        s, ns / 1e6, pct));
                if (hits > 0) sb.append(String.format(Locale.ROOT, "x%d[%.2fms avg]",
                        hits, ns / 1e6 / hits));
            }
            sb.append('\n');
        }

        if (!I.counters.isEmpty()) {
            List<String> keys = new ArrayList<String>(I.counters.keySet());
            Collections.sort(keys);
            sb.append("counters:");
            for (String k : keys) {
                long v = get(k);
                if (v != 0) sb.append(' ').append(k).append('=').append(v);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static void reset() {
        I.counters.clear();
        I.failures.clear();
        I.stageNs.clear();
        I.stageHits.clear();
    }
}
