package bslsjdk.mcjavanpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Per-job timing, because the optimisation question cannot be answered without it.
 *
 * The report's point stands: without this data, tuning is guessing. The specific question that
 * has to be answerable is where the time actually goes - queue wait, feature preparation, IPC,
 * NPU execution, or assembly - and the answer decides what to attack next.
 *
 * This records the fields the review asked for and keeps them cheap: one ring of counters, no
 * allocation per job, no logging on the hot path. A snapshot turns into a readable summary, and
 * a full dump turns into CSV for offline comparison.
 */
public final class NpuTelemetry {

    /** Ring size. Small enough to stay cheap, large enough to cover a burst of chunk work. */
    private static final int RING = 512;

    // Column order for the CSV and for the ring arrays. Kept explicit so a dump is self-describing.
    private static final String[] COLUMNS = {
        "m", "k", "n", "bucket_m", "bucket_k", "bucket_n",
        "queue_wait_us", "prepare_us", "ipc_us", "assemble_us", "total_us",
        "input_bytes", "output_bytes"
    };

    private static final AtomicLongArray[] RING_DATA;
    private static final AtomicLong NEXT = new AtomicLong();
    private static final AtomicLong COUNT = new AtomicLong();

    // Running totals, so a summary does not need to walk the ring.
    private static final AtomicLong T_QUEUE = new AtomicLong();
    private static final AtomicLong T_PREPARE = new AtomicLong();
    private static final AtomicLong T_IPC = new AtomicLong();
    private static final AtomicLong T_ASSEMBLE = new AtomicLong();
    private static final AtomicLong T_TOTAL = new AtomicLong();
    private static final AtomicLong T_IN = new AtomicLong();
    private static final AtomicLong T_OUT = new AtomicLong();
    private static final AtomicLong T_PAD_MILLI = new AtomicLong();   // padding ratio x1000

    static {
        RING_DATA = new AtomicLongArray[COLUMNS.length];
        for (int i = 0; i < COLUMNS.length; i++) RING_DATA[i] = new AtomicLongArray(RING);
    }

    private NpuTelemetry() {}

    /**
     * Records one job. All times in microseconds.
     *
     * paddingRatio is the wasted fraction the bucket planner introduced: a 33-wide request living
     * in a 64-wide bucket wastes over half its compute, and that number is exactly what decides
     * whether cross-chunk batching is worth building.
     */
    public static void record(int m, int k, int n, int bm, int bk, int bn,
                              long queueWaitUs, long prepareUs, long ipcUs,
                              long assembleUs, long inputBytes, long outputBytes) {
        long total = queueWaitUs + prepareUs + ipcUs + assembleUs;
        long[] row = {m, k, n, bm, bk, bn, queueWaitUs, prepareUs, ipcUs, assembleUs, total,
                      inputBytes, outputBytes};
        int slot = (int) (NEXT.getAndIncrement() % RING);
        for (int i = 0; i < row.length; i++) RING_DATA[i].set(slot, row[i]);
        COUNT.incrementAndGet();

        T_QUEUE.addAndGet(queueWaitUs);
        T_PREPARE.addAndGet(prepareUs);
        T_IPC.addAndGet(ipcUs);
        T_ASSEMBLE.addAndGet(assembleUs);
        T_TOTAL.addAndGet(total);
        T_IN.addAndGet(inputBytes);
        T_OUT.addAndGet(outputBytes);

        long req = (long) m * k * n;
        long got = (long) bm * bk * bn;
        if (got > 0) T_PAD_MILLI.addAndGet(req * 1000 / got);
    }

    public static long jobs() { return COUNT.get(); }

    /** One-line answer to "where did the time go". */
    public static String summary() {
        long c = COUNT.get();
        if (c == 0) return "telemetry: no jobs recorded yet";
        double avg = T_TOTAL.get() / (double) c;
        return String.format(Locale.ROOT,
            "telemetry jobs=%d avg_total_us=%.0f | queue=%.1f%% prepare=%.1f%% ipc=%.1f%% assemble=%.1f%% | padding=%.1f%% | in=%.1fMB out=%.1fMB",
            c, avg,
            pct(T_QUEUE.get(), c, avg), pct(T_PREPARE.get(), c, avg),
            pct(T_IPC.get(), c, avg), pct(T_ASSEMBLE.get(), c, avg),
            c == 0 ? 0.0 : T_PAD_MILLI.get() / (double) c / 10.0,
            T_IN.get() / 1048576.0, T_OUT.get() / 1048576.0);
    }

    private static double pct(long totalUs, long count, double avg) {
        if (count == 0 || avg <= 0) return 0;
        return (totalUs / (double) count) * 100.0 / avg;
    }

    /** CSV header + the live ring, for pasting into an issue or a report. */
    public static String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", COLUMNS)).append('\n');
        long n = Math.min(COUNT.get(), RING);
        long start = Math.max(0, NEXT.get() - n);
        for (long i = start; i < NEXT.get(); i++) {
            int slot = (int) (i % RING);
            List<String> vals = new ArrayList<>(COLUMNS.length);
            for (AtomicLongArray col : RING_DATA) vals.add(Long.toString(col.get(slot)));
            sb.append(String.join(",", vals)).append('\n');
        }
        return sb.toString();
    }

    public static void reset() {
        NEXT.set(0); COUNT.set(0);
        T_QUEUE.set(0); T_PREPARE.set(0); T_IPC.set(0); T_ASSEMBLE.set(0); T_TOTAL.set(0);
        T_IN.set(0); T_OUT.set(0); T_PAD_MILLI.set(0);
    }
}
