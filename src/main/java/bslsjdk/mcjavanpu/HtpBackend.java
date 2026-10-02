package bslsjdk.mcjavanpu;

/**
 * QNN HTP execution backend.
 *
 * Java callers use this stable backend instead of knowing QNN's
 * provider/device/context lifecycle.
 */
public final class HtpBackend implements NpuBackend {
    private static final HtpBackend INSTANCE = new HtpBackend();

    private HtpBackend() {}

    public static HtpBackend getInstance() {
        return INSTANCE;
    }

    @Override
    public String name() {
        return "QNN-HTP-V73";
    }

    @Override
    public boolean initialize() {
        return NpuRuntime.initInternal();
    }

    @Override
    public boolean isReady() {
        return NpuRuntime.isAvailable();
    }

    @Override
    public TestResult runSmokeTest() {
        NpuRuntime.TestResult result = NpuRuntime.testInternal();
        return new TestResult(result.success(), result.detail());
    }

    @Override
    public String diagnostics() {
        return NpuRuntime.getDiagnostics();
    }

    @Override
    public void close() {
        NpuRuntime.shutdownInternal();
    }
}
