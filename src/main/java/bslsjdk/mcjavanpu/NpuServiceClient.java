package bslsjdk.mcjavanpu;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class NpuServiceClient {
    private static final String NATIVE_NAME = "mcjavanpu_native";
    private static volatile String nativeLoadError;

    static {
        loadNative();
    }

    private NpuServiceClient() {}

    private static void loadNative() {
        try {
            try {
                System.loadLibrary(NATIVE_NAME);
                return;
            } catch (Throwable ignored) {
            }

            String arch = System.getProperty("os.arch", "").toLowerCase();
            String resource;
            if (arch.contains("aarch64") || arch.contains("arm64")) {
                resource = "/natives/arm64-v8a/libmcjavanpu_native.so";
            } else {
                nativeLoadError = "unsupported_arch_" + arch;
                return;
            }

            try (InputStream in = NpuServiceClient.class.getResourceAsStream(resource)) {
                if (in == null) {
                    nativeLoadError = "missing_native_resource";
                    return;
                }
                Path tmp = Files.createTempFile("mcjavanpu-", ".so");
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                tmp.toFile().deleteOnExit();
                System.load(tmp.toAbsolutePath().toString());
            }
        } catch (Throwable t) {
            nativeLoadError = t.getClass().getSimpleName() + ":" + String.valueOf(t.getMessage());
        }
    }

    private static native String nativeRequest(String command);

    private static native void nativeClose();

    public static String request(String command) {
        if (command == null || command.isEmpty()) return "ERR EMPTY_COMMAND";
        if (nativeLoadError != null) return "ERR NATIVE_UNAVAILABLE " + nativeLoadError;
        try {
            return nativeRequest(command);
        } catch (Throwable t) {
            return "ERR NATIVE_RUNTIME " + t.getClass().getSimpleName();
        }
    }

    public static void close() {
        if (nativeLoadError == null) {
            try { nativeClose(); } catch (Throwable ignored) {}
        }
    }

    public static boolean isAvailable() {
        return request("PING").startsWith("PONG MCNPU/");
    }

    public static String status() {
        return request("STATUS");
    }

    public static String smoke() {
        return request("SMOKE");
    }

    public static String capabilities() {
        return request("CAPABILITIES");
    }

    public static String add(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length || a.length > 1024)
            return "ERR ADD_SIZE";
        StringBuilder sa = new StringBuilder(), sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (!Float.isFinite(a[i]) || !Float.isFinite(b[i])) return "ERR ADD_NON_FINITE";
            if (i > 0) {
                sa.append(',');
                sb.append(',');
            }
            sa.append(Float.toString(a[i]));
            sb.append(Float.toString(b[i]));
        }
        return request("EXEC_ADD " + sa + "|" + sb);
    }
}
