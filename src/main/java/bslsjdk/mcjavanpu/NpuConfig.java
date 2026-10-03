package bslsjdk.mcjavanpu;

import net.fabricmc.loader.api.FabricLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Runtime switches, stored in config/mcjavanpu.properties.
 *
 *  enabled    - master switch (the in-game screen toggles this too)
 *  autoWarmup - build + calibrate the graphs in the background at world load,
 *               so the very first real use is not the slow path
 *  debugLog   - log every call instead of only summaries
 *  lightBatch - how many 8x8x8 blocks go into one propagation batch
 */
public final class NpuConfig {

    public boolean enabled = true;
    public boolean autoWarmup = true;
    public boolean debugLog = false;
    public int lightBatch = 128;

    private static NpuConfig INSTANCE;

    public static synchronized NpuConfig get() {
        if (INSTANCE == null) { INSTANCE = new NpuConfig(); INSTANCE.load(); }
        return INSTANCE;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcjavanpu.properties");
    }

    public synchronized void load() {
        try {
            Path p = file();
            if (!Files.exists(p)) { save(); return; }
            Properties pr = new Properties();
            try (var in = Files.newInputStream(p)) { pr.load(in); }
            enabled = Boolean.parseBoolean(pr.getProperty("enabled", "true"));
            autoWarmup = Boolean.parseBoolean(pr.getProperty("autoWarmup", "true"));
            debugLog = Boolean.parseBoolean(pr.getProperty("debugLog", "false"));
            lightBatch = Integer.parseInt(pr.getProperty("lightBatch", "128"));
            NpuLog.log("config loaded from " + p);
        } catch (Throwable t) {
            NpuLog.error("config load failed, using defaults", t);
        }
    }

    public synchronized void save() {
        try {
            Properties pr = new Properties();
            pr.setProperty("enabled", String.valueOf(enabled));
            pr.setProperty("autoWarmup", String.valueOf(autoWarmup));
            pr.setProperty("debugLog", String.valueOf(debugLog));
            pr.setProperty("lightBatch", String.valueOf(lightBatch));
            Path p = file();
            Files.createDirectories(p.getParent());
            try (var out = Files.newOutputStream(p)) {
                pr.store(out, "MCJavaNPU switches");
            }
            NpuLog.log("config saved to " + p);
        } catch (Throwable t) {
            NpuLog.error("config save failed", t);
        }
    }

    public synchronized void toggle(String key) {
        switch (key) {
            case "enabled": enabled = !enabled; break;
            case "autoWarmup": autoWarmup = !autoWarmup; break;
            case "debugLog": debugLog = !debugLog; break;
            default: return;
        }
        save();
        NpuLog.log("config toggled " + key + " -> " + describe());
    }

    public String describe() {
        return "enabled=" + enabled + " autoWarmup=" + autoWarmup
                + " debugLog=" + debugLog + " lightBatch=" + lightBatch;
    }
}
