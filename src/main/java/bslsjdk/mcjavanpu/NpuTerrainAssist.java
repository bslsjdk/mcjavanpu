package bslsjdk.mcjavanpu;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Terrain, in assist mode.
 *
 * Assist cannot mean "compute half and hand it to vanilla": vanilla rebuilds the whole volume
 * from its own tree and never reads our intermediates. So assist is time shifting:
 *
 *   background  ->  prepare chunk density before it is asked for
 *   hit         ->  main thread copies and vanilla never runs for that chunk
 *   miss        ->  vanilla runs, and the chunk is queued for next time
 *
 * Three rules keep this from becoming a regression, and each one exists because an earlier
 * version broke it:
 *
 *   1. the probe must not do network IO
 *      A service availability check per probe opens a socket and waits for a reply. On the chunk
 *      path that was a round trip per chunk on the main thread, which is simply slower than not
 *      having this class at all. Health is now a cached flag.
 *
 *   2. the probe must not allocate or lock
 *      A record key per probe fed the collector, and an access-ordered LinkedHashMap inside a
 *      synchronized block let the worker stall the game thread. The key is now packed into a
 *      long and the cache is a ConcurrentHashMap read.
 *
 *   3. the worker must not compete for CPU
 *      World load is already CPU saturated; a prefetch thread that runs flat out steals from the
 *      thing it is trying to help. The rate is deliberately modest and the thread is low priority.
 */
public final class NpuTerrainAssist {

    /** Prepared chunks retained. Roughly 225 floats each, so this is a trivial amount of memory. */
    private static final int CACHE_CAP = 256;
    /** Requests waiting on the worker. Bounded: prefetch is an optimisation, never a backlog. */
    private static final int QUEUE_CAP = 256;
    /**
     * Gap between submissions. This is the CPU-sharing knob: larger means the prefetcher takes
     * less of the machine while a world is loading, at the cost of a lower hit rate.
     */
    private static final long MIN_INTERVAL_MS = 10;

    /** Overworld chunk shape this path serves. */
    private static final int DEF_SX = 16, DEF_SY = 384, DEF_SZ = 16;

    /**
     * Vanilla's own interpolated cell sizes for the overworld.
     *
     * These are NOT ours to choose. final_density is "interpolated" with cell_size_xz=4 and
     * cell_size_y=8; evaluating on a different lattice would change the terrain, which is exactly
     * the mistake the previous implementation made.
     */
    private static final int DEF_STEP_XZ = 4, DEF_STEP_Y = 8;

    private static final class Prepared {
        final float[] density;
        final int sx, sy, sz;
        Prepared(float[] density, int sx, int sy, int sz) {
            this.density = density; this.sx = sx; this.sy = sy; this.sz = sz;
        }
    }

    private static final Map<Long, Prepared> CACHE = new ConcurrentHashMap<>();
    private static final ArrayBlockingQueue<Long> REQUESTED = new ArrayBlockingQueue<>(QUEUE_CAP);

    private static final AtomicLong HITS = new AtomicLong();
    private static final AtomicLong MISSES = new AtomicLong();
    private static final AtomicLong BUILT = new AtomicLong();
    private static final AtomicLong BATCHES = new AtomicLong();
    private static final AtomicLong LAST_BATCH = new AtomicLong();
    private static final AtomicLong DROPPED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static volatile boolean workerStarted;
    private static volatile String lastError = "";

    /**
     * Takeover-mode cache: volumes generated as a by-product of a four-chunk batch.
     *
     * Eight entries is ~3 MB. Enough to cover the walk-ahead that actually happens while the
     * player moves (the next three chunks of the batch), without holding a whole region.
     */
    private static final int TAKEOVER_CAP = 8;
    private static final Map<Long, float[]> TAKEOVER_CACHE = new ConcurrentHashMap<>();
    private static final AtomicLong TAKEOVER_BATCHES = new AtomicLong();
    private static final AtomicLong TAKEOVER_SERVED = new AtomicLong();

    public static void countTakeoverBatch() { TAKEOVER_BATCHES.incrementAndGet(); }
    public static void countTakeoverServed() { TAKEOVER_SERVED.incrementAndGet(); }

    private NpuTerrainAssist() {}

    /** Packs chunk and height into one long. Bit layout is fixed so both sides agree. */
    private static long key(int cx, int cz, int minY) {
        return ((long) cx << 40) ^ (((long) cz & 0xFFFFFFFL) << 16) ^ (minY & 0xFFFFL);
    }

    private static int keyCx(long k) { return (int) (k >> 40); }
    private static int keyCz(long k) { return (int) ((k >> 16) & 0xFFFFFFFL); }
    private static int keyMinY(long k) { return (short) (k & 0xFFFFL); }

    private static synchronized void ensureWorker() {
        if (workerStarted) return;
        workerStarted = true;
        Thread t = new Thread(NpuTerrainAssist::loop, "npu-terrain-assist");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        NpuLog.log("terrain assist worker started");
    }

    private static void loop() {
        while (true) {
            try {
                Long firstKey = REQUESTED.take();
                if (firstKey == null) continue;

                NpuConfig cfg = NpuConfig.get();
                if (cfg == null || !cfg.enabled) continue;
                if (NpuStats.NOISE != null && !NpuStats.NOISE.enabled) continue;
                // No health gate here any more.
                //
                // This used to be "if not healthy and not reachable, skip the item", and that single
                // line is why processed=0: the very first call includes graph construction, which is
                // slow, which tripped the guard, which made healthy() false forever, which made the
                // prefetcher drop every request it was given. 8231 submitted, 0 processed, all while
                // the service was up and answering.
                //
                // The prefetcher is now unconditionally optimistic: it attempts the work and lets the
                // call itself decide success or failure. A failed attempt is cheap (the client already
                // has cooldown handling); silently discarding everything is not.

                int minY = keyMinY(firstKey);

                // Gather a whole submission's worth. Fewer, larger calls is the only lever that
                // raises throughput, because the native side serialises on a global lock.
                // Two submissions' worth per drain. One submission covers four chunks, and four
                // chunks per 10 ms still leaves the prefetcher behind a fast-moving player; eight
                // per drain lifts the ceiling to roughly 800 chunks/s of walk-ahead while keeping
                // the CPU yield pattern the game needs.
                int room = Math.max(1, NpuTerrainLattice.maxChunksPerSubmit(DEF_SX, DEF_SY, DEF_SZ) * 2);
                List<Long> keys = new ArrayList<>(room);
                keys.add(firstKey);
                while (keys.size() < room) {
                    Long next = REQUESTED.peek();
                    if (next == null || keyMinY(next) != minY) break;
                    keys.add(REQUESTED.take());
                }

                // Yield to the game before doing the work, not after.
                Thread.sleep(MIN_INTERVAL_MS);

                int n = keys.size();
                int[] cxs = new int[n], czs = new int[n], oys = new int[n];
                long[] seeds = new long[n];
                for (int i = 0; i < n; i++) {
                    int cx = keyCx(keys.get(i)), cz = keyCz(keys.get(i));
                    cxs[i] = cx; czs[i] = cz; oys[i] = minY;
                    seeds[i] = (cx * 341873128712L) ^ (cz * 132897987541L) ^ (minY * 42317861L);
                }

                // Vanilla maths, evaluated here in the background.
                //
                // This used to call NpuTerrainLattice, which invented its own noise and therefore
                // produced a different world for the same seed. The volumes are now evaluated from
                // the game's own final_density tree, and the sampling lattice is the one the game
                // uses (step 4/8/4), not one chosen to flatter the NPU. What the game gets back is
                // vanilla's answer; the only thing that changed is where it was computed.
                float[][] vols = new float[n][];
                long[] npuUs = new long[1], prepUs = new long[1], interpUs = new long[1];
                long tGen = System.nanoTime();
                for (int i = 0; i < n; i++) {
                    vols[i] = NpuTerrainVanilla.fill(DEF_SX, DEF_SY, DEF_SZ,
                            cxs[i] << 4, oys[i], czs[i] << 4, seeds[i],
                            DEF_STEP_XZ, DEF_STEP_Y, DEF_STEP_XZ);
                }
                interpUs[0] = (System.nanoTime() - tGen) / 1000;
                if (vols[0] == null) {
                    FAILED.incrementAndGet();
                    NpuLog.log("prefetch: vanilla tree unavailable (" + NpuTerrainVanilla.failReason()
                            + "), falling back to per-chunk vanilla generation");
                    continue;
                }
                if (vols.length != n) { FAILED.incrementAndGet(); lastError = "short batch"; continue; }

                for (int i = 0; i < n; i++) {
                    CACHE.put(keys.get(i), new Prepared(vols[i], DEF_SX, DEF_SY, DEF_SZ));
            IN_FLIGHT.remove(keys.get(i));
                }
                if (CACHE.size() > CACHE_CAP * 2) CACHE.clear();
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
     * Main-thread probe. Allocation-free, lock-free, network-free by construction.
     *
     * Returns prepared density when ready, otherwise null and queues a prefetch.
     */
    /**
     * Lookup without touching the assist statistics.
     *
     * Takeover batching reuses this cache, and folding its traffic into the assist hit rate
     * would make the assist numbers meaningless.
     */
    public static float[] peek(int cx, int cz, int minY) {
        Prepared p = CACHE.get(key(cx, cz, minY));
        if (p == null) return null;
        if (p.density.length != DEF_SX * DEF_SY * DEF_SZ) return null;
        return p.density;
    }

    /**
     * Publish a volume somebody else computed.
     *
     * Takeover generates four chunks per submission (the element budget allows exactly four at
     * 225 lattice points and K=16). The one that was asked for is consumed immediately; the other
     * three would be thrown away, so they are parked here for the neighbours that are about to
     * load. That is what turns one ~50 ms submission into four served chunks.
     *
     * Capacity is deliberately much smaller than the assist cache: a full 16x384x16 volume is
     * ~393 KB as floats, so a large takeover cache would be hundreds of megabytes.
     */
    public static void put(int cx, int cz, int minY, float[] vol) {
        if (vol == null || vol.length != DEF_SX * DEF_SY * DEF_SZ) return;
        if (TAKEOVER_CACHE.size() >= TAKEOVER_CAP) TAKEOVER_CACHE.clear();
        TAKEOVER_CACHE.put(key(cx, cz, minY), vol);
    }

    /**
     * Takeover lookup: same store as assist.
     *
     * The two modes differ in who is allowed to run vanilla (takeover says nobody), not in what a
     * finished volume looks like. Both therefore post into and read from one cache, so a volume
     * built for an assist prefetch is usable by takeover and vice versa.
     */
    public static float[] peekTakeover(int cx, int cz, int minY) {
        float[] parked = TAKEOVER_CACHE.get(key(cx, cz, minY));
        if (parked != null) return parked;
        Prepared p = CACHE.get(key(cx, cz, minY));
        if (p == null) return null;
        if (p.density.length != DEF_SX * DEF_SY * DEF_SZ) return null;
        return p.density;
    }

    public static int takeoverCached() { return TAKEOVER_CACHE.size(); }

    public static String takeoverSummary() {
        return "takeover_cache=" + TAKEOVER_CACHE.size() + "/" + TAKEOVER_CAP
                + " batches=" + TAKEOVER_BATCHES.get() + " served=" + TAKEOVER_SERVED.get();
    }

    public static float[] take(int cx, int cz, int sx, int sy, int sz, int minY) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return null;
        // Takeover queues here too. This is the whole point of the rework: the game thread must
        // never wait for the NPU, in ANY mode. Takeover wants NPU numbers rather than vanilla's,
        // and the only way to have those without stalling is to queue the work, return "not ready"
        // now, and let the background batcher have it done before the request comes back around.
        if (!"assist".equalsIgnoreCase(cfg.chunkMode) && !"npu".equalsIgnoreCase(cfg.chunkMode)) return null;
        if (NpuStats.NOISE != null && !NpuStats.NOISE.enabled) return null;
        if (!NpuServiceClient.healthy()) return null;

        long k = key(cx, cz, minY);
        Prepared p = CACHE.get(k);
        if (p != null && p.sx == sx && p.sy == sy && p.sz == sz) {
            HITS.incrementAndGet();
            return p.density;
        }
        MISSES.incrementAndGet();
        if (!workerStarted) ensureWorker();
        if (!REQUESTED.offer(k)) DROPPED.incrementAndGet();
        return null;
    }

    /**
     * Queue a chunk for background generation without counting it as an assist miss.
     *
     * Takeover uses this. It is deliberately fire-and-forget: no return value, no waiting, no
     * exception, so a caller on the game thread cannot be delayed by it. Requests beyond the
     * queue capacity are dropped, which is the correct failure mode - the game keeps generating
     * terrain with vanilla, and the next chunk along will probably fit.
     */
    /**
     * Side of the square work set requested around the chunk the game just asked for.
     *
     * Nine, not two. The earlier version only ever queued the immediate neighbours of a single
     * chunk, which is why the NPC always looked starved: by the time the player walks into the
     * next chunk the prefetcher has had one submission's worth of time to produce it, and one
     * submission is ~9 ms of service plus lock waiting. A 9x9 set is 81 chunks - enough that the
     * queue stays full, the batcher always has four compatible chunks to submit together, and the
     * result is produced well before the player arrives.
     *
     * It is still requested as one work set and executed as many small submissions, which is the
     * only shape that fits the 16384 element budget. "Plan 81, execute in batches of 4".
     */
    public static final int WORK_SET_SIDE = 9;

    /** Chunks currently queued or already produced, so a redraw does not queue duplicates. */
    private static final java.util.Set<Long> IN_FLIGHT =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Submit a whole work set centred on one chunk.
     *
     * Ordering matters more than it looks: the centre goes first, then rings outward, so the chunk
     * the player is about to need is produced before the ones at the edge of the set. The walk-ahead
     * is biased along the direction of travel when the caller knows it, otherwise the ring order
     * still gives a usable near-first result.
     */
    public static void requestWorkSet(int cx, int cz, int sx, int sy, int sz, int minY) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        if (!workerStarted) ensureWorker();

        int half = WORK_SET_SIDE / 2;
        for (int ring = 0; ring <= half; ring++) {
            for (int dz = -ring; dz <= ring; dz++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    // Only the shell of this ring; the interior was queued by earlier iterations.
                    if (ring > 0 && Math.abs(dx) != ring && Math.abs(dz) != ring) continue;
                    offer(cx + dx, cz + dz, minY);
                }
            }
        }
    }

    private static void offer(int cx, int cz, int minY) {
        long k = key(cx, cz, minY);
        if (CACHE.containsKey(k) || TAKEOVER_CACHE.containsKey(k)) return;
        if (!IN_FLIGHT.add(k)) return;              // already queued by someone else
        if (!REQUESTED.offer(k)) {
            IN_FLIGHT.remove(k);
            DROPPED.incrementAndGet();
        }
    }

    public static void request(int cx, int cz, int sx, int sy, int sz, int minY) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return;
        if (!NpuServiceClient.healthy()) return;
        if (!workerStarted) ensureWorker();
        long k = key(cx, cz, minY);
        if (CACHE.containsKey(k)) return;
        if (!REQUESTED.offer(k)) DROPPED.incrementAndGet();
    }

    public static String summary() {
        long h = HITS.get(), m = MISSES.get();
        double rate = (h + m) == 0 ? 0 : h * 100.0 / (h + m);
        return "terrain_assist hits=" + h + " misses=" + m
                + " hit_rate=" + String.format(java.util.Locale.ROOT, "%.1f%%", rate)
                + " prepared=" + BUILT.get() + " cached=" + CACHE.size() + "/" + CACHE_CAP
                + " batches=" + BATCHES.get() + " last_batch=" + LAST_BATCH.get()
                + " queued=" + REQUESTED.size() + "/" + QUEUE_CAP
                + " dropped=" + DROPPED.get() + " failed=" + FAILED.get()
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    public static void clear() {
        CACHE.clear();
        REQUESTED.clear();
    }
}
