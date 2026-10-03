package bslsjdk.mcjavanpu;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatic entry point for chunk-triggered NPU work.
 *
 * The hard lesson here is that world load is a flood, not a trickle: loading a world can push
 * hundreds of chunks through CHUNK_LOAD in a few seconds (558 observed in one server tick
 * window). A queue with no ceiling and a batch with no ceiling is therefore not a scheduler, it
 * is an outage: the work piles up faster than it drains and the first oversized submission
 * wedges the single-threaded service behind it.
 *
 * So three limits exist, and all three matter:
 *
 *   PENDING_CAP        - the queue can never grow without bound; past this we drop, not block
 *   CHUNKS_PER_SUBMIT  - one submission never exceeds a size the service can finish quickly
 *   BATCH_TICKS        - a partial batch is flushed on a timer, so nothing waits forever
 *
 * Dropping excess work is deliberate. This path accelerates light; it must never be able to
 * stall chunk loading, and vanilla light is still there as the fallback.
 */
public final class NpuChunkAuto {

    /** Smallest set worth a submission: below this the per-call cost dominates. */
    private static final int BATCH_MIN = 4;
    /** Upper bound on one submission. Kept small so the service always answers inside the IPC timeout. */
    private static final int CHUNKS_PER_SUBMIT = 8;
    /** Hard ceiling on the pending queue. Loading a world must not be able to grow this forever. */
    private static final int PENDING_CAP = 512;
    /** Flush a partial batch after this many ticks so stragglers do not sit in the queue. */
    private static final int BATCH_TICKS = 40;

    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicLong TOTAL_CHUNKS = new AtomicLong();
    private static final AtomicLong TOTAL_BATCHES = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static volatile int ticksSinceFlush;
    private static volatile int lastBatchSize;

    private NpuChunkAuto() {}

    /** Called from the chunk-load event, on the server thread. Must be cheap and must not block. */
    public static void onChunkLoad(ServerLevel world, LevelChunk chunk) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        if ("vanilla".equalsIgnoreCase(cfg.lightMode) && "vanilla".equalsIgnoreCase(cfg.chunkMode)) return;
        TOTAL_CHUNKS.incrementAndGet();
        NpuChunkWork.setLevel(world);
        try {
            if (PENDING.size() >= PENDING_CAP) {
                // Queue is saturated. Shed load instead of blocking the world load.
                DROPPED.incrementAndGet();
                return;
            }
            if (!NpuServiceClient.isAvailable()) {
                // Service busy or down: do not queue work we cannot deliver.
                SKIPPED.incrementAndGet();
                return;
            }
            int x = chunk.getPos().x();
            int z = chunk.getPos().z();
            NpuBatchManager.record(x, z);
            PENDING.add((((long) x) << 32) ^ (z & 0xFFFFFFFFL));
        } catch (Throwable t) {
            NpuLog.error("chunk-load hook could not read the chunk pos", t);
        }
    }

    /** Called every server tick. Drains at most one submission's worth per call. */
    public static void onServerTick(Object server) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        ticksSinceFlush++;
        int size = PENDING.size();
        if (size > 0) {
            boolean ready = size >= BATCH_MIN || ticksSinceFlush >= BATCH_TICKS;
            if (ready) flush(size);
        }
        // Always drain at a fixed, small rate. This is the only place NPU chunk work runs,
        // and it runs on the server thread, so the rate limit is what keeps loading smooth.
        NpuWorkQueue.pump();
    }

    /**
     * Takes one bounded slice out of the queue and hands it over. Whatever does not fit stays
     * queued for the next tick, so a flood of 500 chunks drains over many ticks at a size the
     * service can actually keep up with.
     */
    private static void flush(int size) {
        int take = Math.min(size, CHUNKS_PER_SUBMIT);
        java.util.List<long[]> slice = new java.util.ArrayList<>(take);
        int moved = 0;
        var it = PENDING.iterator();
        while (it.hasNext() && moved < take) {
            long packed = it.next();
            it.remove();
            slice.add(new long[]{packed >> 32, (int) packed});
            moved++;
        }
        ticksSinceFlush = 0;
        lastBatchSize = moved;
        if (moved == 0) return;
        TOTAL_BATCHES.incrementAndGet();
        NpuWorkQueue.submit(slice);
        if (TOTAL_BATCHES.get() % 8 == 1) {
            NpuLog.log("chunk batch: submitted=" + moved + " pending=" + PENDING.size()
                    + " (total " + TOTAL_CHUNKS.get() + " loaded, batch #" + TOTAL_BATCHES.get()
                    + ", dropped=" + DROPPED.get() + " skipped=" + SKIPPED.get() + ")");
        }
    }

    public static String summary() {
        return "chunks_seen=" + TOTAL_CHUNKS.get() + " batches=" + TOTAL_BATCHES.get()
                + " pending=" + PENDING.size() + "/" + PENDING_CAP
                + " last_batch=" + lastBatchSize
                + " dropped=" + DROPPED.get() + " skipped=" + SKIPPED.get()
                + " | " + NpuWorkQueue.summary();
    }

    /** Clears the queue; used by /npu batch reset. */
    public static void reset() {
        PENDING.clear();
        NpuWorkQueue.clear();
        ticksSinceFlush = 0;
    }
}
