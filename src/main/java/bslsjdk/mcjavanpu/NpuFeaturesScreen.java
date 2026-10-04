package bslsjdk.mcjavanpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * One row per feature: switch, live counters, and a test button.
 *
 * The counters are the important part. A feature that shows calls=0 while the world
 * is loading is not wired into anything - that is the honest signal, and it is far
 * more useful than reading the code and hoping.
 */
public final class NpuFeaturesScreen extends Screen {

    private final Screen parent;
    private String status = "";
    private volatile boolean closed;

    public NpuFeaturesScreen(Screen parent) {
        super(Component.literal("NPU 功能与统计"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = 40;
        for (NpuStats.Feature f : NpuStats.ALL) {
            addRenderableWidget(Button.builder(
                    Component.literal((f.enabled ? "[开] " : "[关] ") + label(f.key)),
                    b -> { f.enabled = !f.enabled; this.rebuildWidgets(); this.status = f.key + " -> " + (f.enabled ? "on" : "off"); NpuLog.log("feature " + f.key + " -> " + f.enabled); })
                    .bounds(cx - 160, y, 150, 20).build());
            addRenderableWidget(Button.builder(Component.literal("测试"), b -> runTest(f))
                    .bounds(cx - 5, y, 60, 20).build());
            addRenderableWidget(Button.builder(Component.literal("清零"), b -> { f.calls.set(0); f.cells.set(0); f.npuUs.set(0); f.hostUs.set(0); this.status = f.key + " counters cleared"; })
                    .bounds(cx + 60, y, 60, 20).build());
            y += 24;
        }
        y += 8;
        NpuConfig cfg = NpuConfig.get();

        // Global switches. These decide whether the mod runs at all and whether it
        // is allowed to back off on its own, so they belong next to the features
        // rather than buried in a config file.
        addRenderableWidget(Button.builder(
                Component.literal((cfg.enabled ? "[开] " : "[关] ") + "总开关 enabled"),
                b -> { NpuConfig.get().toggle("enabled"); this.rebuildWidgets();
                       this.status = "enabled -> " + NpuConfig.get().enabled; })
                .bounds(cx - 160, y, 230, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(
                Component.literal((cfg.autoProbe ? "[开] " : "[关] ") + "自动探测 autoProbe"),
                b -> { NpuConfig.get().toggle("autoProbe"); this.rebuildWidgets();
                       this.status = "autoProbe -> " + NpuConfig.get().autoProbe
                               + " (next launch)"; })
                .bounds(cx - 160, y, 230, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(
                Component.literal((cfg.skyLight ? "[开] " : "[关] ") + "天空光 skyLight"),
                b -> { NpuConfig.get().toggle("skyLight"); this.rebuildWidgets();
                       this.status = "skyLight -> " + NpuConfig.get().skyLight
                               + " (近似算子，只提亮不调暗)"; })
                .bounds(cx - 160, y, 230, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(
                Component.literal((cfg.guardEnabled ? "[开] " : "[关] ") + "自适应降级 guard"),
                b -> { NpuConfig.get().toggle("guardEnabled"); this.rebuildWidgets();
                       this.status = "guardEnabled -> " + NpuConfig.get().guardEnabled; })
                .bounds(cx - 160, y, 230, 20).build());
        y += 24;

        // Guard state is the one thing worth showing live: if it has tripped, the
        // NPU is not paying for itself right now and every feature is on CPU.
        addRenderableWidget(Button.builder(
                Component.literal("守卫状态: " + (NpuGuard.isDegraded() ? "已降级" : "正常")
                        + (NpuLightAccel.zeroWrites() > 0
                                ? "  空写入 " + NpuLightAccel.zeroWrites() + "/" + 6 : "")),
                b -> { NpuGuard.reset(); this.rebuildWidgets(); this.status = "guard reset"; })
                .bounds(cx - 160, y, 230, 20).build());
        y += 24;

        addRenderableWidget(Button.builder(Component.literal("刷新"), b -> { this.status = NpuStats.report(); })
                .bounds(cx - 160, y + 8, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("返回"), b -> onClose())
                .bounds(cx + 60, y + 8, 100, 20).build());
        this.status = NpuStats.report().replace("\\n", "  |  ")
                + "  ||  " + NpuGuard.summary()
                + (NpuLightAccel.isNoEffect()
                        ? "  ||  light NO_EFFECT (0 writes, path disabled)" : "");
    }

    private static String label(String key) {
        switch (key) {
            case "light": return "光照传播";
            case "chunk": return "区块折叠";
            case "noise": return "噪声插值";
            case "blocks": return "方块批量";
            default: return key;
        }
    }

    private void runTest(NpuStats.Feature f) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null || mc.player.connection == null) {
                this.status = "not in a world";
                return;
            }
            String cmd;
            switch (f.key) {
                case "light": cmd = "npu lightapply 1"; break;
                case "chunk": cmd = "npu terrain 1"; break;
                case "noise": cmd = "npu noise 512"; break;
                default: cmd = "npu submit 256"; break;
            }
            Object conn = mc.player.connection;
            for (String m : new String[]{"sendCommand", "sendUnsignedCommand"}) {
                try { conn.getClass().getMethod(m, String.class).invoke(conn, cmd); this.status = "sent /" + cmd; return; }
                catch (NoSuchMethodException ignored) { }
            }
            this.status = "no sendCommand available";
        } catch (Throwable t) {
            this.status = "test failed: " + t.getMessage();
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = this.width / 2;
        g.text(this.font, this.title, cx - this.font.width(this.title) / 2, 16, 0xFFFFFFFF, true);
        int y = 40;
        for (NpuStats.Feature f : NpuStats.ALL) {
            String s = String.format(java.util.Locale.ROOT, "calls=%d cells=%d npu=%.0fms host=%.0fms x%.2f",
                    f.calls.get(), f.cells.get(), f.npuUs.get() / 1000.0, f.hostUs.get() / 1000.0, f.speedup());
            g.text(this.font, Component.literal(s), cx + 130, y + 6, 0xFFAAAAAA, false);
            y += 24;
        }
        String st = status == null ? "" : status;
        g.text(this.font, Component.literal(st.length() > 110 ? st.substring(0, 110) + "…" : st), 12, this.height - 40, 0xFFCCCCCC, false);
        g.text(this.font, Component.literal("NPU busy total: " + NpuStats.totalNpuMs() + " ms"), 12, this.height - 26, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() {
        closed = true;
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
