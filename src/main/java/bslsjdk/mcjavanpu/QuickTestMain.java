package bslsjdk.mcjavanpu;

public final class QuickTestMain {
    private QuickTestMain() {}

    public static void main(String[] args) {
        System.out.println("=== MCJavaNPU QUICK TEST ===");
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        System.out.println("java.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        long t0 = System.nanoTime();
        try {
            NpuRuntime.init();
            long initMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.println("INIT_AVAILABLE=" + NpuRuntime.isAvailable());
            System.out.println("INIT_TIME_MS=" + initMs);
            System.out.println("DEVICE_INFO=" + NpuRuntime.getLoadError());
            System.out.println("--- DIAGNOSTICS ---");
            System.out.print(NpuRuntime.getDiagnostics());
            System.out.println("--- END DIAGNOSTICS ---");

            if (!NpuRuntime.isAvailable()) {
                System.out.println("RESULT=HTP_INIT_FAIL");
                System.exit(2);
            }

            long t1 = System.nanoTime();
            NpuRuntime.TestResult result = NpuRuntime.test();
            long testMs = (System.nanoTime() - t1) / 1_000_000L;
            System.out.println("GRAPH_TEST_TIME_MS=" + testMs);
            System.out.println("GRAPH_TEST=" + result.success());
            System.out.println("GRAPH_TEST_NAME=" + result.name());
            System.out.println("GRAPH_TEST_DETAIL=" + result.detail());
            System.out.println("RESULT=" + (result.success()
                    ? "NPU_EXECUTION_VERIFIED"
                    : "HTP_INIT_OK_GRAPH_TEST_FAIL"));
            System.exit(result.success() ? 0 : 3);
        } catch (Throwable t) {
            t.printStackTrace(System.out);
            System.out.println("RESULT=JAVA_FATAL");
            System.exit(10);
        }
    }
}
