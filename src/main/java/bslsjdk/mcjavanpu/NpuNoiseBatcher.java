package bslsjdk.mcjavanpu;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups pending noise evaluations and runs them as one batch.
 *
 * THE BUG THIS EXISTS TO PREVENT
 *
 * The first version of the batched evaluator grouped by arrival order. Chunks from
 * different worlds ended up in the same call, and because one call carries one noise
 * state, every chunk after the first was evaluated with the wrong noise. Density MAE
 * went from 0.0004 to 0.04 - a hundredfold - while the throughput numbers looked
 * perfectly healthy.
 *
 * That is the worst possible shape for a bug: it would never crash and never show up in
 * a timing report. It would only ever be noticed as terrain being subtly wrong in places.
 * So grouping key is (world seed, dimension id) and is never anything coarser.
 *
 * WHY DRAIN IS EXPLICIT
 *
 * There is no background flush thread. Two earlier attempts at automatic protection in
 * this project - the guard's degradation and the work queue's time budget - each managed
 * to stall the path they were protecting, and both did it from a background decision the
 * caller could not see. An explicit drain keeps the caller in control of when work
 * happens, so a stall is visible instead of mysterious.
 */
public final class NpuNoiseBatcher {

    /** Everything the evaluator needs to know about one chunk's lattice. */
    public static final class Entry {
        public final int chunkX, chunkZ, minY;
        public final float[] px, py, pz;      // lattice point coordinates
        /** [channel][point], filled by the evaluator. */
        public volatile float[][] out;
        public volatile boolean done;
        public volatile String error;

        public Entry(int chunkX, int chunkZ, int minY, float[] px, float[] py, float[] pz) {
            this.chunkX = chunkX; this.chunkZ = chunkZ; this.minY = minY;
            this.px = px; this.py = py; this.pz = pz;
        }

        public int points() { return px.length; }
    }

    /** Supplies noise values for a block of points. Implemented on CPU or via NPU. */
    public interface Evaluator {
        /** Returns [channel][point]. */
        float[][] eval(float[] px, float[] py, float[] pz, int points);
    }

    /** Identity of a batchable group. */
    private static final class Key {
        final long seed;
        final int dim;
        Key(long seed, int dim) { this.seed = seed; this.dim = dim; }
        public boolean equals(Object o) {
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return seed == k.seed && dim == k.dim;
        }
        public int hashCode() { return (int) (seed * 31L) + dim; }
    }

    private static final Object LOCK = new Object();
    private static final java.util.HashMap<Key, List<Entry>> PENDING = new java.util.HashMap<>();
    private static final java.util.HashMap<Key, Evaluator> EVALS = new java.util.HashMap<>();

    /** Largest block sent in one call; the m bucket ceiling decides this. */
    public static volatile int maxPointsPerCall = 16384;

    private NpuNoiseBatcher() {}

    public static void reset() {
        synchronized (LOCK) {
            PENDING.clear();
            EVALS.clear();
        }
    }

    /**
     * Queues one chunk's lattice. Does no work; call {@link #drain} to run it.
     *
     * The evaluator is captured per key. If two callers register different evaluators for
     * the same (seed, dimension) that is a bug in the caller, and it is logged rather than
     * silently resolved - a mismatched evaluator produces exactly the wrong-noise failure
     * this class was written to prevent.
     */
    public static void enqueue(long seed, int dim, Evaluator ev, Entry e) {
        if (e == null || ev == null) return;
        synchronized (LOCK) {
            Key k = new Key(seed, dim);
            Evaluator prev = EVALS.putIfAbsent(k, ev);
            if (prev != null && prev != ev) {
                NpuLog.log("noisebatch: conflicting evaluator for seed=" + seed + " dim=" + dim);
            }
            List<Entry> list = PENDING.get(k);
            if (list == null) {
                list = new ArrayList<>();
                PENDING.put(k, list);
            }
            list.add(e);
        }
    }

    public static int pendingCount(long seed, int dim) {
        synchronized (LOCK) {
            List<Entry> l = PENDING.get(new Key(seed, dim));
            return l == null ? 0 : l.size();
        }
    }

    /**
     * Runs everything queued for one (seed, dimension) in as few calls as the point
     * budget allows.
     *
     * Chunks are packed greedily up to the budget; a chunk that would overflow starts the
     * next call. That packing is what turns 64 separate round trips into 20, and it is
     * also why the budget matters more than the chunk count: one chunk is 1225 points, so
     * at a 1024-point ceiling nothing would ever batch.
     */
    public static void drain(long seed, int dim) {
        Key key = new Key(seed, dim);
        List<Entry> entries;
        Evaluator ev;
        synchronized (LOCK) {
            entries = PENDING.remove(key);
            ev = EVALS.get(key);
        }
        if (entries == null || entries.isEmpty()) return;

        List<Entry> block = new ArrayList<>();
        int blockPoints = 0;
        for (Entry e : entries) {
            int p = e.points();
            if (!block.isEmpty() && blockPoints + p > maxPointsPerCall) {
                runBlock(ev, block, blockPoints);
                block.clear();
                blockPoints = 0;
            }
            // A single chunk larger than the budget still goes alone rather than being
            // dropped: correctness over packing.
            block.add(e);
            blockPoints += p;
            if (blockPoints >= maxPointsPerCall) {
                runBlock(ev, block, blockPoints);
                block.clear();
                blockPoints = 0;
            }
        }
        if (!block.isEmpty()) runBlock(ev, block, blockPoints);
    }

    private static void runBlock(Evaluator ev, List<Entry> block, int blockPoints) {
        if (ev == null) {
            for (Entry e : block) {
                e.error = "no evaluator";
                e.done = true;
            }
            return;
        }
        float[] px = new float[blockPoints];
        float[] py = new float[blockPoints];
        float[] pz = new float[blockPoints];
        int off = 0;
        for (Entry e : block) {
            System.arraycopy(e.px, 0, px, off, e.px.length);
            System.arraycopy(e.py, 0, py, off, e.py.length);
            System.arraycopy(e.pz, 0, pz, off, e.pz.length);
            off += e.px.length;
        }

        long t0 = System.nanoTime();
        float[][] all;
        String err = null;
        try {
            all = ev.eval(px, py, pz, blockPoints);
            if (all == null) err = "evaluator returned null";
        } catch (Throwable t) {
            all = null;
            err = t.getClass().getSimpleName();
        }
        long us = (System.nanoTime() - t0) / 1000L;

        if (err != null) {
            NpuNoiseBatch.recordReject(err);
            for (Entry e : block) {
                e.error = err;
                e.done = true;
            }
            return;
        }

        NpuNoiseBatch.recordBatch(block.size(), blockPoints);
        off = 0;
        for (Entry e : block) {
            int p = e.points();
            float[][] mine = new float[all.length][p];
            for (int c = 0; c < all.length; c++) {
                System.arraycopy(all[c], off, mine[c], 0, p);
            }
            e.out = mine;
            e.done = true;
            off += p;
        }
        int[] sh = NpuDispatcher.planShape(blockPoints, 32, 32);
        if (sh != null) {
            NpuBatchMetrics.recordActualSubmit(sh[0] + "x" + sh[1] + "x" + sh[2], us);
        }
    }

    public static String summary() {
        synchronized (LOCK) {
            int groups = PENDING.size();
            int waiting = 0;
            for (List<Entry> l : PENDING.values()) waiting += l.size();
            return "batcher: groups=" + groups + " pending=" + waiting
                    + " budget=" + maxPointsPerCall + "pts";
        }
    }
}
