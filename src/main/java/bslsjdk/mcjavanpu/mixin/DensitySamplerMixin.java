package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuTerrainHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Minecraft's density volume sampling.
 *
 * The target is the bound sampler, because that is where a whole volume gets filled
 * in one go - the batch unit the NPU needs.
 *
 * require = 0 and a failure-tolerant body on purpose: this is deep terrain machinery
 * and a version bump must cost us the hook, not the world.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.densityfunction.DensitySampler$Bound")
public abstract class DensitySamplerMixin {

    @Inject(method = "sampleVolume", at = @At("HEAD"), require = 0)
    private void mcjavanpu$onSampleVolume(Object context, int x, int y, int z, CallbackInfo ci) {
        NpuTerrainHook.onVolume(x, y, z);
    }
}
