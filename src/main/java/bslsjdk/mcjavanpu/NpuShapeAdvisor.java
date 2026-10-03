package bslsjdk.mcjavanpu;

import java.util.Locale;

/**
 * Answers "what matrix shape should I actually use?" for the HTP V73.
 *
 * The constraints below are measured on SM8635 / HTP v73, not guessed:
 *
 *   m < 64              -> the device rejects the graph outright
 *   m = 63              -> borderline, intermittent failures
 *   m >= 128            -> stable
 *   m in [100, 257]     -> cost is essentially unchanged (m is nearly free)
 *   cost                -> roughly O(k * n); 512^3 is about 4x 256^3
 *
 * The consequence is easy to get backwards: the expensive dimensions are k and n,
 * not m. A shape like 98304 x 32 x 1 spends its budget on 98304 rows that buy
 * nothing, while a shape like 16384 x 4 x 1 does the same per-point work far
 * cheaper. Prefer large m with small k.
 *
 * Padding is the other half. bucketize() rounds each dimension up to the next
 * power of two, and the rounding is per-dimension, so a shape that is unlucky in
 * all three can inflate the real work several times over:
 *
 *   33 x 33 x 33 -> 64 x 64 x 64   (about 7.3x the arithmetic)
 *
 * This class exists to make that visible before the call, not after.
 */
public final class NpuShapeAdvisor {

    /** Must mirror NpuDispatcher.MM_BUCKETS and the native MM_BUCKETS. */
    public static final int[] BUCKETS = {32, 64, 128, 256, 512, 1024, 2048, 4096, 8192, 16384, 32768, 65536};

    /** Below this the HTP refuses to build the graph. */
    public static final int M_FLOOR = 128;

    /** bucketize() returns 0 above this, which the native side rejects. */
    public static final int DIM_MAX = 65536;

    private NpuShapeAdvisor() {}

    public static int bucket(int v) {
        for (int b : BUCKETS) if (v <= b) return b;
        return 0;
    }

    /**
     * A planned shape plus the cost of getting there.
     */
    public static final class Plan {
        public final boolean valid;
        public final String reason;
        public final int m, k, n;            // what will actually be executed
        public final int reqM, reqK, reqN;   // what was asked for
        public final double paddingRatio;    // executed work / requested work
        public final long inputBytes, outputBytes;
        public final double estCost;         // relative units, O(k*n) with a small m term

        Plan(boolean valid, String reason, int reqM, int reqK, int reqN,
             int m, int k, int n, double paddingRatio, long inputBytes, long outputBytes, double estCost) {
            this.valid = valid; this.reason = reason;
            this.reqM = reqM; this.reqK = reqK; this.reqN = reqN;
            this.m = m; this.k = k; this.n = n;
            this.paddingRatio = paddingRatio;
            this.inputBytes = inputBytes; this.outputBytes = outputBytes;
            this.estCost = estCost;
        }

        public String summary() {
            if (!valid) return "INVALID: " + reason;
            return String.format(Locale.ROOT,
                    "req=%dx%dx%d -> plan=%dx%dx%d  padding=%.2fx  in=%s  out=%s  estCost=%.1f",
                    reqM, reqK, reqN, m, k, n, paddingRatio,
                    human(inputBytes), human(outputBytes), estCost);
        }

        /** True when padding more than doubles the work, i.e. worth re-shaping. */
        public boolean paddingIsWasteful() { return valid && paddingRatio > 2.0; }
    }

    public static Plan plan(int reqM, int reqK, int reqN) {
        if (reqM <= 0 || reqK <= 0 || reqN <= 0)
            return bad(reqM, reqK, reqN, "non-positive dimension");

        int bm = bucket(Math.max(reqM, M_FLOOR));
        int bk = bucket(reqK);
        int bn = bucket(reqN);
        if (bm == 0 || bk == 0 || bn == 0)
            return bad(reqM, reqK, reqN, "dimension above " + DIM_MAX + "; split the batch");

        // Guard the byte size too: the native side rejects a tensor over its cap,
        // and 65536 x 65536 would overflow the uint32 dataSize field regardless.
        long inA = (long) bm * bk, inB = (long) bk * bn, outC = (long) bm * bn;
        if (inA > Integer.MAX_VALUE || inB > Integer.MAX_VALUE || outC > Integer.MAX_VALUE)
            return bad(reqM, reqK, reqN, "tensor exceeds 2 GiB");

        double reqWork = (double) reqM * reqK + (double) reqK * reqN + (double) reqM * reqN;
        double planWork = (double) bm * bk + (double) bk * bn + (double) bm * bn;
        double ratio = reqWork > 0 ? planWork / reqWork : 1.0;

        // Cost model: dominated by k*n, with a weak m term. Calibrated against the
        // measured statement "512^3 is about 4x 256^3" -> k*n scaling, m nearly free.
        double estCost = (double) bk * bn * (1.0 + (double) bm / 65536.0);

        return new Plan(true, "", reqM, reqK, reqN, bm, bk, bn, ratio, inA + inB, outC, estCost);
    }

    /**
     * Suggest a cheaper shape for the same per-point work.
     *
     * The usual mistake is one row per sample point with a wide feature vector.
     * Because m is nearly free and k*n is what costs, the cheaper move is to keep
     * the total row count but shrink k, splitting the feature dimension across
     * several calls if needed.
     */
    public static String advise(int reqM, int reqK, int reqN) {
        Plan p = plan(reqM, reqK, reqN);
        if (!p.valid) return p.summary();

        StringBuilder sb = new StringBuilder(p.summary());
        if (p.paddingIsWasteful()) {
            sb.append("\n  ! padding wastes ")
              .append(String.format(Locale.ROOT, "%.1fx", p.paddingRatio))
              .append(" -- round the request up to a bucket first, or batch with neighbours");
        }
        if (reqM < M_FLOOR) {
            sb.append("\n  ! m=").append(reqM).append(" is below the stable floor ")
              .append(M_FLOOR).append("; padding to ").append(p.m);
        }
        if (p.k > 64) {
            sb.append("\n  ! k=").append(p.k).append(" is wide; cost is ~O(k*n), ")
              .append("so splitting k across calls is usually cheaper than one wide call");
        }
        if (p.m > 32768) {
            sb.append("\n  ! m=").append(p.m).append(" is very large; m is nearly free but ")
              .append("the input buffer is ").append(human(p.inputBytes)).append(" per call");
        }
        return sb.toString();
    }

    /** Worked examples for the sizes this project actually hits. */
    public static String examples() {
        StringBuilder sb = new StringBuilder("shape guidance (SM8635 / HTP v73):");
        int[][] cases = {
            {98304, 32, 1},   // current terrain: a full chunk volume
            {16384, 4, 1},    // one 16x16 slice, minimal features
            {4096, 16, 1},    // light fold
            {128, 64, 16},    // entity batch
            {33, 33, 33},     // padding worst case
            {1000, 8, 8},     // realistic small feature
        };
        for (int[] c : cases) sb.append("\n  ").append(advise(c[0], c[1], c[2]));
        return sb.toString();
    }

    private static Plan bad(int m, int k, int n, String why) {
        return new Plan(false, why, m, k, n, 0, 0, 0, 0, 0, 0, 0);
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }
}
