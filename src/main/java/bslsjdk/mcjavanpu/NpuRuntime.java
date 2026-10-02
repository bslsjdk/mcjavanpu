package bslsjdk.mcjavanpu;

/** Stable Java-side inference facade. */
public final class NpuRuntime {
    private static volatile boolean initialized;
    private static volatile boolean available;
    private static volatile String loadError = "not initialized";
    private static volatile String diagnostics = "";

    private NpuRuntime() {}

    public static synchronized void init() {
        HtpBackend.getInstance().initialize();
    }

    static synchronized boolean initInternal() {
        if (initialized && available) return true;
        try {
            NativeLoader.load();
            System.out.println("[MCJavaNPU] native diagnostic log=" + nativeGetLogPath());
            available = nativeInit();
            loadError = available ? "" : nativeGetDeviceInfo();
            try {
                diagnostics = nativeGetDiagnostics();
            } catch (UnsatisfiedLinkError e) {
                diagnostics = "NATIVE_DIAGNOSTICS_UNAVAILABLE " + e + "\n";
                System.err.println("[MCJavaNPU] diagnostics JNI missing: " + e);
            }
            System.out.println("[MCJavaNPU] NPU_INIT_RESULT available=" + available);
            System.out.println("[MCJavaNPU] NPU_DEVICE_INFO=" + loadError);
            System.out.println("[MCJavaNPU] NPU_DIAGNOSTICS_BEGIN\n" + diagnostics
                    + "[MCJavaNPU] NPU_DIAGNOSTICS_END");
        } catch (Throwable error) {
            available = false;
            loadError = error.toString();
            diagnostics = "JAVA_INIT_EXCEPTION " + error + "\n";
            System.err.println("[MCJavaNPU] native runtime unavailable: " + error);
        }
        initialized = true;
        return available;
    }

    public static boolean isInitialized() { return initialized; }
    public static boolean isAvailable() { return available; }
    public static String getLoadError() { return loadError; }
    public static String getDiagnostics() { return diagnostics; }

    public static String getDeviceInfo() { return nativeGetDeviceInfo(); }
    public static String getLogPath() { return nativeGetLogPath(); }

    public static TestResult test() {
        return testInternal();
    }

    static TestResult testInternal() {
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
        HtpBackend.getInstance().close();
    }

    static synchronized void shutdownInternal() {
        if (!initialized) return;
        if (available) {
            try { nativeShutdown(); }
            catch (Throwable error) {
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
    private static native String nativeGetDiagnostics();
    private static native boolean nativeTest();
    private static native String nativeBenchmark();
    private static native void nativeShutdown();
}
