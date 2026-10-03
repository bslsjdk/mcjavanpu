package bslsjdk.mcjavanpu;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Answers one question: is the batching real?
 *
 * It is easy to build a scheduler that puts N chunks into a list and calls that a
 * batch, while the transport still makes one round trip per chunk. Nothing about
 * the frame time improves, and the summary line still looks healthy because the
 * counters count the list, not the wire.
 *
 * So this class keeps the two numbers separate:
 *
 *   logicalChunks    - how many chunks the scheduler claims to have handled
 *   actualSubmits    - how many times we actually opened a request to the NPU
 *
 * When batching works, actualSubmits is well below logicalChunks. When it does not,
 * they are equal, and the ratio says so plainly. A terrain path that never reaches
 * the transport at all shows actualSubmits = 0 while logicalChunks climbs, which is
 * the clearest possible signal that the work is being done somewhere else.
 *
 * Cold and steady are also separated, because a first call carries graph creation
 * and finalisation that no later call pays. Averaging them produces a number that
 * describes neither.
 */
public final class NpuBatchMetrics {

    private static final AtomicLong LOGICAL_CHUNKS = new AtomicLong();
    private static final AtomicLong ACTUAL_SUBMITS = new AtomicLong();

    private static final AtomicLong COLD_SUBMITS = new AtomicLong();
    private static final AtomicLong STEADY_SUBMITS = new AtomicLong();
    private static final AtomicLong COLD_US = new AtomicLong();
    private static final AtomicLong STEADY_US = new AtomicLong();
    private static final AtomicLong STEADY_MAX_US = new AtomicLong();

    /** Buckets that have been exercised at least once, so cold/steady is per shape. */
    private static final java.util.Set<String> WARM_BUCKETS =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private NpuBatchMetrics() {}

    /** Scheduler handled this many chunks. Does not imply any NPU traffic. */
    public static void recordLogicalChunks(long n) {
        if (n > 0) LOGICAL_CHUNKS.addAndGet(n);
    }

    /**
     * One real request reached the transport.
     *
     * bucket identifies the shape; the first time a bucket is seen is cold, every
     * later call on the same bucket is steady. That matches where the cost actually
     * is: graph creation happens once per shape, not once per process.
     */
    public static void recordActualSubmit(String bucket, long us) {
        ACTUAL_SUBMITS.incrementAndGet();
        if (bucket != null && WARM_BUCKETS.add(bucket)) {
            COLD_SUBMITS.incrementAndGet();
            COLD_US.addAndGet(Math.max(0L, us));
            return;
        }
        STEADY_SUBMITS.incrementAndGet();
        STEADY_US.addAndGet(Math.max(0L, us));
        long m = STEADY_MAX_US.get();
        if (us > m) STEADY_MAX_US.set(us);
    }

    public static long logicalChunks() { return LOGICAL_CHUNKS.get(); }
    public static long actualSubmits() { return ACTUAL_SUBMITS.get(); }

    /**
     * Chunks per NPU round trip. Above 1 the transport is genuinely batched;
     * equal to 1 it is one trip per chunk; 0 submits means the NPU is not in this
     * path at all.
     */
    public static double chunksPerSubmit() {
        long s = ACTUAL_SUBMITS.get();
        if (s == 0) return 0.0;
        return LOGICAL_CHUNKS.get() / (double) s;
    }

    public static String summary() {
        long s = STEADY_SUBMITS.get();
        long avgSteady = s == 0 ? 0 : STEADY_US.get() / s;
        return "batch_metrics logical_chunks=" + LOGICAL_CHUNKS.get()
                + " actual_npu_submits=" + ACTUAL_SUBMITS.get()
                + " chunks_per_submit=" + String.format(java.util.Locale.ROOT, "%.2f", chunksPerSubmit())
                + " cold=" + COLD_SUBMITS.get() + "/" + COLD_US.get() + "us"
                + " steady=" + s + " avg=" + avgSteady + "us max=" + STEADY_MAX_US.get() + "us";
    }

    public static void reset() {
        LOGICAL_CHUNKS.set(0);
        ACTUAL_SUBMITS.set(0);
        COLD_SUBMITS.set(0);
        STEADY_SUBMITS.set(0);
        COLD_US.set(0);
        STEADY_US.set(0);
        STEADY_MAX_US.set(0);
        WARM_BUCKETS.clear();
    }
}
