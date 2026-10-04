package bslsjdk.mcjavanpu;

/**
 * Runs the whole diagnostic set by itself, the moment the game is up.
 *
 * Why this exists: an optimisation mod that only works when you type a command
 * is not an optimisation mod, it is a benchmark. Everything interesting has to
 * happen unattended, in the background, and land in the log file so it can be
 * read after the fact.
 *
 * Sequence, all off the server thread:
 *
 *   1. wait for the world to settle (chunk loading storms poison timings)
 *   2. service probe      - is MCNPU even reachable
 *   3. shape analysis     - what the current shapes actually cost
 *   4. bench sweep        - prepare / submit / cpuRef split, p50 and p99
 *   5. periodic summary   - rolling stats so a long session is readable
 *
 * Every step writes to NpuLog, which persists to logs/mcjavanpu-npu.log. That
 * file is what gets uploaded for analysis; nothing here requires interaction.
 *
 * Controlled by NpuConfig.autoProbe. Off means truly off: no threads, no IPC.
 */
public final class NpuAutoProbe {

    /** How long to let the world settle before measuring anything. */
    private static final long SETTLE_MS = 20_000L;
    /** Rolling summary cadence. */
    private static final long SUMMARY_PERIOD_MS = 60_000L;

    private static volatile boolean started;
    private static volatile boolean running;
    private static volatile String lastReport = "(not run yet)";

    private NpuAutoProbe() {}

    /** Called from mod init. Returns immediately. */
    public static void start() {
        if (started) return;
        started = true;
        NpuConfig cfg = NpuConfig.get();
        if (!cfg.autoProbe) {
            NpuLog.log("autoprobe: disabled by config (autoProbe=false)");
            return;
        }
        running = true;
        Thread.ofVirtual().name("mcjavanpu-autoprobe").start(NpuAutoProbe::run);
    }

    public static void stop() { running = false; }

    public static boolean isRunning() { return running; }

    /** Last full report, for the in-game screen. */
    public static String lastReport() { return lastReport; }

    private static void run() {
        try {
            Thread.sleep(SETTLE_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!running) return;

        StringBuilder sb = new StringBuilder("==== NPU auto probe ====");

        // 0. In-process route. Gated, not removed: the code is correct and it is the
        //    low-latency path if it ever works, but on any launcher without a
        //    uses-native-library declaration it fails with rc=14001 (DSP unreachable from
        //    this process) after burning ~3 s of startup. Opt-in only.
        if (NpuConfig.get().inProcessProbe) {
            try {
                sb.append("\ninprocess: ").append(NpuInProcessProbe.run());
            } catch (Throwable t) {
                sb.append("\ninprocess probe threw: ").append(t);
            }
        } else {
            sb.append("\ninprocess: skipped (inProcessProbe=false; rc=14001 without a "
                    + "launcher native-library declaration)");
        }

        // 1. Service reachability, and the reason when it is not.
        boolean up = NpuRuntime.isAvailable();
        sb.append("\nservice: ").append(up ? "UP" : "DOWN");
        sb.append("\ndevice: ").append(NpuRuntime.getDeviceInfo());
        if (!up) {
            String fail = NpuServiceClient.lastFailure();
            sb.append("\nlastFailure: ").append(fail == null || fail.isEmpty() ? "none" : fail);
            sb.append("\nverdict: MCNPU unreachable - every feature will fall back to CPU.");
            sb.append(" Start the MCNPU app, then it will be picked up within 5s.");
            finish(sb);
            return;
        }

        // 2. Shape analysis. This is the one that catches a shape that is
        //    silently wasting most of its work on padding.
        try {
            sb.append("\n\n").append(NpuShapeAdvisor.examples());
        } catch (Throwable t) {
            sb.append("\nshape analysis failed: ").append(t);
        }

        // 3. Bench sweep: the prepare / submit / cpuRef split that decides
        //    whether any of this is actually faster than doing it on the CPU.
        try {
            sb.append("\n\n").append(NpuBench.sweep());
        } catch (Throwable t) {
            sb.append("\nbench sweep failed: ").append(t);
        }

        // The sweep just built one graph per candidate shape. Hand production a
        // clean cache instead of leaving our diagnostics to evict its graphs.
        try {
            sb.append("\nflush: ").append(NpuRuntime.flushGraphs());
        } catch (Throwable t) {
            sb.append("\nflush failed: ").append(t);
        }

        finish(sb);

        // 4. Keep a readable heartbeat for long sessions.
        long next = System.currentTimeMillis();
        while (running) {
            try {
                Thread.sleep(2000L);
                if (System.currentTimeMillis() - next < SUMMARY_PERIOD_MS) continue;
                next = System.currentTimeMillis();
                NpuLog.log("heartbeat | " + NpuStats.report().replace("\n", " ; ")
                        + " | guard=" + (NpuGuard.isDegraded() ? "DEGRADED " + NpuGuard.reason() : "ok")
                        + " | service=" + (NpuRuntime.isAvailable() ? "UP" : "DOWN")
                        + " | " + NpuBatchMetrics.summary()
                        + " | " + NpuTerrainAssist.summary());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                NpuLog.error("heartbeat failed", t);
            }
        }
    }

    private static void finish(StringBuilder sb) {
        sb.append("\n==== end auto probe ====");
        lastReport = sb.toString();
        NpuLog.log(lastReport);
    }

    /** One-line status for the screen. */
    public static String summary() {
        if (!NpuConfig.get().autoProbe) return "autoprobe off";
        return "autoprobe " + (running ? "active" : "stopped")
                + " guard=" + (NpuGuard.isDegraded() ? "DEGRADED" : "ok");
    }
}
