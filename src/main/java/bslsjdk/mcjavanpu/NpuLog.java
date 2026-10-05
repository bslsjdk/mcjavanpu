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

    /**
     * Rate-limited logging for events that can fire per chunk.
     *
     * A world load calls the density hook hundreds of thousands of times, and every new
     * diagnostic added on that path inherits the rate. The first TAKEOVER MISS was useful;
     * 180000 of them cost more than the terrain they were describing, and buried the one
     * line that would have explained why.
     *
     * So: first occurrence in full, then at most one per interval, with the suppressed count
     * carried forward - otherwise a rate limit hides the magnitude of a problem that a single
     * sample cannot convey.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, long[]> THROTTLE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Default interval: one line per second per key. */
    public static final long DEFAULT_THROTTLE_MS = 1000L;

    /** Writes at most once per intervalMs for a given key. Returns true when it wrote. */
    public static boolean throttled(String key, long intervalMs, String m) {
        return throttled(key, intervalMs, m, "INFO");
    }

    public static boolean throttledWarn(String key, long intervalMs, String m) {
        return throttled(key, intervalMs, m, "WARN");
    }

    public static boolean throttledError(String key, long intervalMs, String m) {
        return throttled(key, intervalMs, m, "ERROR");
    }

    private static boolean throttled(String key, long intervalMs, String m, String level) {
        final long now = System.currentTimeMillis();
        long[] st = THROTTLE.get(key);
        if (st == null) {
            long[] created = new long[]{now, 0L};
            long[] prev = THROTTLE.putIfAbsent(key, created);
            st = (prev == null) ? created : prev;
        }
        synchronized (st) {
            if (st[0] != now && now - st[0] < intervalMs) {
                st[1]++;
                return false;
            }
            st[0] = now;
            long suppressed = st[1];
            st[1] = 0L;
            write(level, suppressed > 0 ? m + " (+" + suppressed + " suppressed)" : m);
            return true;
        }
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
