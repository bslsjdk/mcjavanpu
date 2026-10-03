package bslsjdk.mcjavanpu;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Terrain, in assist mode.
 *
 * Assist cannot mean "compute half and hand it to vanilla", because vanilla does not read our
 * intermediates: it rebuilds the whole volume from its own tree. Computing anything inline is
 * therefore wasted unless we also take over the write.
 *
 * So assist is implemented as time shifting instead of work splitting:
 *
 *   background thread  ->  NPU fills the density for chunks before they are needed
 *   cache hit          ->  the main thread just copies, and vanilla never runs
 *   cache miss         ->  vanilla runs, and the chunk is queued for next time
 *
 * Nothing on the main path ever waits for the NPU. A miss costs exactly what it cost before this
 * class existed, and a hit costs a memory copy. That is the only shape of "assist" that cannot
 * make the game slower.
 *
 * Cache entries are keyed by chunk and volume shape, so a chunk built at a different height or
 * with a different step is never served a wrong sized answer.
 */
public final class NpuTerrainAssist {

    /** How many prepared chunks to keep. ~98304 floats each, so this stays in the low MB. */
    private static final int CACHE_CAP = 96;
    /** Requests waiting on the background thread. Bounded: prefetch is an optimisation. */
    private static final int QUEUE_CAP = 256;
    /** How many chunks the worker prepares per second. Keeps the service free for light. */
    private static final long MIN_INTERVAL_MS = 8;

    private record Key(int cx, int cz, int sx, int sy, int sz, int minY) {}

    private static final class Prepared {
        final float[] density;
        final int sx, sy, sz;
        Prepared(float[] density, int sx, int sy, int sz) {
            this.density = density; this.sx = sx; this.sy = sy; this.sz = sz;
        }
    }

    private static final Map<Key, Prepared> CACHE = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Key, Prepared> eldest) {
            return size() > CACHE_CAP;
        }
    };

    private static final ArrayBlockingQueue<Key> REQUESTED = new ArrayBlockingQueue<>(QUEUE_CAP);
    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong BUILT = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static final AtomicLong BATCHES = new AtomicLong();
    private static final AtomicLong LAST_BATCH = new AtomicLong();
    private static volatile boolean workerStarted;
    private static volatile String lastError = "";

    private NpuTerrainAssist() {}

    private static synchronized void ensureWorker() {
        if (workerStarted) return;
        workerStarted = true;
        Thread t = new Thread(NpuTerrainAssist::loop, "npu-terrain-assist");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);   // never compete with the server or render thread
        t.start();
        NpuLog.log("terrain assist worker started");
    }

    private static void loop() {
        while (true) {
            try {
                Key first = REQUESTED.take();
                NpuConfig cfg = NpuConfig.get();
                if (cfg == null || !cfg.enabled) continue;
                if (NpuStats.NOISE != null && !NpuStats.NOISE.enabled) continue;
                if (!NpuServiceClient.isAvailable()) continue;

                // Gather as many queued requests as the element budget allows for this shape and
                // submit them together. Batching here rather than per chunk is the whole point:
                // the native side serialises on one lock, so fewer, larger calls is the only way
                // to raise throughput.
                int room = Math.max(1, NpuTerrainLattice.maxChunksPerSubmit(first.sx, first.sy, first.sz));
                java.util.List<Key> batch = new java.util.ArrayList<>(room);
                batch.add(first);
                while (batch.size() < room) {
                    Key next = REQUESTED.peek();
                    if (next == null) break;
                    if (next.sx != first.sx || next.sy != first.sy || next.sz != first.sz) break;
                    if (next.minY != first.minY) break;
                    batch.add(REQUESTED.take());
                }
                Thread.sleep(MIN_INTERVAL_MS);

                int n = batch.size();
                int[] cxs = new int[n], czs = new int[n], oys = new int[n];
                long[] seeds = new long[n];
                for (int i = 0; i < n; i++) {
                    Key k = batch.get(i);
                    cxs[i] = k.cx; czs[i] = k.cz; oys[i] = k.minY;
                    seeds[i] = (k.cx * 341873128712L) ^ (k.cz * 132897987541L) ^ (k.minY * 42317861L);
                }
                long[] npuUs = new long[1], prepUs = new long[1], interpUs = new long[1];
                float[][] vols = NpuTerrainLattice.generateMulti(n, cxs, czs, oys, seeds,
                        first.sx, first.sy, first.sz, npuUs, prepUs, interpUs);
                if (vols.length != n) { FAILED.incrementAndGet(); lastError = "short batch"; continue; }

                synchronized (CACHE) {
                    for (int i = 0; i < n; i++) {
                        CACHE.put(batch.get(i), new Prepared(vols[i], first.sx, first.sy, first.sz));
                    }
                }
                BUILT.addAndGet(n);
                BATCHES.incrementAndGet();
                LAST_BATCH.set(n);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                FAILED.incrementAndGet();
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            }
        }
    }

    /**
     * Main-thread probe. Never blocks, never computes.
     *
     * Returns the prepared density when it is ready, otherwise null and queues a prefetch.
     */
    public static float[] take(int cx, int cz, int sx, int sy, int sz, int minY) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return null;
        if (!"assist".equalsIgnoreCase(cfg.chunkMode)) return null;
        if (NpuStats.NOISE != null && !NpuStats.NOISE.enabled) return null;

        Key k = new Key(cx, cz, sx, sy, sz, minY);
        Prepared p;
        synchronized (CACHE) { p = CACHE.get(k); }
        if (p != null && p.sx == sx && p.sy == sy && p.sz == sz) {
            HITS.incrementAndGet();
            return p.density;
        }
        MISSES.incrementAndGet();
        ensureWorker();
        if (!REQUESTED.offer(k)) DROPPED.incrementAndGet();
        return null;
    }

    public static String summary() {
        int size;
        synchronized (CACHE) { size = CACHE.size(); }
        long h = HITS.get(), m = MISSES.get();
        double rate = (h + m) == 0 ? 0 : h * 100.0 / (h + m);
        return "terrain_assist hits=" + h + " misses=" + m + " hit_rate=" + String.format(java.util.Locale.ROOT, "%.1f%%", rate)
                + " prepared=" + BUILT.get() + " cached=" + size + "/" + CACHE_CAP
                + " batches=" + BATCHES.get() + " last_batch=" + LAST_BATCH.get()
                + " queued=" + REQUESTED.size() + "/" + QUEUE_CAP
                + " dropped=" + DROPPED.get() + " failed=" + FAILED.get()
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    public static void clear() {
        synchronized (CACHE) { CACHE.clear(); }
        REQUESTED.clear();
    }
}
