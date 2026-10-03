package bslsjdk.mcjavanpu;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SuperChunk batching, as two layers.
 *
 * Layer 1 - Minecraft keeps seeing ordinary chunks. Nothing about the world model
 *           changes; there is no fake 9x9 chunk anywhere.
 * Layer 2 - the scheduler groups chunk requests onto an NxN lattice (default 9x9)
 *           and treats one lattice cell as a single unit of work.
 *
 * This class deliberately only measures. Before choosing m/k/n for a batched NPU
 * call we need to know, from the game itself: how many chunks land in a lattice
 * cell, how spread out they arrive, and whether the batch fills up. Guessing the
 * lattice size is exactly the mistake this exists to avoid.
 *
 * The math that a batch would hand to the NPU is ~768 noise samples per chunk
 * (cellWidth 4, cellHeight 8 over a full column), before interpolation. That number
 * is the thing to confirm against reality, not to assume.
 */
public final class NpuBatchManager {

    private static volatile int lattice = 9;

    /** lattice cell -> number of chunks that arrived inside it */
    private static final Map<Long, AtomicLong> CELL_FILL = new ConcurrentHashMap<>();
    private static final AtomicLong TOTAL = new AtomicLong();
    private static final AtomicLong FULL_CELLS = new AtomicLong();
    private static final AtomicLong MAX_FILL = new AtomicLong();
    private static final AtomicLong FLUSHES = new AtomicLong();

    private NpuBatchManager() {}

    public static int lattice() { return lattice; }

    public static void setLattice(int n) {
        lattice = Math.max(1, Math.min(32, n));
        reset();
        NpuLog.log("batch lattice set to " + lattice + "x" + lattice + " (" + (lattice * lattice) + " chunks per unit)");
    }

    /** Groups a chunk coordinate onto the lattice and counts it. */
    public static void record(int chunkX, int chunkZ) {
        int l = lattice;
        int sx = Math.floorDiv(chunkX, l);
        int sz = Math.floorDiv(chunkZ, l);
        long key = (((long) sx) << 32) ^ (sz & 0xFFFFFFFFL);
        long n = CELL_FILL.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        TOTAL.incrementAndGet();
        if (n == (long) l * l) FULL_CELLS.incrementAndGet();
        long prev;
        do {
            prev = MAX_FILL.get();
            if (n <= prev) break;
        } while (!MAX_FILL.compareAndSet(prev, n));
        FLUSHES.incrementAndGet();
    }

    public static void reset() {
        CELL_FILL.clear();
        TOTAL.set(0); FULL_CELLS.set(0); MAX_FILL.set(0); FLUSHES.set(0);
    }

    public static String report() {
        long cells = CELL_FILL.size();
        long total = TOTAL.get();
        long full = FULL_CELLS.get();
        int l = lattice;
        int per = l * l;
        double avgFill = cells == 0 ? 0 : total / (double) cells;
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(java.util.Locale.ROOT,
                "lattice=%dx%d (%d chunks/unit)  cells_touched=%d  chunks=%d  avg_fill=%.1f/%.1f  full_cells=%d  max_fill=%d",
                l, l, per, cells, total, avgFill, (double) per, full, MAX_FILL.get()));
        sb.append("\n  samples_per_unit(if full)=").append(per * 768)
          .append("  rows_if_512_points_each=").append((per * 768) / 512);
        return sb.toString();
    }

    /** Rough shape a full unit would submit, so m/k/n can be sanity-checked early. */
    public static String shapeHint() {
        int per = lattice * lattice;
        long samples = (long) per * 768;              // noise samples per full unit
        long cells512 = samples / 512;                // 8x8x8 blocks worth
        return "full unit: " + per + " chunks, " + samples + " noise samples, "
             + cells512 + " x 512-cell blocks (m=" + cells512 + " at k=n=512)";
    }
}
