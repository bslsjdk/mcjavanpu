package bslsjdk.mcjavanpu;

public final class QuickTestMain {
    private QuickTestMain() {}

    public static void main(String[] args) {
        System.out.println("=== MCJavaNPU QUICK TEST ===");
        System.out.println("java.version=" + System.getProperty("java.version"));
        System.out.println("os.arch=" + System.getProperty("os.arch"));
        System.out.println("java.io.tmpdir=" + System.getProperty("java.io.tmpdir"));
        configure(args);
        System.out.println("TUNING=" + System.getProperty("mcjavanpu.logLevel", "DEBUG")
                + ";deviceRetries=" + System.getProperty("mcjavanpu.deviceRetries", "0")
                + ";adspExtra=" + System.getProperty("mcjavanpu.adspExtra", "<none>"));
        long t0 = System.nanoTime();
        try {
            NpuRuntime.init();
            long initMs = (System.nanoTime() - t0) / 1_000_000L;
            System.out.println("INIT_AVAILABLE=" + NpuRuntime.isAvailable());
            System.out.println("INIT_TIME_MS=" + initMs);
            System.out.println("DEVICE_INFO=" + NpuRuntime.getDeviceInfo());
            System.out.println("--- DIAGNOSTICS ---");
            System.out.print(NpuRuntime.getDiagnostics());
            System.out.println("--- END DIAGNOSTICS ---");
            System.out.println("LOG_PATH=" + NpuRuntime.getLogPath());

            if (!NpuRuntime.isAvailable()) {
                System.out.println("RESULT=HTP_INIT_FAIL");
                System.exit(2);
            }

            long t1 = System.nanoTime();
            int repeat = Integer.getInteger("mcjavanpu.repeat", 1);
            NpuRuntime.TestResult result = NpuRuntime.test();
            for (int i = 1; i < repeat && result.success(); i++) {
                result = NpuRuntime.test();
            }
            System.out.println("GRAPH_TEST_REPEAT=" + repeat);
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

    private static void configure(String[] args) {
        for (String arg : args) {
            if (arg.equals("--verbose")) System.setProperty("mcjavanpu.logLevel", "DEBUG");
            else if (arg.equals("--info")) System.setProperty("mcjavanpu.logLevel", "INFO");
            else if (arg.equals("--retries=1")) System.setProperty("mcjavanpu.deviceRetries", "1");
            else if (arg.equals("--retries=2")) System.setProperty("mcjavanpu.deviceRetries", "2");
            else if (arg.equals("--retries=3")) System.setProperty("mcjavanpu.deviceRetries", "3");
            else if (arg.startsWith("--repeat=")) System.setProperty("mcjavanpu.repeat", arg.substring("--repeat=".length()));
            else if (arg.startsWith("--adsp-extra=")) System.setProperty("mcjavanpu.adspExtra", arg.substring("--adsp-extra=".length()));
        }
    }
}
