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
 *  autoProbe  - run the full diagnostic set unattended at game start and keep a
 *               rolling heartbeat. Results go to logs/mcjavanpu-npu.log. This is
 *               what makes the mod measurable without typing a command.
 *  guardEnabled - adaptive backoff. Watches the p99 cost of real calls and
 *               temporarily disables the NPU when it stops being worth it, then
 *               re-arms on its own. Protects the frame rate rather than the
 *               benchmark number.
 *  lightBatch - how many 8x8x8 blocks go into one propagation batch (legacy)
 *  lightMode - how light maths runs:
 *                 VANILLA - hands off, the game does exactly what it always did
 *                 NPU     - the NPU owns the propagation, the game's queues are skipped
 *                 ASSIST  - the NPU warms the field up, the game still finishes it
 *  chunkMode - same three-way choice for chunk work (noise, section fill)
 *  lightFoldRadius - chunk-fold radius. 0 = off, 1 = 3x3 sections (9), 2 = 5x5 (25),
 *                   3 = 7x7 (49). Bigger = fewer calls but a longer stall when it lands.
 */
public final class NpuConfig {

    public boolean enabled = true;
    public boolean autoWarmup = true;
    public boolean debugLog = false;

    /** Run the full diagnostic set by itself once the world is up. */
    public boolean autoProbe = true;
    /** Back off automatically when the NPU stops paying for itself. */
    public boolean guardEnabled = true;
    public int lightBatch = 128;
    public int lightFoldRadius = 1;

    /** vanilla | npu | assist */
    public String lightMode = "assist";
    /** vanilla | npu | assist */
    public String chunkMode = "assist";

    /** Canonical three-way modes. */
    public static final String[] MODES = {"vanilla", "npu", "assist"};

    /** Advances a mode field vanilla -> npu -> assist -> vanilla. */
    public static String nextMode(String cur) {
        for (int i = 0; i < MODES.length; i++) if (MODES[i].equalsIgnoreCase(cur)) return MODES[(i + 1) % MODES.length];
        return MODES[0];
    }

    public static String modeLabel(String m) {
        if ("npu".equalsIgnoreCase(m)) return "NPU 接管";
        if ("assist".equalsIgnoreCase(m)) return "NPU 辅助";
        return "原版";
    }

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
            autoProbe = Boolean.parseBoolean(pr.getProperty("autoProbe", "true"));
            guardEnabled = Boolean.parseBoolean(pr.getProperty("guardEnabled", "true"));
            lightBatch = Integer.parseInt(pr.getProperty("lightBatch", "128"));
            lightFoldRadius = Integer.parseInt(pr.getProperty("lightFoldRadius", "1"));
            // A radius of 3+ folds 49+ sections into one submit and can overrun the single
            // threaded service (observed as SocketTimeoutException on every later request).
            // 2 (5x5 = 25 sections) is the largest batch that stays comfortably inside the
            // IPC payload cap, so clamp here rather than trusting a hand-edited file.
            lightFoldRadius = Math.max(0, Math.min(2, lightFoldRadius));
            lightMode = pr.getProperty("lightMode", "assist");
            chunkMode = pr.getProperty("chunkMode", "assist");
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
            pr.setProperty("autoProbe", String.valueOf(autoProbe));
            pr.setProperty("guardEnabled", String.valueOf(guardEnabled));
            pr.setProperty("lightBatch", String.valueOf(lightBatch));
            pr.setProperty("lightFoldRadius", String.valueOf(lightFoldRadius));
            pr.setProperty("lightMode", lightMode);
            pr.setProperty("chunkMode", chunkMode);
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
            case "autoProbe": autoProbe = !autoProbe; break;
            case "guardEnabled": guardEnabled = !guardEnabled; break;
            case "lightFoldRadius": lightFoldRadius = (lightFoldRadius + 1) % 5; break;
            case "lightMode": lightMode = nextMode(lightMode); break;
            case "chunkMode": chunkMode = nextMode(chunkMode); break;
            default: return;
        }
        save();
        NpuLog.log("config toggled " + key + " -> " + describe());
    }

    public String describe() {
        return "enabled=" + enabled + " autoWarmup=" + autoWarmup
                + " debugLog=" + debugLog + " autoProbe=" + autoProbe
                + " guardEnabled=" + guardEnabled
                + " lightBatch=" + lightBatch
                + " lightFoldRadius=" + lightFoldRadius
                + " lightMode=" + lightMode + " chunkMode=" + chunkMode;
    }
}
