package bslsjdk.mcjavanpu;

/** Java-side contract for the native QNN/HTP runtime. */
public final class NpuRuntime {
    private static volatile boolean initialized;
    private static volatile boolean available;

    private NpuRuntime() {}

    public static synchronized void init() {
        if (initialized) return;
        try {
            System.loadLibrary("mcjavanpu");
            available = nativeInit();
        } catch (UnsatisfiedLinkError | RuntimeException error) {
            available = false;
            System.err.println("[MCJavaNPU] native runtime unavailable: " + error);
        }
        initialized = true;
    }

    public static boolean isInitialized() { return initialized; }
    public static boolean isAvailable() { return available; }

    public static String getDeviceInfo() {
        return available ? nativeGetDeviceInfo() : "unavailable";
    }

    public static TestResult test() {
        if (!available) return TestResult.failure("UNAVAILABLE", "native QNN runtime is not loaded");
        try {
            return nativeTest()
                    ? TestResult.success("PASS", "native deterministic test passed")
                    : TestResult.failure("FAIL", "native deterministic test failed");
        } catch (RuntimeException error) {
            return TestResult.failure("ERROR", error.toString());
        }
    }

    public static TestResult benchmark() {
        if (!available) return TestResult.failure("UNAVAILABLE", "native QNN runtime is not loaded");
        try {
            return TestResult.success("READY", nativeBenchmark());
        } catch (RuntimeException error) {
            return TestResult.failure("ERROR", error.toString());
        }
    }

    public static synchronized void shutdown() {
        if (!initialized) return;
        if (available) nativeShutdown();
        available = false;
        initialized = false;
    }

    public record TestResult(boolean success, String name, String detail) {
        static TestResult success(String name, String detail) { return new TestResult(true, name, detail); }
        static TestResult failure(String name, String detail) { return new TestResult(false, name, detail); }
    }

    private static native boolean nativeInit();
    private static native String nativeGetDeviceInfo();
    private static native boolean nativeTest();
    private static native String nativeBenchmark();
    private static native void nativeShutdown();
}
