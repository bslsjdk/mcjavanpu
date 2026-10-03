package bslsjdk.mcjavanpu;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rendering, in assist mode.
 *
 * What is genuinely batch-shaped on the render path is visibility: every frame the client walks
 * hundreds of section bounding boxes and pushes all eight corners of each through the same
 * view-projection matrix. That is one matrix applied to a long list of points - a single
 * (8N x 4) * (4 x 4) product - and it is the same operation for every box.
 *
 * So the NPU computes the transformed corners and the CPU keeps only the cheap part: six plane
 * tests per box. The CPU work becomes "am I inside", not "where is it".
 *
 * Guard rails, because this runs every frame:
 *
 *   - bounded batch size; extra boxes are simply tested on the CPU as before
 *   - any failure falls back to the CPU path for that frame, silently
 *   - coordinates are made camera-relative and normalised before int8 quantisation, so world
 *     coordinates in the tens of thousands cannot saturate the tensor
 *   - a frame-rate guard skips the NPU entirely when frames are already long, so we can never
 *     turn a GPU-bound frame into a slower one
 */
public final class NpuRenderAssist {

    /** Corners per bounding box. */
    private static final int CORNERS = 8;
    /** Largest number of boxes pushed through the NPU in one frame. */
    private static final int MAX_BOXES = 512;
    /** If the last frame took longer than this, do not add NPU work to it. */
    private static final long SLOW_FRAME_US = 22_000;

    private static final AtomicLong FRAMES = new AtomicLong();
    private static final AtomicLong NPU_FRAMES = new AtomicLong();
    private static final AtomicLong BOXES_DONE = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final AtomicLong LAST_NPU_US = new AtomicLong();
    private static volatile long lastFrameUs;
    private static volatile String lastError = "";
    private static volatile float lastScaleC;

    private NpuRenderAssist() {}

    /** Called once per frame with the previous frame's duration. */
    public static void frameTick(long frameUs) {
        lastFrameUs = frameUs;
        FRAMES.incrementAndGet();
    }

    /**
     * Transforms up to MAX_BOXES bounding boxes into clip space.
     *
     * Input layout: boxes[i] = {minX, minY, minZ, maxX, maxY, maxZ} in camera-relative space.
     * Output: clip[8*i + c] = the four clip components of corner c of box i, row-major, or null
     * when the NPU path is unavailable and the caller should do it itself.
     *
     * mvp is column-major 4x4, matching the client's own matrix storage.
     */
    public static float[] transformBoxes(float[] boxes, int boxCount, float[] mvp) {
        if (boxes == null || mvp == null || mvp.length < 16) return null;
        int n = Math.min(boxCount, MAX_BOXES);
        if (n <= 0) return null;

        NpuConfig cfg = NpuConfig.get();
        if (cfg == null || !cfg.enabled) return null;
        if (!NpuStats.CHUNK.enabled) return null;   // the chunk switch doubles as the render switch
        if (lastFrameUs > SLOW_FRAME_US) return null;
        if (!NpuServiceClient.isAvailable()) return null;

        try {
            // Corner signs: the standard 8 combinations of (lo, hi) per axis.
            final int[][] C = {{0,0,0},{1,0,0},{0,1,0},{1,1,0},{0,0,1},{1,0,1},{0,1,1},{1,1,1}};
            int m = n * CORNERS;

            // Camera-relative already, but normalise so int8 keeps its precision.
            float maxAbs = 1f;
            for (int i = 0; i < n; i++) {
                int b = i * 6;
                for (int a = 0; a < 3; a++) {
                    maxAbs = Math.max(maxAbs, Math.abs(boxes[b + a]));
                    maxAbs = Math.max(maxAbs, Math.abs(boxes[b + 3 + a]));
                }
            }
            // Quantisation, and why the constants are what they are.
            //
            // The service returns int8, so the entire dynamic range of the product must fit in
            // 127 levels. A 4-term dot product can reach 4 * maxA * maxB, so scaling both sides
            // by s/max caps the worst case at 4*s^2, which puts s at about 5. Both sides are
            // therefore normalised by their own maximum and then scaled by SHIFT.
            //
            // The resulting resolution is (maxA * maxB) / SHIFT^2 world units - a few units for a
            // real view matrix. That is acceptable for visibility, which only needs "roughly where
            // is this box and is it inside", and it is stated rather than hidden because the same
            // trick would NOT be acceptable for the density field.
            final float SHIFT = 5f;
            float sa = SHIFT / maxAbs;

            float maxB = 1e-6f;
            for (int i = 0; i < 16; i++) maxB = Math.max(maxB, Math.abs(mvp[i]));
            float sb = SHIFT / maxB;

            byte[] a = new byte[m * 4];
            for (int i = 0; i < n; i++) {
                int b = i * 6;
                float x0 = boxes[b], y0 = boxes[b + 1], z0 = boxes[b + 2];
                float x1 = boxes[b + 3], y1 = boxes[b + 4], z1 = boxes[b + 5];
                for (int c = 0; c < CORNERS; c++) {
                    float x = C[c][0] == 0 ? x0 : x1;
                    float y = C[c][1] == 0 ? y0 : y1;
                    float z = C[c][2] == 0 ? z0 : z1;
                    int base = (i * CORNERS + c) * 4;
                    a[base]     = (byte) clamp8(Math.round(x * sa));
                    a[base + 1] = (byte) clamp8(Math.round(y * sa));
                    a[base + 2] = (byte) clamp8(Math.round(z * sa));
                    a[base + 3] = (byte) clamp8(Math.round(sa));
                }
            }

            byte[] bmat = new byte[16];
            for (int i = 0; i < 16; i++) bmat[i] = (byte) clamp8(Math.round(mvp[i] * sb));

            long t0 = System.nanoTime();
            NpuRuntime.MatMulResult r = NpuDispatcher.submit(a, bmat, m, 4, 4);
            long us = (System.nanoTime() - t0) / 1000;
            if (!r.ok() || r.c() == null) { FALLBACKS.incrementAndGet(); return null; }

            // Both quantisation factors are known exactly, so the inverse is exact too. scaleC is
            // recorded for diagnostics but not depended on, because its convention belongs to the
            // service and a mismatch would silently rescale an entire frame.
            float scale = 1f / (sa * sb);
            lastScaleC = r.scaleC();
            byte[] c = r.c();
            float[] out = new float[m * 4];
            for (int i = 0; i < m; i++) {
                for (int j = 0; j < 4; j++) out[i * 4 + j] = c[i * 4 + j] * scale;
            }

            LAST_NPU_US.set(us);
            NPU_FRAMES.incrementAndGet();
            BOXES_DONE.addAndGet(n);
            return out;
        } catch (Throwable t) {
            FALLBACKS.incrementAndGet();
            lastError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        }
    }

    private static int clamp8(long v) {
        return v < -127 ? -127 : (v > 127 ? 127 : (int) v);
    }

    public static String summary() {
        long f = FRAMES.get(), nf = NPU_FRAMES.get();
        double rate = f == 0 ? 0 : nf * 100.0 / f;
        return "render_assist frames=" + f + " npu_frames=" + nf
                + " rate=" + String.format(Locale.ROOT, "%.1f%%", rate)
                + " boxes=" + BOXES_DONE.get() + " last_npu_us=" + LAST_NPU_US.get()
                + " fallbacks=" + FALLBACKS.get() + " last_frame_us=" + lastFrameUs
                + " scaleC=" + lastScaleC
                + (lastError.isEmpty() ? "" : " lastError=" + lastError);
    }
}
