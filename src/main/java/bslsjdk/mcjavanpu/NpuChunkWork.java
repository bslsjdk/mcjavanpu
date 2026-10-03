package bslsjdk.mcjavanpu;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.core.SectionPos;

/**
 * What a newly loaded chunk actually gets from the NPU.
 *
 * Chunk load cost splits into two parts. Terrain density is handled further up by the density
 * sampler hook, so it is not repeated here. What is left is the light pass, and that is the
 * part this class takes: read a section's light, propagate it through the NPU operator, write it
 * back.
 *
 * Two rules shape the implementation:
 *
 *   - everything is reachable by reflection, because the light API here is version sensitive
 *     and a mismatch must degrade to "skip this section", never to a broken world
 *   - work per chunk is capped, because a chunk has 24 sections and doing all of them in one
 *     tick per chunk would defeat the rate limiting the queue exists to provide
 */
public final class NpuChunkWork {

    /** Sections examined per chunk. The interesting light is near the surface, not at bedrock. */
    private static final int MAX_SECTIONS_PER_CHUNK = 4;

    /**
     * A 16^3 section is exactly eight 8^3 sub-blocks. Constant, so it is built once
     * instead of on every call -- it used to be a local, which meant nine array
     * allocations per chunk on the server tick.
     */
    private static final int[][] SUB = {{0,0,0},{8,0,0},{0,0,8},{8,0,8},{0,8,0},{8,8,0},{0,8,8},{8,8,8}};
    private static final int SUB_COUNT = SUB.length;

    private static volatile ServerLevel lastLevel;

    /**
     * Resolved reflection handles.
     *
     * Class.getMethods() clones the entire Method array on every call (it has to,
     * so callers cannot mutate the class's own array). This runs from
     * NpuWorkQueue.pump() on the server tick, several times a second, and each call
     * used to walk the full method list of the light engine, its listener and
     * SectionPos four times over. Caching the resolved Method objects removes that
     * entirely.
     *
     * Keyed on the actual classes, so switching worlds or dimensions -- where the
     * engine instance can differ -- invalidates by itself.
     */
    private static volatile ReflectCache cache;
    private static final Object CACHE_LOCK = new Object();

    private static final class ReflectCache {
        final Class<?> engineClass;
        final Class<?> listenerClass;
        final java.lang.reflect.Method getLayerListener;
        final java.lang.reflect.Method getDataLayerData;
        final java.lang.reflect.Method queueSectionData;
        final java.lang.reflect.Method sectionPosOf;

        ReflectCache(Class<?> ec, Class<?> lc, java.lang.reflect.Method a,
                     java.lang.reflect.Method b, java.lang.reflect.Method c,
                     java.lang.reflect.Method d) {
            this.engineClass = ec; this.listenerClass = lc;
            this.getLayerListener = a; this.getDataLayerData = b;
            this.queueSectionData = c; this.sectionPosOf = d;
        }

        boolean matches(Class<?> ec, Class<?> lc) {
            return engineClass == ec && listenerClass == lc;
        }
    }

    private NpuChunkWork() {}

    /** Set from the chunk-load event so later work can reach the level without threading it through. */
    public static void setLevel(ServerLevel level) { lastLevel = level; }

    public static ServerLevel level() { return lastLevel; }

    /**
     * Runs the NPU light pass for one chunk column. Returns true if anything was actually done.
     *
     * Failing here is not an error condition: the vanilla light engine still owns the result, so
     * a skip just means this chunk was not accelerated.
     */
    public static boolean runForChunk(int cx, int cz) {
        ServerLevel level = lastLevel;
        if (level == null) return false;

        Object layer = null;
        Object engine = null;
        try {
            engine = level.getClass().getMethod("getLightEngine").invoke(level);
            Class<?> ll = Class.forName("net.minecraft.world.level.LightLayer");
            Object blockLayer = null, skyLayer = null;
            for (Object o : ll.getEnumConstants()) {
                String nm = String.valueOf(o);
                if ("BLOCK".equals(nm)) blockLayer = o;
                else if ("SKY".equals(nm)) skyLayer = o;
            }
            layer = blockLayer;
            if (engine == null || layer == null) return false;
            if (!NpuServiceClient.healthy()) return false;

            // Sky light is the same shape of work as block light - one operator applied to
            // a whole 8x8x8 block - so it rides the exact same path. It is opt-in because
            // its propagation rule differs (it does not attenuate on the way down until
            // something opaque stops it), so the linearised operator is only an
            // approximation there. Raise-only still applies, so the vanilla result is
            // always the floor.
            boolean doSky = NpuConfig.get().skyLight && skyLayer != null;

            Object listener = resolveListener(engine, layer);
            if (listener == null) return false;

            ReflectCache rc = resolve(engine.getClass(), listener.getClass());
            if (rc == null) return false;
            java.lang.reflect.Method getData = rc.getDataLayerData;
            java.lang.reflect.Method spOf = rc.sectionPosOf;
            java.lang.reflect.Method queue = rc.queueSectionData;

            Object skyListener = null;
            if (doSky) {
                skyListener = resolveListener(engine, skyLayer);
                if (skyListener == null) doSky = false;
            }

            // LevelHeightAccessor names these getMinSectionY/getMaxSectionY; there is no
            // getMinSection() in this version. Both are inclusive section coordinates.
            int minSec = level.getMinSectionY();
            int maxSec = level.getMaxSectionY();

            // One submission carries all eight sub-blocks, so the batch overhead is paid
            // once per section instead of eight times.
            int done = 0;
            for (int sy = maxSec - 1; sy >= minSec && done < MAX_SECTIONS_PER_CHUNK; sy--) {
                Object sp = spOf.invoke(null, cx, sy, cz);

                boolean anyBlock = propagateSection(engine, listener, getData, queue, sp,
                        blockLayer, sp, cx, sy, cz);
                if (anyBlock) done++;

                if (doSky && skyListener != null) {
                    Object dlSky = getData.invoke(skyListener, sp);
                    if (dlSky instanceof DataLayer) {
                        propagateSection(engine, skyListener, getData, queue, sp,
                                skyLayer, sp, cx, sy, cz);
                    }
                }
            }
            return done > 0;
        } catch (Throwable t) {
            NpuLog.error("chunk work failed for " + cx + "," + cz, t);
            return false;
        }
    }

    /**
     * Returns the block-light listener for this engine.
     *
     * The listener's class is only known after the call returns, so the first
     * invocation has to walk the method list; after that the resolved handle in
     * the cache is invoked directly.
     */
    private static Object resolveListener(Object engine, Object layer) {
        ReflectCache c = cache;
        if (c != null && c.engineClass == engine.getClass() && c.getLayerListener != null
                && c.getLayerListener.getParameterTypes()[0].isInstance(layer)) {
            try { return c.getLayerListener.invoke(engine, layer); }
            catch (Throwable ignored) { return null; }
        }
        try {
            for (java.lang.reflect.Method m : engine.getClass().getMethods()) {
                if (m.getName().equals("getLayerListener") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(layer)) {
                    return m.invoke(engine, layer);
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Resolves and caches the reflection handles for this engine/listener pair.
     * Returns null when a handle cannot be found; the caller treats that as "skip
     * this chunk", and the vanilla light engine still owns the result.
     */
    private static ReflectCache resolve(Class<?> engineClass, Class<?> listenerClass) {
        ReflectCache c = cache;
        if (c != null && c.matches(engineClass, listenerClass)) return c;

        synchronized (CACHE_LOCK) {
            c = cache;
            if (c != null && c.matches(engineClass, listenerClass)) return c;
            try {
                java.lang.reflect.Method getData = null;
                for (java.lang.reflect.Method m : listenerClass.getMethods()) {
                    if (m.getName().equals("getDataLayerData") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0] == SectionPos.class) {
                        getData = m;
                        break;
                    }
                }
                if (getData == null) return null;

                java.lang.reflect.Method spOf = SectionPos.class.getMethod("of", int.class, int.class, int.class);

                java.lang.reflect.Method queue = null;
                for (java.lang.reflect.Method m : engineClass.getMethods()) {
                    if (m.getName().equals("queueSectionData") && m.getParameterCount() == 3) {
                        queue = m;
                        break;
                    }
                }

                java.lang.reflect.Method gll = null;
                for (java.lang.reflect.Method m : engineClass.getMethods()) {
                    if (m.getName().equals("getLayerListener") && m.getParameterCount() == 1) {
                        gll = m;
                        break;
                    }
                }

                c = new ReflectCache(engineClass, listenerClass, gll, getData, queue, spOf);
                cache = c;
                NpuLog.log("chunk work: reflection cached for "
                        + engineClass.getSimpleName() + " / " + listenerClass.getSimpleName());
                return c;
            } catch (Throwable t) {
                NpuLog.error("chunk work: reflection resolve failed", t);
                return null;
            }
        }
    }


    /**
     * One section of one light layer: gather, run through the NPU operator, write back.
     * Returns true when anything was written.
     *
     * Write-back is raise-only. The NPU may brighten a cell but must never darken
     * the world, so the vanilla result always stands as a floor. That is what makes
     * an approximate operator safe to use here.
     */
    private static boolean propagateSection(Object engine, Object listener,
                                            java.lang.reflect.Method getData,
                                            java.lang.reflect.Method queue,
                                            Object sp, Object layer, Object spForQueue,
                                            int cx, int sy, int cz) {
        Object dl;
        try {
            dl = getData.invoke(listener, sp);
        } catch (Throwable t) {
            return false;
        }
        if (!(dl instanceof DataLayer dataLayer)) return false;

        // Gather the eight sub-blocks in the operator's own layout: (y*8 + z)*8 + x.
        byte[] a = new byte[SUB_COUNT * NpuLightAccel.CELLS];
        boolean any = false;
        for (int b = 0; b < SUB_COUNT; b++) {
            int ox = SUB[b][0], oy = SUB[b][1], oz = SUB[b][2];
            int base = b * NpuLightAccel.CELLS;
            for (int y = 0; y < 8; y++) {
                for (int z = 0; z < 8; z++) {
                    for (int x = 0; x < 8; x++) {
                        int v = dataLayer.get(ox + x, oy + y, oz + z);
                        if (v != 0) any = true;
                        a[base + (y * 8 + z) * 8 + x] = (byte) v;
                    }
                }
            }
        }
        if (!any) return false;   // fully dark section, nothing to propagate

        NpuLightAccel.Result r = NpuLightAccel.propagateReal(a, SUB_COUNT);
        if (!r.ok || r.out == null) return false;

        try {
            for (int b = 0; b < SUB_COUNT; b++) {
                int ox = SUB[b][0], oy = SUB[b][1], oz = SUB[b][2];
                for (int y = 0; y < 8; y++) {
                    for (int z = 0; z < 8; z++) {
                        for (int x = 0; x < 8; x++) {
                            int cell = (y * 8 + z) * 8 + x;
                            int nv = r.light(b, cell);
                            int cur = dataLayer.get(ox + x, oy + y, oz + z);
                            if (nv > cur) dataLayer.set(ox + x, oy + y, oz + z, nv > 15 ? 15 : nv);
                        }
                    }
                }
            }
            if (queue != null) queue.invoke(engine, layer, spForQueue, dataLayer);
        } catch (Throwable t) {
            return false;
        }
        return true;
    }

}
