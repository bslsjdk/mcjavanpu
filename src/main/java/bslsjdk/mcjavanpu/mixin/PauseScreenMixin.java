package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "init", at = @At("TAIL"))
    private void mcjavanpu$addButton(CallbackInfo ci) {
        PauseScreen self = (PauseScreen) (Object) this;
        int x = self.width / 2 - 100;
        int y = self.height / 4 + 112;
        ((ScreenAccessor) self).mcjavanpu$addRenderableWidget(
            Button.builder(net.minecraft.network.chat.Component.literal("NPU 设置"), b ->
                Minecraft.getInstance().gui.setScreen(new NpuScreen()))
            .bounds(x, y, 200, 20).build());
    }
}
