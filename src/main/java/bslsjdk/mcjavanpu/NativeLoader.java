package bslsjdk.mcjavanpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Extracts the native MCJavaNPU library and the complete bundled QNN HTP
 * userspace stack into one private directory, then loads the JNI library.
 */
public final class NativeLoader {
    private static final String LIB_NAME = "libmcjavanpu.so";
    private static final String PLUGIN_PATH_PROPERTY = "mcjavanpu.native";

    private static final List<String> QNN_LIBS = List.of(
            "libc++_shared.so",
            "libQnnSystem.so",
            "libQnnHtp.so",
            "libQnnHtpPrepare.so",
            "libQnnHtpV73Stub.so",
            "libQnnHtpV73Skel.so"
    );

    private NativeLoader() {}

    public static void load() throws IOException {
        String pluginPath = System.getProperty(PLUGIN_PATH_PROPERTY);
        if (pluginPath != null && !pluginPath.isBlank()) {
            Path path = Path.of(pluginPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("plugin native library not found: " + path);
            }

            System.out.println("[MCJavaNPU] native source=external path=" + path);
            System.load(path.toString());
            return;
        }

        loadBundled();
    }

    private static void loadBundled() throws IOException {
        String arch = normalizeArch(System.getProperty("os.arch", ""));
        String base = "/natives/" + arch + "/";

        try (InputStream in = NativeLoader.class.getResourceAsStream(base + LIB_NAME)) {
            if (in == null) {
                throw new IOException("native library not bundled: " + base + LIB_NAME);
            }

            String tmpProperty = System.getProperty("java.io.tmpdir");
            if (tmpProperty == null || tmpProperty.isBlank()) {
                throw new IOException("java.io.tmpdir is unavailable");
            }

            Path root = Path.of(tmpProperty).toAbsolutePath().normalize();
            Files.createDirectories(root);

            Path dir = Files.createTempDirectory(root, "mcjavanpu-");

            // Keep every host-side QNN dependency beside libmcjavanpu.so.
            // The HTP V73 Skel is also extracted here so ADSP_LIBRARY_PATH
            // can expose it to the FastRPC loader, but it is NOT dlopen'ed
            // into the host process.
            for (String lib : QNN_LIBS) {
                String resource = base + "qnn/" + lib;
                try (InputStream qnn = NativeLoader.class.getResourceAsStream(resource)) {
                    if (qnn == null) {
                        throw new IOException("QNN library not bundled: " + resource);
                    }
                    Files.copy(qnn, dir.resolve(lib), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            Path target = dir.resolve(LIB_NAME);
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);

            System.out.println("[MCJavaNPU] native source=bundled path=" + target);
            System.out.println("[MCJavaNPU] qnn dir=" + dir);

            // The JNI library has libc++_shared.so as a DT_NEEDED dependency.
            // Load it from the same extracted directory before the JNI library.
            System.load(dir.resolve("libc++_shared.so").toString());
            System.load(target.toString());
        }
    }

    private static String normalizeArch(String arch) {
        String a = arch.toLowerCase();
        if (a.equals("aarch64") || a.equals("arm64") || a.equals("arm64-v8a")) {
            return "arm64-v8a";
        }
        throw new IllegalStateException("unsupported native architecture: " + arch);
    }
}
