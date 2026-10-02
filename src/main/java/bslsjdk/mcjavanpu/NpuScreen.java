package bslsjdk.mcjavanpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class NpuScreen extends Screen {
    private String status = "正在读取 NPU 状态…";
    private String detail = "";
    private int scroll = 0;

    public NpuScreen() {
        super(Component.literal("MC Java NPU 设置"));
    }

    @Override
    protected void init() {
        int cx = this.width / 2;
        int y = this.height / 2 - 60;

        addRenderableWidget(Button.builder(Component.literal("刷新状态"), b -> refresh())
                .bounds(cx - 100, y, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("NPU 测试"), b -> runTest())
                .bounds(cx - 100, y + 26, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("服务性能测试"), b -> runBenchmark())
                .bounds(cx - 100, y + 52, 200, 20).build());

        addRenderableWidget(Button.builder(Component.literal("上一页"), b -> { scroll = Math.max(0, scroll - 10); })
                .bounds(cx - 205, y + 78, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("下一页"), b -> { scroll += 10; })
                .bounds(cx - 100, y + 78, 100, 20).build());

        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose())
                .bounds(cx + 5, y + 78, 100, 20).build());

        refresh();
    }

    private void refresh() {
        new Thread(() -> {
            String s = NpuServiceClient.status();
            this.minecraft.execute(() -> {
                boolean available = s.startsWith("QNN HTP ready");
                status = available ? "NPU：服务已连接" : "NPU：服务未连接";
                detail = s;
                scroll = 0;
            });
        }, "mcnpu-status").start();
    }

    private void runTest() {
        new Thread(() -> {
            String r = NpuServiceClient.smoke();
            this.minecraft.execute(() -> {
                boolean ok = r.startsWith("OK ");
                status = ok ? "NPU 测试：通过" : "NPU 测试：失败";
                detail = r;
            });
        }, "mcnpu-smoke").start();
    }

    private void runBenchmark() {
        runTest();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        int cx = this.width / 2;
        graphics.text(this.font, this.title, cx - this.font.width(this.title) / 2, 35, 0xFFFFFFFF, true);
        graphics.text(this.font, Component.literal(status), cx - this.font.width(status) / 2, 58, 0xFFFFFFFF, true);

        String shown = detail == null ? "" : detail;
        String[] lines = shown.split("\\R");
        int maxLines = 13;
        int start = Math.min(scroll, Math.max(0, lines.length - maxLines));
        for (int i = 0; i < maxLines && start + i < lines.length; i++) {
            String line = lines[start + i];
            if (line.length() > 92) line = line.substring(0, 92) + "…";
            graphics.text(this.font, Component.literal(line), 12, 205 + i * 9, 0xFFAAAAAA, false);
        }
        graphics.text(this.font,
                Component.literal("诊断行 " + (lines.length == 0 ? 0 : start + 1) + "-" + Math.min(lines.length, start + maxLines) + "/" + lines.length),
                12, 330, 0xFFFFFFFF, false);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(null);
    }
}