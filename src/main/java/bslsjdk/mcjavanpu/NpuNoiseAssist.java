package bslsjdk.mcjavanpu;

/**
 * Turns a compiled density program into a batched noise evaluation.
 *
 * The problem this solves: the density program reads noise through OP_NOISE, one point at
 * a time, and OP_JZ means which of those actually runs can vary per point. So the
 * evaluation cannot be hoisted by inspecting the program - it has to be hoisted by
 * evaluating every channel the program contains, for every lattice point, before running
 * the program at all.
 *
 * That is safe because noise is pure: an unused value is wasted work, not a wrong answer.
 * And it is worth it because noise is the only part of worldgen that is both large and
 * regular enough to be worth moving - spline, range choice, cache semantics and carvers
 * are branch- and state-dependent and stay exactly where they are. That division is what
 * makes this assist rather than takeover.
 */
public final class NpuNoiseAssist {

    /**
     * What one program needs from a batch.
     *
     * Channels are keyed by (noise index, xz scale, y scale) rather than by noise index
     * alone: the same noise sampled at two scales is two different evaluations.
     */
    public static final class Plan {
        public final boolean usable;
        public final String reason;
        /** One entry per channel. */
        public final int[] noiseIdx;
        public final double[] xzScale;
        public final double[] yScale;
        /** nv slot for each noise op, so OP_NOISE can index the value it needs. */
        public final int[] opChannel;

        Plan(boolean usable, String reason, int[] noiseIdx, double[] xzScale,
             double[] yScale, int[] opChannel) {
            this.usable = usable; this.reason = reason;
            this.noiseIdx = noiseIdx; this.xzScale = xzScale; this.yScale = yScale;
            this.opChannel = opChannel;
        }

        public int channels() { return noiseIdx == null ? 0 : noiseIdx.length; }
    }

    private static volatile Plan cached = null;
    private static NpuDfProgram cachedFor = null;

    private NpuNoiseAssist() {}

    public static void invalidate() {
        cached = null;
        cachedFor = null;
    }

    /** Cached per program: the scan is cheap but this runs per chunk. */
    public static synchronized Plan plan(NpuDfProgram p) {
        if (p == null) return new Plan(false, "no program", null, null, null, null);
        if (cachedFor == p && cached != null) return cached;

        int n = p.noiseEvalCount();
        if (n == 0) {
            cachedFor = p;
            cached = new Plan(false, "program contains no noise", null, null, null, null);
            return cached;
        }

        int[] opChannel = new int[n];
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        java.util.List<Double> xs = new java.util.ArrayList<>();
        java.util.List<Double> ys = new java.util.ArrayList<>();

        // A channel is (noise index, xz scale, y scale). If one noise index appears at two
        // different scales, the values cannot share a slot in nv - nv is indexed by noise
        // index - and the last write would win. Refusing the plan is the only safe answer;
        // silently picking one scale would produce a plausible but wrong world.
        for (int i = 0; i < n; i++) {
            int a = p.noiseIndexAt(i);
            for (int j = i + 1; j < n; j++) {
                if (p.noiseIndexAt(j) != a) continue;
                if (p.noiseXzScaleAt(j) != p.noiseXzScaleAt(i)
                        || p.noiseYScaleAt(j) != p.noiseYScaleAt(i)) {
                    cachedFor = p;
                    cached = new Plan(false,
                            "noise " + a + " sampled at two scales", null, null, null, null);
                    return cached;
                }
            }
        }

        for (int i = 0; i < n; i++) {
            int ni = p.noiseIndexAt(i);
            double xz = p.noiseXzScaleAt(i);
            double yv = p.noiseYScaleAt(i);
            int slot = -1;
            for (int c = 0; c < idx.size(); c++) {
                if (idx.get(c) == ni && xs.get(c).equals(xz) && ys.get(c).equals(yv)) {
                    slot = c;
                    break;
                }
            }
            if (slot < 0) {
                idx.add(ni);
                xs.add(xz);
                ys.add(yv);
                slot = idx.size() - 1;
            }
            opChannel[i] = slot;
        }

        int[] ni = new int[idx.size()];
        double[] dx = new double[idx.size()];
        double[] dy = new double[idx.size()];
        for (int c = 0; c < ni.length; c++) {
            ni[c] = idx.get(c);
            dx[c] = xs.get(c);
            dy[c] = ys.get(c);
        }
        cachedFor = p;
        cached = new Plan(true, "ok", ni, dx, dy, opChannel);
        return cached;
    }

    /**
     * Evaluates every channel of the plan at every lattice point.
     *
     * Goes through the batcher so chunks of one world and dimension share a call; goes to
     * the CPU reference when no kernel is advertised, which is the default and returns
     * exactly what the program would have computed.
     */
    public static float[][] evaluate(Plan pl, NpuDfProgram p, long seed, int dim,
                                     int cx, int cz, int minY,
                                     float[] px, float[] py, float[] pz, int pts) {
        if (pl == null || !pl.usable) return null;
        final NpuNoise.NormalNoise[] noises = p.theNoises();
        final double[] dx = pl.xzScale, dy = pl.yScale;
        final int[] ni = pl.noiseIdx;

        NpuNoiseBatcher.Entry e = new NpuNoiseBatcher.Entry(cx, cz, minY, px, py, pz);
        NpuNoiseBatcher.enqueue(seed, dim, new NpuNoiseBatcher.Evaluator() {
            public float[][] eval(float[] ax, float[] ay, float[] az, int points) {
                NpuNoise.NormalNoise[] sub = new NpuNoise.NormalNoise[ni.length];
                for (int c = 0; c < ni.length; c++) {
                    int k = ni[c];
                    sub[c] = (k >= 0 && k < noises.length) ? noises[k] : null;
                }
                return NpuNoiseBatch.evalOnCpu(sub, dx, dy, ax, ay, az, points);
            }
        }, e);
        NpuNoiseBatcher.drain(seed, dim);
        if (!e.done || e.out == null) {
            NpuLog.log("noiseassist: batch incomplete (" + e.error + "), falling back");
            return null;
        }
        return e.out;
    }

    public static String summary() {
        Plan pl = cached;
        if (pl == null) return "noiseassist: no plan";
        return "noiseassist: " + (pl.usable
                ? pl.channels() + " channels"
                : "unusable (" + pl.reason + ")")
                + " | " + NpuNoiseBatch.summary()
                + " | " + NpuNoiseBatcher.summary();
    }
}
