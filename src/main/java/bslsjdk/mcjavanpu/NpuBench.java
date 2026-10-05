package bslsjdk.mcjavanpu;

import java.util.Arrays;
import java.util.Locale;
import java.util.Random;

/**
 * Honest end-to-end benchmark for the NPU path.
 *
 * Why this exists: the numbers reported elsewhere in this project are easy to
 * misread. `NpuStats.Feature.speedup()` divides hostUs by npuUs, but npuUs is the
 * wall time of the whole submit() call -- Java-side padding, IPC, native padding
 * and graphExecute all rolled into one. That ratio cannot tell you whether the
 * NPU won or the transport lost.
 *
 * This class measures the stages separately and reports both:
 *
 *   prepare  - build + quantise the input in Java
 *   submit   - padding + IPC + native execution (what the caller actually waits for)
 *   cpuRef   - the same maths done locally, for a correctness and cost baseline
 *
 * It then reports TWO different speedups, because they answer different questions:
 *
 *   execSpeedup  = cpuRef / submit      <- "did this call get faster?"
 *   pipelineSpeedup = cpuRef / (prepare + submit)
 *                                       <- "did the whole job get faster?"
 *
 * The second one is the number that matters. A path can look great on the first
 * and still be a pessimisation once prepare is counted, which is exactly what
 * happens when the NPU only performs the last step of a long Java-side pipeline.
 *
 * Percentiles are p50/p99. Averages hide the spikes that actually cause frame
 * drops, so p99 is reported alongside and is the one to watch.
 */
public final class NpuBench {

    /** Runs off the main thread only. A single run can take seconds. */
    public static final class Result {
        public final boolean ok;
        public final String error;
        public final int m, k, n, iters;

        public final long[] prepareUs, submitUs, cpuRefUs;
        public final double badPct, maxAbsErr;

        Result(boolean ok, String error, int m, int k, int n, int iters,
               long[] prepareUs, long[] submitUs, long[] cpuRefUs, double badPct, double maxAbsErr) {
            this.ok = ok; this.error = error;
            this.m = m; this.k = k; this.n = n; this.iters = iters;
            this.prepareUs = prepareUs; this.submitUs = submitUs; this.cpuRefUs = cpuRefUs;
            this.badPct = badPct; this.maxAbsErr = maxAbsErr;
        }

        public String summary() {
            if (!ok) return "BENCH FAILED: " + error;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "shape %dx%dx%d  iters=%d%n", m, k, n, iters));
            sb.append(String.format(Locale.ROOT, "  prepare  p50=%s p99=%s%n", fmt(p50(prepareUs)), fmt(p99(prepareUs))));
            sb.append(String.format(Locale.ROOT, "  submit   p50=%s p99=%s%n", fmt(p50(submitUs)), fmt(p99(submitUs))));
            sb.append(String.format(Locale.ROOT, "  cpuRef   p50=%s p99=%s%n", fmt(p50(cpuRefUs)), fmt(p99(cpuRefUs))));

            double exec = ratio(p50(cpuRefUs), p50(submitUs));
            double pipe = ratio(p50(cpuRefUs), p50(prepareUs) + p50(submitUs));
            sb.append(String.format(Locale.ROOT, "  execSpeedup=%.2fx  pipelineSpeedup=%.2fx%n", exec, pipe));
            sb.append(String.format(Locale.ROOT, "  correctness bad=%.2f%% maxAbs=%.4g%n", badPct, maxAbsErr));

            if (pipe < 1.0) {
                sb.append("  VERDICT: slower than CPU end to end. ")
                  .append(String.format(Locale.ROOT,
                        "prepare costs %.1f%% of the job; move that work into the NPU or drop this path.",
                        100.0 * p50(prepareUs) / (p50(prepareUs) + p50(submitUs))));
            } else if (exec > 1.0 && pipe < exec * 0.5) {
                sb.append("  VERDICT: NPU is fast, but most of the win is eaten by prepare.");
            } else {
                sb.append("  VERDICT: pipeline is faster than CPU.");
            }
            return sb.toString();
        }
    }

    /**
     * @param warmup ignored iterations that pay for graph creation
     * @param iters  measured iterations
     */
    public static Result run(int m, int k, int n, int warmup, int iters) {
        NpuShapeAdvisor.Plan p = NpuShapeAdvisor.plan(m, k, n);
        if (!p.valid) return fail(m, k, n, p.reason);

        if (!NpuRuntime.isAvailable())
            return fail(m, k, n, "MCNPU offline: " + NpuRuntime.getDeviceInfo());

        Random rnd = new Random(1234);
        // Input range must match what the production path actually feeds, otherwise
        // correctness is not comparable. NpuLightAccel quantises into [0,16); a full
        // int8 range [-128,127) over a k=512 reduction produces magnitudes the int8
        // output cannot hold, which shows up as bad=93.82% on a shape that the real
        // data path reports as bad=0/65536. Same shape, same device, only the data
        // distribution differs -- so this bench used to fail a working path.
        final int inputRange = 16;
        long[] prepare = new long[iters];
        long[] submit = new long[iters];
        long[] cpuRef = new long[iters];
        double badPct = 0.0, maxAbs = 0.0;

        for (int i = -(warmup); i < iters; i++) {
            long t0 = System.nanoTime();
            byte[] A = new byte[m * k];
            byte[] B = new byte[k * n];
            for (int q = 0; q < A.length; q++) A[q] = (byte) rnd.nextInt(inputRange);
            for (int q = 0; q < B.length; q++) B[q] = (byte) rnd.nextInt(inputRange);
            long t1 = System.nanoTime();

            NpuRuntime.MatMulResult r = NpuDispatcher.submit(A, B, m, k, n);
            long t2 = System.nanoTime();

            if (!r.ok()) return fail(m, k, n, r.error());

            // Local reference in the same units the service uses (scaleA*scaleB = 1e-6).
            float[] ref = new float[m * n];
            for (int a = 0; a < m; a++)
                for (int c = 0; c < k; c++) {
                    int av = A[a * k + c];
                    for (int b = 0; b < n; b++) ref[a * n + b] += av * B[c * n + b] * 1.0e-6f;
                }
            long t3 = System.nanoTime();

            if (i < 0) continue;  // warmup

            prepare[i] = (t1 - t0) / 1000;
            submit[i] = (t2 - t1) / 1000;
            cpuRef[i] = (t3 - t2) / 1000;

            if (i == 0 && r.c() != null) {
                int bad = 0;
                float scale = r.scaleC() == 0f ? 1e-6f : r.scaleC();
                for (int q = 0; q < ref.length && q < r.c().length; q++) {
                    double got = r.c()[q] * scale;
                    double d = Math.abs(got - ref[q]);
                    if (d > maxAbs) maxAbs = d;
                    if (d > 0.15 + 0.10 * Math.abs(ref[q])) bad++;
                }
                badPct = 100.0 * bad / Math.max(1, ref.length);
            }
        }
        return new Result(true, "", m, k, n, iters, prepare, submit, cpuRef, badPct, maxAbs);
    }

    /** Sweeps the shapes this project actually cares about. Run off-thread. */
    public static String sweep() {
        StringBuilder sb = new StringBuilder("NPU bench sweep (each: 1 warmup + 5 measured)");
        int[][] shapes = {
            {128, 8, 8},
            {128, 16, 16},
            {256, 16, 16},
            {1024, 8, 8},
            {4096, 4, 1},
            {16384, 4, 1},
        };
        for (int[] s : shapes) {
            Result r = run(s[0], s[1], s[2], 1, 5);
            sb.append("\n\n").append(r.summary());
        }
        return sb.toString();
    }

    private static Result fail(int m, int k, int n, String why) {
        return new Result(false, why, m, k, n, 0, new long[0], new long[0], new long[0], 0, 0);
    }

    private static long p50(long[] v) {
        if (v.length == 0) return 0;
        long[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        return c[c.length / 2];
    }

    private static long p99(long[] v) {
        if (v.length == 0) return 0;
        long[] c = Arrays.copyOf(v, v.length);
        Arrays.sort(c);
        int i = (int) Math.min(c.length - 1, Math.ceil(0.99 * c.length) - 1);
        return c[Math.max(0, i)];
    }

    private static double ratio(double a, double b) { return b > 0 ? a / b : 0.0; }

    private static String fmt(long us) {
        if (us < 1000) return us + "us";
        return String.format(Locale.ROOT, "%.2fms", us / 1000.0);
    }

    private NpuBench() {}
}
