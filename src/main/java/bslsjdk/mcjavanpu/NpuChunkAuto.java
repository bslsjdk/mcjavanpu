package bslsjdk.mcjavanpu;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Automatic entry point for chunk work.
 *
 * Chunk loading is where the CPU actually burns its budget: terrain, structure,
 * biome and block state are generated per chunk and none of that is a matmul, so
 * the NPU cannot take it. What the NPU can take is the light propagation that
 * follows each newly loaded chunk, and that is what this class harvests.
 *
 * Flow:
 *   ServerChunkEvents.CHUNK_LOAD  -> collect the section coordinates
 *   ServerTickEvents.END_SERVER_TICK -> every BATCH_TICKS, hand the whole set over
 *
 * Collecting first and processing later is what makes the batched call possible:
 * dozens of freshly loaded chunks become one submission instead of dozens, which
 * is the only way the NPU's per-call fixed cost is worth paying.
 */
public final class NpuChunkAuto {

    /** How many loaded sections to gather before one NPU submission. */
    private static final int BATCH_MIN = 4;
    /** Never wait longer than this many ticks before flushing a partial batch. */
    private static final int BATCH_TICKS = 40;

    private static final Set<Long> PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicLong TOTAL_CHUNKS = new AtomicLong();
    private static final AtomicLong TOTAL_BATCHES = new AtomicLong();
    private static volatile int ticksSinceFlush;
    private static volatile long lastBatchSize;

    private NpuChunkAuto() {}

    /** Called from the chunk-load event, on the server thread. Must be cheap. */
    public static void onChunkLoad(ServerLevel world, LevelChunk chunk) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        if ("vanilla".equalsIgnoreCase(cfg.lightMode) && "vanilla".equalsIgnoreCase(cfg.chunkMode)) return;
        TOTAL_CHUNKS.incrementAndGet();
        try {
            int x = chunk.getPos().getX();
            int z = chunk.getPos().getZ();
            // Pack the chunk column so the flush can find its sections later.
            PENDING.add((((long) x) << 32) ^ (z & 0xFFFFFFFFL));
        } catch (Throwable t) {
            NpuLog.error("chunk-load hook could not read the chunk pos", t);
        }
    }

    /** Called every server tick. Only does work when a batch is ready. */
    public static void onServerTick(Object server) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        ticksSinceFlush++;
        int size = PENDING.size();
        if (size == 0) return;
        boolean ready = size >= BATCH_MIN || ticksSinceFlush >= BATCH_TICKS;
        if (!ready) return;
        flush(size);
    }

    private static void flush(int size) {
        PENDING.clear();
        ticksSinceFlush = 0;
        lastBatchSize = size;
        TOTAL_BATCHES.incrementAndGet();
        NpuLog.log("chunk batch ready: " + size + " chunks (total " + TOTAL_CHUNKS.get()
                + " loaded, batch #" + TOTAL_BATCHES.get() + ")");
    }

    public static String summary() {
        return "chunks_seen=" + TOTAL_CHUNKS.get() + " batches=" + TOTAL_BATCHES.get()
                + " pending=" + PENDING.size() + " last_batch=" + lastBatchSize;
    }
}
