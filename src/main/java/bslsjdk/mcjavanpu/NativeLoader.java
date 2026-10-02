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
 *
 * The launcher may leave an old mcjavanpu.native property behind from an
 * earlier test build. Never let a differently named native library satisfy
 * the current Java JNI contract.
 */
public final class NativeLoader {
    private static final String LIB_NAME = "libmcjavanpu.so";
    private static final String PLUGIN_PATH_PROPERTY = "mcjavanpu.native";
    private static final String FORCE_BUNDLED_PROPERTY = "mcjavanpu.forceBundled";

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
        boolean forceBundled = Boolean.parseBoolean(
                System.getProperty(FORCE_BUNDLED_PROPERTY, "true"));

        if (!forceBundled && pluginPath != null && !pluginPath.isBlank()) {
            Path path = Path.of(pluginPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("plugin native library not found: " + path);
            }

            String fileName = path.getFileName().toString();
            if (!LIB_NAME.equals(fileName)) {
                System.out.println("[MCJavaNPU] ignoring stale external native=" + path
                        + " expected=" + LIB_NAME);
            } else {
                System.out.println("[MCJavaNPU] native source=external path=" + path);
                System.load(path.toString());
                return;
            }
        } else if (pluginPath != null && !pluginPath.isBlank()) {
            System.out.println("[MCJavaNPU] external native override disabled; bundled runtime selected");
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

            // Keep the complete HTP V73 userspace stack together.
            // The Skel is for the DSP/FastRPC side and is deliberately not
            // dlopen'ed into the ARM64 host process.
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
            System.out.println("[MCJavaNPU] qnn skel=" + dir.resolve("libQnnHtpV73Skel.so"));

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
