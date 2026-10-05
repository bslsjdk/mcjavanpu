package bslsjdk.mcjavanpu;

/**
 * Standalone noise baseline - the measurement that does not need anything else to be working.
 *
 * The in-path probe in NpuNoise.NormalNoise.getValue() has never produced a number, and the
 * reason is structural: it only counts when NpuTerrainVanilla.fill() runs, and fill() returns
 * null immediately when the density tree is unavailable. So the probe that was supposed to
 * decide "is noise worth moving to the DSP" sat behind the very thing it was meant to judge.
 * Every run so far has reported noise_calls=0 for that reason, not because noise is cheap.
 *
 * This baseline breaks that loop. It constructs a noise channel directly, at a fixed seed, over
 * a fixed coordinate grid, and times it. No density tree, no world, no gate, no parity. It runs
 * off the server thread because a million evaluations is seconds of work and blocking the tick
 * loop for that long is a stall, not a measurement.
 *
 * What it can actually answer:
 *   - how expensive one noise evaluation is on the CPU, cold and steady state
 *   - how much noise a chunk's worth of density sampling costs, which is the number that
 *     decides whether a fixed per-call IPC cost can ever be paid back
 *
 * What it cannot answer, and says so instead of guessing:
 *   - the device-side cost of a batched noise kernel. NpuNoiseBatch.available() is false until
 *     a service advertises NOISE_BATCH, so there is no kernel to time. Timing the same loop on
 *     the CPU and comparing it against the CPU per-call number would always read "not worth it"
 *     by construction - it is the same work measured twice. That comparison is not performed.
 *   - the share of worldgen that is noise. That needs a denominator, and the only honest
 *     denominator (a real density evaluation) is the tree this baseline deliberately bypasses.
 */
public final class NpuNoiseBaseline {

    /** Total evaluations. Enough that steady state is steady, small enough to finish. */
    public static final int DEFAULT_CALLS = 1_000_000;
    /** First calls, reported separately: they carry class loading and JIT warmup. */
    public static final int COLD_CALLS = 1_000;
    public static final int[] BATCH_ROWS = {128, 512, 1024};
    public static final int BATCH_REPS = 100;

    /** Give up rather than sit on a slow device; a partial run is reported as partial. */
    public static final long WALL_BUDGET_NS = 20_000_000_000L;

    public static final int GRID_X = 1024, GRID_Y = 384, GRID_Z = 1024;
    public static final int Y_MIN = -64;

    /**
     * Channels per density evaluation when the real count is unavailable.
     *
     * Vanilla's overworld final_density pulls several noise channels, but how many depends on
     * the version's data, which is exactly what we cannot read without the tree. Six is a
     * placeholder and the report marks it as one.
     */
    public static final int ASSUMED_CHANNELS = 6;

    /**
     * Lattice points one 16x16x16 section evaluates, at the steps vanilla asks for (4, 8, 4):
     * (16-1)/4+2 by (16-1)/8+2 by (16-1)/4+2 = 5 x 3 x 5.
     */
    public static final int LATTICE_POINTS = 75;

    /**
     * Cost of one NPU round trip, from the service's own logs: a few ms including IPC. Used as
     * the bar a batch has to clear. It is a measured service number, not an estimate.
     */
    public static final long IPC_FLOOR_NS = 2_000_000L;

    private static volatile String lastReport = "not run yet";
    private static volatile boolean running = false;

    private NpuNoiseBaseline() {}

    public static String lastReport() { return lastReport; }

    public static boolean isRunning() { return running; }

    /** Runs on a virtual thread. Results go to the log; the command does not wait. */
    public static void runAsync(int calls) {
        if (running) return;
        running = true;
        Thread.ofVirtual().name("mcjavanpu-noise-baseline").start(() -> {
            try {
                lastReport = run(calls);
            } catch (Throwable t) {
                lastReport = "baseline failed: " + t;
            } finally {
                running = false;
            }
            NpuLog.log("[NPU] noise baseline\n" + lastReport);
        });
    }

    public static String run(int calls) {
        if (calls < COLD_CALLS * 2) calls = COLD_CALLS * 2;
        long t0 = System.nanoTime();

        NpuNoise.NormalNoise ch = NpuNoise.continentalness(0L);
        double sink = 0.0;

        // ---- cold -------------------------------------------------------------------
        long coldNs = 0;
        int coldN = 0;
        int i = 0;
        for (int b = 0; b < COLD_CALLS / 100; b++) {
            long s = System.nanoTime();
            for (int j = 0; j < 100; j++) sink += sample(ch, i++);
            coldNs += System.nanoTime() - s;
            coldN += 100;
        }

        // ---- steady state -----------------------------------------------------------
        long hotNs = 0;
        int hotN = 0;
        boolean aborted = false;
        final int block = 10_000;
        while (i < calls) {
            int n = Math.min(block, calls - i);
            long s = System.nanoTime();
            for (int j = 0; j < n; j++) sink += sample(ch, i++);
            hotNs += System.nanoTime() - s;
            hotN += n;
            if (System.nanoTime() - t0 > WALL_BUDGET_NS) { aborted = true; break; }
        }

        double coldPer = coldN == 0 ? -1 : coldNs / (double) coldN;
        double hotPer = hotN == 0 ? -1 : hotNs / (double) hotN;

        // ---- batched shape, CPU side -------------------------------------------------
        // With no kernel there is nothing to time on the device, so this measures what the
        // client has to do before a batch could even be submitted: materialise the point
        // arrays. That cost is real and it is paid per row, so it belongs in the report -
        // but it is not the kernel's cost and is not compared against the per-call number.
        StringBuilder batch = new StringBuilder();
        boolean kernel = NpuNoiseBatch.available();
        for (int rows : BATCH_ROWS) {
            long packNs = 0, evalNs = 0;
            long rowsTotal = 0;
            float[] px = new float[rows], py = new float[rows], pz = new float[rows];
            for (int rep = 0; rep < BATCH_REPS; rep++) {
                long a = System.nanoTime();
                for (int r = 0; r < rows; r++) {
                    int q = rep * rows + r;
                    px[r] = q & (GRID_X - 1);
                    py[r] = Y_MIN + ((q >>> 10) % GRID_Y);
                    pz[r] = (q >>> 20) & (GRID_Z - 1);
                }
                packNs += System.nanoTime() - a;
                long b = System.nanoTime();
                for (int r = 0; r < rows; r++) sink += ch.getValue(px[r], py[r], pz[r]);
                evalNs += System.nanoTime() - b;
                rowsTotal += rows;
            }
            if (batch.length() > 0) batch.append(" ; ");
            batch.append(rows).append(" -> pack_ns/row=")
                    .append(fmt(packNs / (double) rowsTotal))
                    .append(" eval_ns/row=")
                    .append(fmt(evalNs / (double) rowsTotal));
            if (System.nanoTime() - t0 > WALL_BUDGET_NS) { aborted = true; break; }
        }

        // ---- per-chunk projection -----------------------------------------------------
        int channels;
        boolean channelsAssumed;
        if (NpuTerrainVanilla.lowered()) {
            channels = Math.max(1, NpuTerrainVanilla.programNoiseCount());
            channelsAssumed = false;
        } else {
            channels = ASSUMED_CHANNELS;
            channelsAssumed = true;
        }
        double perChunkNs = LATTICE_POINTS * (double) channels * Math.max(0, hotPer);

        String verdict;
        if (aborted || hotN < calls / 2) {
            verdict = "NEED_MORE";
        } else if (perChunkNs < IPC_FLOOR_NS) {
            verdict = "NOT_WORTH";
        } else if (perChunkNs < 4L * IPC_FLOOR_NS) {
            verdict = "NEED_MORE";
        } else {
            verdict = "WRITE_DSP";
        }

        // The baseline calls getValue(), which feeds the same counters the in-path probe
        // uses. Clear them so a later in-path reading is not inflated by this run.
        NpuNoise.resetNoiseCounters();

        StringBuilder sb = new StringBuilder();
        sb.append("  seed=0 grid=").append(GRID_X).append("x").append(GRID_Y)
                .append("x").append(GRID_Z).append(" y0=").append(Y_MIN)
                .append(" calls=").append(coldN + hotN)
                .append(aborted ? " (aborted on wall budget)" : "");
        sb.append("\n  cold: n=").append(coldN)
                .append(" total_us=").append(coldNs / 1000)
                .append(" per_call_ns=").append(fmt(coldPer));
        sb.append("\n  hot:  n=").append(hotN)
                .append(" total_us=").append(hotNs / 1000)
                .append(" per_call_ns=").append(fmt(hotPer));
        sb.append("\n  batch: ").append(kernel ? "" : "CPU-side only (no NOISE_BATCH kernel) ")
                .append(batch);
        sb.append("\n  channels=").append(channels).append(channelsAssumed ? " (assumed)" : "")
                .append(" lattice_pts=").append(LATTICE_POINTS)
                .append(" -> est_ms_per_section=").append(fmt(perChunkNs / 1e6));
        sb.append("\n  ipc_floor_ms=").append(IPC_FLOOR_NS / 1e6)
                .append(" (measured service round trip)");
        sb.append("\n  share_est=? (denominator needs the density tree; this baseline bypasses it on purpose)");
        sb.append("\n  verdict=").append(verdict)
                .append(verdict.equals("WRITE_DSP")
                        ? " (headroom over a stated IPC floor - still needs a kernel to confirm)"
                        : "");
        if (sink == 123.456) sb.append("\n  (unreachable)");
        return sb.toString();
    }

    private static double sample(NpuNoise.NormalNoise ch, int i) {
        double x = i % GRID_X;
        double z = (i / GRID_X) % GRID_Z;
        double y = Y_MIN + ((i / (long) (GRID_X * GRID_Z)) % GRID_Y);
        return ch.getValue(x, y, z);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
