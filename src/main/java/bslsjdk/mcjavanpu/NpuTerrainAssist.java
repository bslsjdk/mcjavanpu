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
     * Hard ceiling on outstanding prefetch requests.
     *
     * The 9x9 experiment queued 81 chunks per request and the log showed pending=80 within seconds.
     * That is the prefetcher competing with the loader for the same CPU: the work it schedules is
     * the work the game is already busy doing, so "more prefetch" became "slower loading".
     *
     * The queue is now bounded by what can be produced, not by what can be wanted. Anything beyond
     * the ceiling is dropped on the floor - being behind is fine, being a competitor is not.
     */
    private static final int MAX_IN_FLIGHT = 12;
    /**
     * Gap between submissions. This is the CPU-sharing knob: larger means the prefetcher takes
     * less of the machine while a world is loading, at the cost of a lower hit rate.
     */
    private static final long MIN_INTERVAL_MS = 25;
    /**
     * Sleep while there is a backlog. Not zero: the worker still has to let the game
     * thread run, but 1 ms instead of 25 ms is the difference between keeping up and
     * never catching up.
     */
    private static final long BUSY_YIELD_MS = 1;

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
    /** Requests refused outright because the gate is closed: work we never started. */
    private static final AtomicLong SKIPPED_GATE = new AtomicLong();
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

    /** Releases every key a batch claimed, so a bail-out cannot poison them. */
    private static void releaseInFlight(List<Long> keys, Long firstKey) {
        if (keys != null) {
            for (int i = 0; i < keys.size(); i++) IN_FLIGHT.remove(keys.get(i));
            return;
        }
        if (firstKey != null) IN_FLIGHT.remove(firstKey);
    }

    private static void loop() {
        while (true) {
            // Declared outside the try so the catch below can release them.
            // A prefetch that dies or bails must not leave its keys in IN_FLIGHT,
            // or that chunk can never be requested again for the rest of the session.
            Long firstKey = null;
            List<Long> keys = null;
            try {
                firstKey = REQUESTED.take();
                if (firstKey == null) continue;

                NpuConfig cfg = NpuConfig.get();
                if (cfg == null || !cfg.enabled) { releaseInFlight(keys, firstKey); continue; }
                // The feature switch is for measurement, not for permission.
                //
                // This line was the second, silent reason the prefetcher never produced anything:
                // NOISE defaults to false, so every iteration left here - before generating, before
                // touching the vanilla tree, before incrementing any failure counter. The log showed
                // processed=0 with failed=0, which is only possible if the loop bailed at the top.
                // chunkMode already decides whether terrain runs; a stats flag must not veto it.
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
                keys = new ArrayList<>(room);
                keys.add(firstKey);
                while (keys.size() < room) {
                    Long next = REQUESTED.peek();
                    if (next == null || keyMinY(next) != minY) break;
                    keys.add(REQUESTED.take());
                }

                // Yield to the game before doing the work, not after - but only when
                // there is actually slack to give.
                //
                // The old code slept MIN_INTERVAL_MS unconditionally, which capped
                // throughput at one batch per 25 ms no matter how far behind we were.
                // A player moving through new terrain queues far faster than that, so
                // the prefetcher could never catch up and the hit rate stayed at zero.
                //
                // Now the sleep is a function of backlog: when chunks are piling up we
                // run flat out (the thread is MIN_PRIORITY, so the scheduler still
                // favours the game), and only when the queue is nearly idle do we hand
                // the CPU back. Being behind is not a reason to slow down further.
                int backlog = REQUESTED.size();
                if (backlog >= room) {
                    Thread.sleep(BUSY_YIELD_MS);
                } else {
                    Thread.sleep(MIN_INTERVAL_MS);
                }

                int n = keys.size();
                int[] cxs = new int[n], czs = new int[n], oys = new int[n];
                long[] seeds = new long[n];
                final long worldSeed = NpuChunkWork.worldSeed();
                for (int i = 0; i < n; i++) {
                    int cx = keyCx(keys.get(i)), cz = keyCz(keys.get(i));
                    cxs[i] = cx; czs[i] = cz; oys[i] = minY;
                    // Same world seed for every chunk. Coordinates belong to the feature row,
                    // not to the seed. The old code accidentally changed the mathematical world
                    // from chunk to chunk by inventing a new seed for every position.
                    seeds[i] = worldSeed;
                }

                /*
                 * IMPORTANT:
                 * npu mode must actually execute the NPU. The previous worker called
                 * NpuTerrainVanilla.fill(), which is a Java density-tree interpreter. It could
                 * produce a plausible volume, but it proved only that the CPU can precompute a
                 * volume in the background. It did NOT prove that world generation used HTP.
                 *
                 * The takeover test path therefore uses the measured NPU lattice generator:
                 *   feature rows -> INT8 matmul -> HTP -> lattice -> CPU interpolation.
                 *
                 * This is deliberately a test terrain generator, not a vanilla-parity generator.
                 * Assist/parity work remains available separately; npu mode's purpose is to make
                 * the NPU own the terrain-generation result.
                 */
                float[][] vols = new float[n][];
                long[] npuUs = new long[1], prepUs = new long[1], interpUs = new long[1];
                long tGen = System.nanoTime();
                vols = NpuTerrainLattice.generateMulti(n, cxs, czs, oys, seeds,
                        DEF_SX, DEF_SY, DEF_SZ, npuUs, prepUs, interpUs);
                interpUs[0] += (System.nanoTime() - tGen) / 1000L
                        - npuUs[0] - prepUs[0] - interpUs[0];
                if (vols == null || vols.length != n) {
                    FAILED.incrementAndGet();
                    lastError = "NPU terrain generator returned no batch";
                    releaseInFlight(keys, firstKey);
                    continue;
                }
                if (vols.length != n) {
                    FAILED.incrementAndGet(); lastError = "short batch";
                    releaseInFlight(keys, firstKey);
                    continue;
                }

                for (int i = 0; i < n; i++) {
                    CACHE.put(keys.get(i), new Prepared(vols[i], DEF_SX, DEF_SY, DEF_SZ));
                    if (!CACHE_ORDER.offer(keys.get(i))) CACHE_ORDER.poll();
                    IN_FLIGHT.remove(keys.get(i));
                }
                // Evict the oldest rather than dropping everything.
                //
                // Clearing on overflow throws away precisely the chunks most likely to
                // be needed next - the ones just prepared around the player - and the
                // hit rate collapses right when it matters. A volume is ~393 KB as
                // floats, so the cache is trimmed while still holding a useful window.
                trimCache();
                BUILT.addAndGet(n);
                // What the scheduler claims to have handled. Paired with the submit
                // counter at the transport, this is how we tell a real batch from a
                // list of chunks that each made their own round trip.
                NpuBatchMetrics.recordLogicalChunks(n);
                BATCHES.incrementAndGet();
                LAST_BATCH.set(n);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                FAILED.incrementAndGet();
                lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
                // Release the whole batch, not just the first key. Releasing only the
                // first would still poison every other chunk in this submission for the
                // rest of the session, which is the same bug in a smaller form.
                releaseInFlight(keys, firstKey);
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

    /** Insertion order for CACHE, so trimming can drop the oldest entry. */
    private static final java.util.concurrent.ArrayBlockingQueue<Long> CACHE_ORDER =
            new java.util.concurrent.ArrayBlockingQueue<>(CACHE_CAP * 2 + 64);

    private static void trimCache() {
        while (CACHE.size() > CACHE_CAP) {
            Long oldest = CACHE_ORDER.poll();
            if (oldest == null) break;
            CACHE.remove(oldest);
        }
    }

    public static float[] take(int cx, int cz, int sx, int sy, int sz, int minY) {
        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return null;
        // Takeover queues here too. This is the whole point of the rework: the game thread must
        // never wait for the NPU, in ANY mode. Takeover wants NPU numbers rather than vanilla's,
        // and the only way to have those without stalling is to queue the work, return "not ready"
        // now, and let the background batcher have it done before the request comes back around.
        if (!"assist".equalsIgnoreCase(cfg.chunkMode) && !"npu".equalsIgnoreCase(cfg.chunkMode)) return null;
        // See loop(): the stats flag is not a permission gate.
        // Queue first, then let the real submission establish service health. A cold service is
        // still a valid target: the first terrain request may be the call that makes healthy=true.

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
    public static final int WORK_SET_SIDE = 3;

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
        // 81 chunks of density evaluation per call, all of it discarded if the
        // gate is closed. Ask first.
        if (!NpuTerrainGate.worthComputing()) { SKIPPED_GATE.incrementAndGet(); return; }
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
        if (IN_FLIGHT.size() >= MAX_IN_FLIGHT) return;
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
        // Nothing we compute can be written while the gate is closed, so do not
        // start. This is the check that stops the mod burning CPU in the default
        // configuration.
        if (!NpuTerrainGate.worthComputing()) { SKIPPED_GATE.incrementAndGet(); return; }
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
                + " skipped_gate=" + SKIPPED_GATE.get()
                + " | " + NpuBatchMetrics.summary()
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }

    public static void clear() {
        CACHE.clear();
        CACHE_ORDER.clear();
        REQUESTED.clear();
        IN_FLIGHT.clear();
    }
}
