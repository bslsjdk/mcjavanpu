package bslsjdk.mcjavanpu;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The drain side of chunk loading.
 *
 * NpuChunkAuto collects chunk coordinates in bounded batches; this class is what actually does
 * the work, and it exists because doing the work inline is not an option:
 *
 *   - the collecting side runs on the server thread, where anything slow is a stall
 *   - the service is single threaded, so one big ask starves everything behind it
 *
 * So work is queued and drained at a fixed, small rate per tick, and every item is bounded. The
 * queue is capped: when it is full we drop rather than grow, because accelerating light is a
 * bonus and stalling a world load is not acceptable.
 *
 * Both legs of chunk loading are represented here:
 *   density - covered by the DensitySampler hook (chunkMode); this queue does not touch it
 *   light   - handled here, one small batch per tick, using the arrays the light engine uses
 */
public final class NpuWorkQueue {

    /** Items drained per tick. Small on purpose: the service must stay responsive. */
    private static final int DRAIN_PER_TICK = 2;
    /** Hard cap on queued work. Full means drop. */
    private static final int QUEUE_CAP = 512;

    /**
     * Wall-clock budget for one pump, in microseconds.
     *
     * pump() runs on the server thread and each item performs a synchronous IPC
     * round trip, whose read timeout is far larger than a tick. Health is cached
     * so the common case is fast, but a service that is up and merely slow would
     * otherwise stall world loading for seconds at a time. Once the budget is
     * spent the remaining items stay queued for the next tick.
     */
    private static volatile long budgetUs = 4_000L;
    private static final AtomicLong OVERRUNS = new AtomicLong();

    private static final ArrayDeque<long[]> QUEUE = new ArrayDeque<>();
    private static final AtomicLong SUBMITTED = new AtomicLong();
    private static final AtomicLong PROCESSED = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static volatile long lastRunUs;
    private static volatile String lastError = "";

    private NpuWorkQueue() {}

    /** Called from the scheduler when a batch is handed over. Cheap and non-blocking. */
    public static synchronized void submit(List<long[]> items) {
        for (long[] it : items) {
            if (QUEUE.size() >= QUEUE_CAP) {
                DROPPED.incrementAndGet();
                NpuDiagnostics.count("chunk.dropped_queue_full");
                continue;
            }
            QUEUE.addLast(it);
            NpuDiagnostics.count("chunk.submitted");
            SUBMITTED.incrementAndGet();
        }
    }

    public static synchronized int pending() { return QUEUE.size(); }

    public static synchronized void clear() {
        QUEUE.clear();
    }

    /**
     * Drains a small number of items. Called once per server tick from NpuChunkAuto.
     *
     * Each item is one chunk column that just appeared in the world. The light pass that follows
     * a chunk load is what we can move onto the NPU; terrain density is already taken care of
     * further up by the density sampler, so it is not repeated here.
     */
    public static void pump() {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        if (!NpuStats.CHUNK.enabled) return;

        List<long[]> batch = null;
        synchronized (NpuWorkQueue.class) {
            if (QUEUE.isEmpty()) return;
            batch = new ArrayList<>(DRAIN_PER_TICK);
            for (int i = 0; i < DRAIN_PER_TICK && !QUEUE.isEmpty(); i++) batch.add(QUEUE.pollFirst());
        }

        long t0 = System.nanoTime();
        int ok = 0;
        int i = 0;
        for (long[] it : batch) {
            // Budget check, with two hard-won exceptions.
            //
            // The original version pushed the item back with addFirst and broke.
            // If the first item alone consumed the budget - and log evidence shows
            // single runs of 79ms and 190ms against a 4ms budget - that item went
            // straight back to the head, was picked first next tick, overran again,
            // and everything behind it never ran. Log evidence: submitted=8231,
            // processed=0, overruns climbing every tick. Head-of-line starvation.
            //
            // So: always run at least one item per tick, otherwise a slow item
            // blocks the queue forever. And requeue at the tail, so a slow item
            // cannot monopolise the head - the others get their turn.
            boolean first = (i++ == 0);
            if (!first && (System.nanoTime() - t0) / 1000L >= budgetUs) {
                synchronized (NpuWorkQueue.class) { QUEUE.addLast(it); }
                OVERRUNS.incrementAndGet();
                continue;
            }
            try {
                // One entry point for doing NPU work for a just-loaded chunk. Kept behind a
                // single method so the light side can grow without touching the scheduler.
                if (NpuChunkWork.runForChunk((int) it[0], (int) it[1])) ok++;
            } catch (Throwable t) {
                FAILED.incrementAndGet();
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
        lastRunUs = (System.nanoTime() - t0) / 1000;
        PROCESSED.addAndGet(ok);
        NpuDiagnostics.count("chunk.processed", ok);
        if (ok > 0) NpuStats.CHUNK.record(ok, lastRunUs, lastRunUs);
    }

    public static String summary() {
        return "work submitted=" + SUBMITTED.get() + " processed=" + PROCESSED.get()
                + " pending=" + pending() + "/" + QUEUE_CAP + " dropped=" + DROPPED.get()
                + " failed=" + FAILED.get() + " overruns=" + OVERRUNS.get()
                + " last_us=" + lastRunUs + " budget_us=" + budgetUs
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }
    /** Wall-clock budget for one pump, in microseconds. */
    public static void setBudgetUs(long v) { budgetUs = Math.max(500L, v); }
    public static long budgetUs() { return budgetUs; }
}
