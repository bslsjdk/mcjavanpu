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
    private static final long MIN_INTERVAL_MS = 40;

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
                Key k = REQUESTED.take();
                Thread.sleep(MIN_INTERVAL_MS);
                NpuConfig cfg = NpuConfig.get();
                if (cfg == null || !cfg.enabled) continue;
                if (NpuStats.NOISE != null && !NpuStats.NOISE.enabled) continue;
                if (!NpuServiceClient.isAvailable()) continue;

                long seed = (k.cx * 341873128712L) ^ (k.cz * 132897987541L) ^ (k.minY * 42317861L);
                NpuTerrainGen.Result r = NpuTerrainGen.generate(k.sx, k.sy, k.sz,
                        k.cx << 4, k.minY, k.cz << 4, seed);
                if (!r.usedNpu) { FAILED.incrementAndGet(); lastError = r.note; continue; }
                synchronized (CACHE) {
                    CACHE.put(k, new Prepared(r.density, k.sx, k.sy, k.sz));
                }
                BUILT.incrementAndGet();
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
                + " queued=" + REQUESTED.size() + "/" + QUEUE_CAP
                + " dropped=" + DROPPED.get() + " failed=" + FAILED.get()
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    public static void clear() {
        synchronized (CACHE) { CACHE.clear(); }
        REQUESTED.clear();
    }
}
