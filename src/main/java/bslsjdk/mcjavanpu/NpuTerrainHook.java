package bslsjdk.mcjavanpu;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridge between Minecraft's terrain sampling and the batched interpolator.
 *
 * MC hands its density sampler a whole 3D volume at a time (DensitySampler.sampleVolume
 * with a matching DensityVolume), which is the natural batch unit for the NPU: one
 * call's worth of work is already a few thousand sample points.
 *
 * Only counters live here for now. Turning the volumes into NPU calls needs the
 * volume sizes first, so this measures them rather than assuming 4x8x4 or 16x16x384.
 */
public final class NpuTerrainHook {

    private static final AtomicLong VOLUME_CALLS = new AtomicLong();
    private static final AtomicLong POINTS_SEEN = new AtomicLong();
    private static final AtomicLong POINTS_AT_LAST_RESET = new AtomicLong();
    private static volatile int lastX, lastY, lastZ;
    private static volatile long lastReportMs;
    private static volatile String lastVolume = "none";

    private NpuTerrainHook() {}

    /** Called on every density volume sample. Must be extremely cheap. */
    public static void onVolume(int x, int y, int z) {
        NpuStats.Feature f = NpuStats.NOISE;
        if (!f.enabled) return;
        long n = VOLUME_CALLS.incrementAndGet();
        lastX = x; lastY = y; lastZ = z;
        long now = System.currentTimeMillis();
        if (now - lastReportMs > 15_000L) {
            lastReportMs = now;
            NpuLog.log("terrain volumes=" + n + " last_origin=(" + x + "," + y + "," + z + ")");
        }
    }

    /** Records the size of the volume MC just asked for. */
    public static void onVolumeShape(int sx, int sy, int sz) {
        lastVolume = sx + "x" + sy + "x" + sz;
        POINTS_SEEN.addAndGet((long) sx * sy * sz);
    }

    public static long volumeCalls() { return VOLUME_CALLS.get(); }

    public static String summary() {
        return "volumes=" + VOLUME_CALLS.get()
             + " points=" + POINTS_SEEN.get()
             + " last_shape=" + lastVolume
             + " last_origin=(" + lastX + "," + lastY + "," + lastZ + ")";
    }
}
