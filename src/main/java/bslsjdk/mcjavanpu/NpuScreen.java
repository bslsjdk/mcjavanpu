package bslsjdk.mcjavanpu;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class NpuScreen extends Screen {
    private String status = "正在读取 NPU 状态…";
    private String detail = "";

    public NpuScreen() { super(Component.literal("MC Java NPU 设置")); }

    @Override protected void init() {
        int cx = width / 2, y = height / 2 - 70;
        addRenderableWidget(Button.builder(Component.literal("刷新状态"), b -> refresh()).bounds(cx-100,y,200,20).build());
        addRenderableWidget(Button.builder(Component.literal("NPU 测试"), b -> runTest()).bounds(cx-100,y+26,200,20).build());
        addRenderableWidget(Button.builder(Component.literal("性能测试"), b -> runBenchmark()).bounds(cx-100,y+52,200,20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"), b -> onClose()).bounds(cx-100,y+78,200,20).build());
        refresh();
    }

    private void refresh() {
        boolean ok=NpuRuntime.isAvailable();
        status=ok ? "NPU：已连接" : "NPU：未连接";
        detail=ok ? NpuRuntime.getDeviceInfo() : NpuRuntime.getLoadError();
    }
    private void runTest() {
        NpuRuntime.TestResult r=NpuRuntime.test();
        status=r.success() ? "NPU 测试：通过" : "NPU 测试：失败";
        detail=r.detail();
    }
    private void runBenchmark() {
        NpuRuntime.TestResult r=NpuRuntime.benchmark();
        status=r.success() ? "性能测试：完成" : "性能测试：失败";
        detail=r.detail();
    }

    @Override public void render(GuiGraphics g,int mouseX,int mouseY,float partialTick) {
        renderBackground(g,mouseX,mouseY,partialTick);
        int cx=width/2;
        g.drawCenteredString(font,title,cx,35,0xFFFFFF);
        g.drawCenteredString(font,Component.literal(status),cx,58,0xFFFFFF);
        String s=detail==null?"":detail;
        if(s.length()>100)s=s.substring(0,100)+"…";
        g.drawCenteredString(font,Component.literal(s),cx,185,0xAAAAAA);
        super.render(g,mouseX,mouseY,partialTick);
    }
    @Override public void onClose(){ Minecraft.getInstance().setScreen(null); }
}