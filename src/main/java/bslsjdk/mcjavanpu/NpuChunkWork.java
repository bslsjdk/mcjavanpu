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

    private static volatile ServerLevel lastLevel;

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
            for (Object o : ll.getEnumConstants()) if ("BLOCK".equals(String.valueOf(o))) layer = o;
            if (engine == null || layer == null) return false;
            if (!NpuServiceClient.isAvailable()) return false;

            // getLayerListener(LightLayer) -> LayerLightEventListener -> getDataLayerData(SectionPos)
            Object listener = null;
            for (java.lang.reflect.Method m : engine.getClass().getMethods()) {
                if (m.getName().equals("getLayerListener") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(layer)) {
                    listener = m.invoke(engine, layer);
                    break;
                }
            }
            if (listener == null) return false;

            java.lang.reflect.Method getData = null;
            for (java.lang.reflect.Method m : listener.getClass().getMethods()) {
                if (m.getName().equals("getDataLayerData") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0] == SectionPos.class) {
                    getData = m;
                    break;
                }
            }
            if (getData == null) return false;

            java.lang.reflect.Method spOf = SectionPos.class.getMethod("of", int.class, int.class, int.class);
            java.lang.reflect.Method queue = null;
            for (java.lang.reflect.Method m : engine.getClass().getMethods()) {
                if (m.getName().equals("queueSectionData") && m.getParameterCount() == 3
                        && m.getParameterTypes()[0].isInstance(layer)) {
                    queue = m;
                    break;
                }
            }

            int minSec = level.getMinSection();
            int maxSec = level.getMaxSection();
            // Start from the highest sections with stored light: bedrock is almost always black.
            int done = 0;
            for (int sy = maxSec - 1; sy >= minSec && done < MAX_SECTIONS_PER_CHUNK; sy--) {
                Object sp = spOf.invoke(null, cx, sy, cz);
                Object dl = getData.invoke(listener, sp);
                if (!(dl instanceof DataLayer dataLayer)) continue;

                // Flatten 16^3 into the 512-cell layout the operator expects (8^3 blocks).
                boolean any = false;
                byte[] cells = new byte[NpuLightAccel.CELLS * 8];
                int idx = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            int v = dataLayer.get(x, y, z);
                            if (v != 0) any = true;
                            // two cells per byte: value then zero
                            if (idx < cells.length) cells[idx] = (byte) v;
                            idx += 2;
                        }
                    }
                }
                if (!any) continue;   // nothing to propagate in this section

                NpuLightAccel.Result r = NpuLightAccel.propagateOne(cells);
                if (!r.ok || r.out == null) continue;

                // Write the propagated values back through the layer's own set() path.
                int oi = 0;
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            if (oi < r.out.length) {
                                int nv = r.out[oi] & 0xFF;
                                int cur = dataLayer.get(x, y, z);
                                // only ever raise: never let the NPU darken the world
                                if (nv > cur) dataLayer.set(x, y, z, nv > 15 ? 15 : nv);
                            }
                            oi += 2;
                        }
                    }
                }
                if (queue != null) {
                    try { queue.invoke(engine, layer, sp, dataLayer); } catch (Throwable ignored) {}
                }
                done++;
            }
            return done > 0;
        } catch (Throwable t) {
            NpuLog.error("chunk work failed for " + cx + "," + cz, t);
            return false;
        }
    }
}
