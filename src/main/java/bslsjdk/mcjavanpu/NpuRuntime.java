package bslsjdk.mcjavanpu;

/** Java-side contract for the native QNN/HTP runtime. */
public final class NpuRuntime {
    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile String loadError = "not initialized";

    private NpuRuntime() {}

    public static synchronized void init() {
        if (initialized) return;

        try {
            NativeLoader.load();
            System.out.println("[MCJavaNPU] native diagnostic log=" + nativeGetLogPath());
            available = nativeInit();
            loadError = available ? "" : nativeGetDeviceInfo();
        } catch (Throwable error) {
            available = false;
            loadError = error.toString();
            System.err.println("[MCJavaNPU] native runtime unavailable: " + error);
        }

        initialized = true;
    }

    public static boolean isInitialized() { return initialized; }
    public static boolean isAvailable() { return available; }
    public static String getLoadError() { return loadError; }

    public static String getDeviceInfo() {
        return nativeGetDeviceInfo();
    }

    public static String getLogPath() {
        return nativeGetLogPath();
    }

    public static TestResult test() {
        if (!available) return TestResult.failure("UNAVAILABLE", loadError);
        try {
            return nativeTest()
                    ? TestResult.success("PASS", "QNN graphExecute smoke test passed")
                    : TestResult.failure("FAIL", "QNN graphExecute smoke test failed; see " + getLogPath());
        } catch (Throwable error) {
            return TestResult.failure("ERROR", error.toString());
        }
    }

    public static TestResult benchmark() {
        if (!available) return TestResult.failure("UNAVAILABLE", loadError);
        try {
            return TestResult.success("READY", nativeBenchmark());
        } catch (Throwable error) {
            return TestResult.failure("ERROR", error.toString());
        }
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        if (available) {
            try {
                nativeShutdown();
            } catch (Throwable error) {
                System.err.println("[MCJavaNPU] native shutdown failed: " + error);
            }
        }
        available = false;
        initialized = false;
    }

    public record TestResult(boolean success, String name, String detail) {
        static TestResult success(String name, String detail) {
            return new TestResult(true, name, detail);
        }

        static TestResult failure(String name, String detail) {
            return new TestResult(false, name, detail);
        }
    }

    private static native boolean nativeInit();
    private static native String nativeGetDeviceInfo();
    private static native String nativeGetLogPath();
    private static native boolean nativeTest();
    private static native String nativeBenchmark();
    private static native void nativeShutdown();
}
