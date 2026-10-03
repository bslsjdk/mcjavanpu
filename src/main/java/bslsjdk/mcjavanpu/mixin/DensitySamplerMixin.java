package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuTerrainHook;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
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
    private void mcjavanpu$onSampleVolume(DensityBuffer buffer, DensityVolume volume, CallbackInfo ci) {
        if (volume == null) return;
        // The real signature is sampleVolume(DensityBuffer, DensityVolume) and it returns
        // void. This is the single call that fills a whole chunk's worth of samples, so it
        // is the batch unit: chunkVolume() builds it as 16 x height x 16 with step 1, i.e.
        // 98304 points for a full-overworld chunk. Reported so the number is measured in a
        // live game rather than assumed from a toolchain.
        NpuTerrainHook.onVolumeShape(volume.sizeX(), volume.sizeY(), volume.sizeZ());
    }
}
