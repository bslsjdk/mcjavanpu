package bslsjdk.mcjavanpu;

/**
 * Small backend contract inspired by the Terrain Diffusion integration:
 * initialize once, keep the execution backend alive, and perform inference
 * through a stable run() entry point.
 *
 * MCJavaNPU's implementation is QNN HTP rather than ONNX Runtime EP.
 */
public interface NpuBackend extends AutoCloseable {
    String name();
    boolean initialize();
    boolean isReady();
    TestResult runSmokeTest();
    String diagnostics();
    @Override void close();

    record TestResult(boolean success, String detail) {}
}
