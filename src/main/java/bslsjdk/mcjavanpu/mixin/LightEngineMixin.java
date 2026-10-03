package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuLightHook;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hooks the one place that sees every light update: LightEngine.runLightUpdates().
 *
 * require = 0 on purpose. This is a hot, game-critical path and a version bump could
 * rename or reshape it; if the injection cannot be applied the mod should silently
 * lose its hook rather than take the world down with it.
 */
@Mixin(LightEngine.class)
public abstract class LightEngineMixin {

    @Inject(method = "runLightUpdates", at = @At("HEAD"), require = 0)
    private void mcjavanpu$onRunLightUpdates(CallbackInfoReturnable<Integer> cir) {
        NpuLightHook.onLightUpdate();
    }
}
