package bslsjdk.mcjavanpu;

/**
 * Probes the in-process (zero-IPC) NPU route, once, and records the result.
 *
 * Why this exists: the cross-process route costs about 12 ms per submit, and the in-process
 * route - Minecraft JVM dlopens QNN directly - costs microseconds. That difference decides
 * whether any of the rest of this mod is worth having. But nothing currently loads the
 * in-process library: ZL2 only injects a JVM property naming it, it does not call
 * System.load(). So the route sat unverifiable for lack of a trigger, not for lack of code.
 *
 * This is the trigger. It runs once at startup, off the game thread, and changes nothing
 * else - it does not replace NpuRuntime, does not touch the IPC path, and a failure here
 * is a log line and nothing more. It only answers one question: can this process reach the
 * NPU directly?
 */
public final class NpuInProcessProbe {

    private static volatile String result = "(not run)";
    private static volatile boolean ran = false;
    private static volatile boolean ready = false;

    private NpuInProcessProbe() {}

    /** Idempotent. Cheap after the first call. */
    public static synchronized String run() {
        if (ran) return result;
        ran = true;
        if (!NpuConfig.get().inProcessProbe) {
            // See NpuConfig.inProcessProbe: the route is known to fail with rc=14001
            // (DSP not reachable from a game process) unless the launcher declares
            // uses-native-library. Trying anyway costs ~3 s of startup for a certain
            // failure, so it is opt-in.
            result = "SKIP disabled by config (inProcessProbe=false) - the in-process "
                    + "route fails with rc=14001 unless the launcher declares the native "
                    + "library; the cross-process service is the supported path.";
            NpuLog.log("inprocess: " + result);
            return result;
        }
        result = probe();
        NpuLog.log("inprocess: " + result);
        return result;
    }

    private static String probe() {
        // Set by the launcher from the plugin's declared environment:
        //   -Dmcjavanpu.native={nativeLibraryDir}libmcfclnpu.so
        String path = System.getProperty("mcjavanpu.native");
        if (path == null || path.isEmpty()) {
            return "SKIP no -Dmcjavanpu.native - the mcfclnpu plugin is not installed, "
                    + "so the in-process route cannot be tested";
        }

        StringBuilder sb = new StringBuilder("lib=").append(path);

        try {
            System.load(path);
            sb.append(" load=OK");
        } catch (Throwable t) {
            sb.append(" load=FAIL ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage())
                    .append("\nverdict: in-process route unavailable at the load step.");
            return sb.toString();
        }

        try {
            long t0 = System.nanoTime();
            boolean ok = NpuRuntime.nativeInit();
            long us = (System.nanoTime() - t0) / 1000L;
            sb.append(" init=").append(ok ? "OK" : "FAIL").append(" us=").append(us);
            sb.append(" device=").append(NpuRuntime.nativeGetDeviceInfo());

            if (ok) {
                ready = true;
                sb.append(" log=").append(NpuRuntime.nativeGetLogPath());
                sb.append("\nverdict: IN-PROCESS NPU READY - no IPC, no serialization. ")
                        .append("This is the low-latency route; the cross-process path ")
                        .append("(~12 ms per submit) is no longer the ceiling.");
            } else {
                sb.append("\nverdict: in-process init failed - the native log names the ")
                        .append("stage that refused it.");
            }
        } catch (Throwable t) {
            sb.append(" call=FAIL ").append(t.getClass().getSimpleName())
                    .append(": ").append(t.getMessage());
        }
        return sb.toString();
    }

    /** Runs the native smoke test. Slower than init; only for explicit probing. */
    public static String smoke() {
        if (!ready) return "not ready - init did not succeed";
        try {
            long t0 = System.nanoTime();
            boolean ok = NpuRuntime.nativeTest();
            long us = (System.nanoTime() - t0) / 1000L;
            return "smoke=" + (ok ? "PASS" : "FAIL") + " us=" + us;
        } catch (Throwable t) {
            return "smoke=FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static boolean isReady() { return ready; }
    public static String result() { return result; }
}
