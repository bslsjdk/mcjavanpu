package bslsjdk.mcjavanpu;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Extracts the bundled native library to Fabric's config directory and loads it.
 * Stage 0 targets Android/arm64-v8a; desktop fallback is intentionally unsupported.
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

            Path dir = FabricLoader.getInstance().getConfigDir().resolve("mcjavanpu/native");
            Files.createDirectories(dir);

            Path target = dir.resolve(LIB_NAME);
            Path temp = dir.resolve(LIB_NAME + ".tmp");

            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                System.load(temp.toAbsolutePath().toString());
            } finally {
                Files.deleteIfExists(temp);
            }
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
