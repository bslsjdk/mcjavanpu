package bslsjdk.mcjavanpu;

/**
 * Shape planner for the HTP int8 matmul path.
 *
 * Measured 2026-10-03 on SM8635 / HTP v73:
 *   - m < 64            -> the device rejects the graph outright
 *   - m = 63            -> borderline (intermittent)
 *   - m >= 128          -> stable
 *   - m from 100 to 257 -> cost unchanged (m is essentially free)
 *   - k / n             -> no whitelist, any value works
 *   - cost              -> ~O(k*n); 512^3 is ~4x 256^3
 *
 * So: keep m at or above M_MIN, round k/n up to a coarse bucket so the same
 * logical task keeps hitting an identical graph (avoids ~20ms rebuilds).
 */
public final class NpuDispatcher {

    /** Coarse size ladder. Rounding up avoids rebuilding the graph for tiny size changes. */
    public static final int[] MM_BUCKETS = {128, 192, 256, 384, 512, 768, 1024, 1536, 2048};

    /** Hard floor: below this the HTP refuses to execute. */
    public static final int M_MIN = 128;

    private NpuDispatcher() {}

    /** Smallest ladder value >= v; returns v unchanged if it exceeds the ladder. */
    public static int ceilToBucket(int v) {
        for (int b : MM_BUCKETS) if (v <= b) return b;
        return v;
    }

    /** Final shape to submit: [m, k, n]. */
    public static int[] planShape(int mActual, int kActual, int nActual) {
        int m = Math.max(mActual, M_MIN);
        int k = ceilToBucket(kActual);
        int n = ceilToBucket(nActual);
        return new int[]{m, k, n};
    }

    /**
     * Submit an int8 matmul of logical shape (mActual x kActual) * (kActual x nActual).
     * The tensors are zero-padded to the planned shape, the NPU runs one call, and the
     * result is cropped back to mActual x nActual. Zero padding never changes the maths:
     * padded A rows produce zero output, padded k columns multiply against nothing.
     */
    public static NpuRuntime.MatMulResult submit(byte[] a, byte[] b, int mActual, int kActual, int nActual) {
        if (a.length < mActual * kActual) return err("A_TOO_SMALL expected=" + (mActual * kActual) + " got=" + a.length);
        if (b.length < kActual * nActual) return err("B_TOO_SMALL expected=" + (kActual * nActual) + " got=" + b.length);
        if (mActual <= 0 || kActual <= 0 || nActual <= 0) return err("BAD_SHAPE");

        int[] sh = planShape(mActual, kActual, nActual);
        int m = sh[0], k = sh[1], n = sh[2];

        if (m == mActual && k == kActual && n == nActual) {
            return NpuRuntime.submitMatMulInt8(a, b, mActual, kActual, nActual);
        }

        byte[] A = new byte[m * k];
        for (int i = 0; i < mActual; i++) System.arraycopy(a, i * kActual, A, i * k, kActual);

        byte[] B = new byte[k * n];
        for (int p = 0; p < kActual; p++) System.arraycopy(b, p * nActual, B, p * n, nActual);

        NpuRuntime.MatMulResult r = NpuRuntime.submitMatMulInt8(A, B, m, k, n);
        if (!r.ok()) return r;

        byte[] c = new byte[mActual * nActual];
        for (int i = 0; i < mActual; i++) System.arraycopy(r.c(), i * n, c, i * nActual, nActual);

        return new NpuRuntime.MatMulResult(r.scaleC(), c, r.us(), null);
    }

    private static NpuRuntime.MatMulResult err(String e) { return new NpuRuntime.MatMulResult(0f, null, 0L, e); }
}
