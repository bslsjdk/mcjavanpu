package bslsjdk.mcjavanpu;

import net.fabricmc.loader.api.FabricLoader;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/** Dedicated log for the NPU bridge: startup, timings and every error. */
public final class NpuLog {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final Object LOCK = new Object();
    private static Writer writer;
    private static Path path;
    private static boolean disabled;

    private NpuLog() {}

    public static void log(String m) { write("INFO", m); }
    public static void warn(String m) { write("WARN", m); }
    public static void error(String m) { write("ERROR", m); }
    public static void error(String m, Throwable t) {
        write("ERROR", m + " :: " + (t == null ? "null" : t.getClass().getSimpleName() + ": " + t.getMessage()));
    }

    private static void write(String lv, String m) {
        String line = "[" + LocalTime.now().format(TS) + "] [" + lv + "] " + m;
        System.out.println("[MCJavaNPU] " + line);
        if (disabled) return;
        synchronized (LOCK) {
            if (writer == null && !openLocked()) return;
            try { writer.write(line); writer.write("\n"); writer.flush(); }
            catch (Throwable t) { disabled = true; }
        }
    }

    private static boolean openLocked() {
        try {
            Path dir = FabricLoader.getInstance().getGameDir().resolve("logs");
            Files.createDirectories(dir);
            path = dir.resolve("mcjavanpu-npu.log");
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(path.toFile(), true), StandardCharsets.UTF_8));
            writer.write("\n" + "==== session " + LocalDateTime.now() + " ====" + "\n");
            writer.flush();
            return true;
        } catch (Throwable t) { disabled = true; return false; }
    }

    public static String getPathString() { return path == null ? "(not open)" : path.toString(); }
}
