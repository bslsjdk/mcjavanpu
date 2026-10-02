package bslsjdk.mcjavanpu;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled native library to the JVM's private temporary directory.
 * Android/ZL2 does not allow the JVM linker namespace to load native libraries
 * directly from shared external storage.
 */
public final class NativeLoader {
    private static final String LIB_NAME = "libmcjavanpu.so";

    private NativeLoader() {}

    public static void load() throws IOException {
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

            // Keep the file in the JVM-private directory for the lifetime of the
            // process. This avoids Android linker namespace issues and makes the
            // native path stable for debugging.
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
