package bslsjdk.mcjavanpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Loads the native runtime supplied by the ZL2/FCL NativeLibPlugin when present.
 * Falls back to the bundled library for standalone use.
 */
public final class NativeLoader {
    private static final String LIB_NAME = "libmcjavanpu.so";
    private static final String PLUGIN_PATH_PROPERTY = "mcjavanpu.native";

    private NativeLoader() {}

    public static void load() throws IOException {
        String pluginPath = System.getProperty(PLUGIN_PATH_PROPERTY);
        if (pluginPath != null && !pluginPath.isBlank()) {
            Path path = Path.of(pluginPath).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) {
                throw new IOException("plugin native library not found: " + path);
            }

            System.out.println("[MCJavaNPU] native source=FCLNativePlugin path=" + path);
            System.load(path.toString());
            return;
        }

        loadBundled();
    }

    private static void loadBundled() throws IOException {
        String arch = normalizeArch(System.getProperty("os.arch", ""));
        String resource = "/natives/" + arch + "/" + LIB_NAME;

        try (InputStream in = NativeLoader.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("native library not bundled: " + resource);
            }

            String tmpProperty = System.getProperty("java.io.tmpdir");
            if (tmpProperty == null || tmpProperty.isBlank()) {
                throw new IOException("java.io.tmpdir is unavailable");
            }

            Path root = Path.of(tmpProperty).toAbsolutePath().normalize();
            Files.createDirectories(root);

            Path dir = Files.createTempDirectory(root, "mcjavanpu-");
            Path target = dir.resolve(LIB_NAME);

            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);

            System.out.println("[MCJavaNPU] native source=bundled path=" + target);
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
