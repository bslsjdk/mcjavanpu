package bslsjdk.mcjavanpu;

/**
 * Shape planner for the HTP int8 matmul path.
 *
 * Measured 2026-10-03 on SM8635 / HTP v73:
 *   - m < 64            -> the device rejects the graph outright
 *   - m = 63            -> borderline (intermittent)
 *   - m >= 128          -> stable
 *   - m from 100 to 257 -> cost unchanged (m is essentially free)
 *   - m / k / n         -> each dimension is independently rounded to the measured whitelist
 *   - cost              -> ~O(k*n); 512^3 is ~4x 256^3
 *
 * So: keep m at or above M_MIN, round k/n up to a coarse bucket so the same
 * logical task keeps hitting an identical graph (avoids ~20ms rebuilds).
 *
 * MM_BUCKETS MUST stay identical to MM_BUCKETS in mcnpu/app/src/main/cpp/mcnpu.cpp.
 * They used to differ (Java {128..2048} vs native {32..65536}). The result was
 * silent and expensive: Java rounded e.g. 192 to 192, native had no 192 and
 * rounded again to 256, so every call paid for two paddings and the effective
 * shape was not what the caller planned.
 */
public final class NpuDispatcher {

    /** Coarse size ladder. MUST mirror the native side exactly. */
    public static final int[] MM_BUCKETS = {32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};

    /** Hard floor: below this the HTP refuses to execute. */
    public static final int M_MIN = 128;

    /** Native bucketize() returns 0 above this, which fails as ERR BUF_TOO_LARGE. */
    public static final int MM_MAX = 65536;

    private NpuDispatcher() {}

    /** Smallest ladder value >= v; returns 0 if v exceeds the ladder (matches native). */
    public static int ceilToBucket(int v) {
        for (int b : MM_BUCKETS) if (v <= b) return b;
        return 0;
    }

    /**
     * Final shape to submit: [m, k, n].
     * Returns null when the logical shape cannot be expressed at all, so callers
     * can fall back immediately instead of paying for padding + IPC + a failed call.
     */
    public static int planDimension(int actual) {
        if (actual <= 0) return 0;
        int bucket = ceilToBucket(actual);
        if (bucket == 0) return 0;
        return Math.max(bucket, M_MIN);
    }

    public static int[] planShape(int mActual, int kActual, int nActual) {
        if (mActual <= 0 || kActual <= 0 || nActual <= 0) return null;
        int m = planDimension(mActual);
        int k = planDimension(kActual);
        int n = planDimension(nActual);
        if (m == 0 || k == 0 || n == 0 || m > MM_MAX || k > MM_MAX || n > MM_MAX) return null;
        return new int[]{m, k, n};
    }

    /** Ratio of planned tensor work to logical tensor work. 1.0 means no padding. */
    public static double paddingRatio(int mActual, int kActual, int nActual) {
        int[] sh = planShape(mActual, kActual, nActual);
        if (sh == null) return Double.POSITIVE_INFINITY;
        long logical = (long) mActual * kActual * nActual;
        long planned = (long) sh[0] * sh[1] * sh[2];
        return logical <= 0 ? Double.POSITIVE_INFINITY : planned / (double) logical;
    }

    /** True when the planned shape stays below a padding multiplier. */
    public static boolean paddingWithin(int mActual, int kActual, int nActual, double maxRatio) {
        return Double.isFinite(maxRatio) && maxRatio >= 1.0
                && paddingRatio(mActual, kActual, nActual) <= maxRatio;
    }

    /**
     * True when this logical shape can be executed without splitting.
     * A 16x384x16 chunk volume is 98304 rows, which is above MM_MAX and can never
     * be submitted as one matmul. Callers should check this before building the
     * input buffers, not after.
     */
    public static boolean fitsInOneCall(int mActual, int kActual, int nActual) {
        return planShape(mActual, kActual, nActual) != null;
    }

    /**
     * Submit an int8 matmul of logical shape (mActual x kActual) * (kActual x nActual).
     * The tensors are zero-padded to the planned shape, the NPU runs one call, and the
     * result is cropped back to mActual x nActual. Zero padding never changes the maths:
     * padded A rows produce zero output, padded k columns multiply against nothing.
     */
    public static NpuRuntime.MatMulResult submit(byte[] a, byte[] b, int mActual, int kActual, int nActual) {
        // Adaptive backoff. Cheap and allocation-free, so it is safe to sit in
        // front of every call. When the guard has tripped, callers fall back to
        // the vanilla path instead of paying for a slow or absent service.
        if (!NpuGuard.allow()) return err("GUARD_DEGRADED " + NpuGuard.reason());

        if (a == null || b == null) return err("NULL_BUFFER");
        if (a.length < mActual * kActual) return err("A_TOO_SMALL expected=" + (mActual * kActual) + " got=" + a.length);
        if (b.length < kActual * nActual) return err("B_TOO_SMALL expected=" + (kActual * nActual) + " got=" + b.length);
        if (mActual <= 0 || kActual <= 0 || nActual <= 0) return err("BAD_SHAPE");

        int[] sh = planShape(mActual, kActual, nActual);
        if (sh == null) {
            // Explicit and cheap. Previously this silently returned ERR BUF_TOO_LARGE
            // after the caller had already built and padded multi-megabyte buffers.
            return err("SHAPE_UNSUPPORTED m=" + mActual + " k=" + kActual + " n=" + nActual
                    + " (native max " + MM_MAX + "; split the batch)");
        }
        int m = sh[0], k = sh[1], n = sh[2];

        final long t0 = System.nanoTime();

        if (m == mActual && k == kActual && n == nActual) {
            return timed(NpuRuntime.submitMatMulInt8(a, b, mActual, kActual, nActual), t0);
        }

        byte[] A = new byte[m * k];
        for (int i = 0; i < mActual; i++) System.arraycopy(a, i * kActual, A, i * k, kActual);

        byte[] B = new byte[k * n];
        for (int p = 0; p < kActual; p++) System.arraycopy(b, p * nActual, B, p * n, nActual);

        NpuRuntime.MatMulResult r = NpuRuntime.submitMatMulInt8(A, B, m, k, n);
        if (!r.ok()) { reportFail(t0, r.error()); return r; }

        byte[] c = new byte[mActual * nActual];
        for (int i = 0; i < mActual; i++) System.arraycopy(r.c(), i * n, c, i * nActual, nActual);

        return timed(new NpuRuntime.MatMulResult(r.scaleC(), c, r.us(), null), t0);
    }

    /**
     * Split an oversized batch into rows of at most MM_MAX and run them
     * sequentially. Only sane when each row is independent (which is true for
     * per-point feature evaluation: row i only depends on row i).
     */
    public static NpuRuntime.MatMulResult submitSplit(byte[] a, byte[] b, int mActual, int kActual, int nActual) {
        if (mActual <= MM_MAX) return submit(a, b, mActual, kActual, nActual);
        int rows = (mActual + MM_MAX - 1) / MM_MAX;
        byte[] c = new byte[mActual * nActual];
        float scaleC = 0f;
        long us = 0L;
        for (int r0 = 0; r0 < rows; r0++) {
            int off = r0 * MM_MAX;
            int cnt = Math.min(MM_MAX, mActual - off);
            byte[] aSlice = new byte[cnt * kActual];
            System.arraycopy(a, off * kActual, aSlice, 0, aSlice.length);
            NpuRuntime.MatMulResult rr = submit(aSlice, b, cnt, kActual, nActual);
            if (!rr.ok()) return rr;
            System.arraycopy(rr.c(), 0, c, off * nActual, cnt * nActual);
            scaleC = rr.scaleC();
            us += rr.us();
        }
        return new NpuRuntime.MatMulResult(scaleC, c, us, null);
    }

    /** Feed the guard the real wall time, so it can back off on its own. */
    private static NpuRuntime.MatMulResult timed(NpuRuntime.MatMulResult r, long t0Nanos) {
        long us = (System.nanoTime() - t0Nanos) / 1000L;
        if (r.ok()) NpuGuard.recordUs(us);
        else NpuGuard.recordFailure(String.valueOf(r.error()));
        return r;
    }

    private static void reportFail(long t0Nanos, String error) {
        NpuGuard.recordFailure(String.valueOf(error));
    }

    private static NpuRuntime.MatMulResult err(String e) { return new NpuRuntime.MatMulResult(0f, null, 0L, e); }
}
