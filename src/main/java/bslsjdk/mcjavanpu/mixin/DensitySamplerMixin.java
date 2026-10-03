package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuConfig;
import bslsjdk.mcjavanpu.NpuStats;
import bslsjdk.mcjavanpu.NpuTerrainGen;
import bslsjdk.mcjavanpu.NpuTerrainHook;
import net.minecraft.world.level.levelgen.densityfunction.DensityBuffer;
import net.minecraft.world.level.levelgen.densityfunction.DensityVolume;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hooks Minecraft density volume sampling.
 *
 * The target is the bound sampler: one call here fills an entire chunk volume, which is
 * exactly the batch unit the NPU wants. chunkVolume() builds it as 16 x height x 16 with
 * step 1 (98304 points for a full overworld chunk), so one call == one chunk of terrain.
 *
 * chunkMode == npu -> the NPU generates the whole density field, vanilla sampler skipped.
 * anything else    -> observation only, the game does its own work unchanged.
 *
 * require = 0 and a failure-tolerant body on purpose: this is deep terrain machinery, so a
 * version bump must cost us the hook, not the world.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.densityfunction.DensitySampler$Bound")
public abstract class DensitySamplerMixin {

    @Inject(method = "sampleVolume", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcjavanpu$onSampleVolume(DensityBuffer buffer, DensityVolume volume, CallbackInfo ci) {
        if (volume == null || buffer == null) return;
        NpuTerrainHook.onVolumeShape(volume.sizeX(), volume.sizeY(), volume.sizeZ());

        if (!NpuConfig.get().enabled) return;
        if (!"npu".equalsIgnoreCase(NpuConfig.get().chunkMode)) return;
        if (!NpuStats.BLOCKS.enabled) return;

        // Seed from the chunk anchor: neighbours differ, repeats stay stable.
        long seed = volume.minBlockX() * 341873128712L ^ volume.minBlockZ() * 132897987541L
                  ^ volume.minBlockY() * 42317861L;

        long t0 = System.nanoTime();
        NpuTerrainGen.Result r = NpuTerrainGen.generate(
                volume.sizeX(), volume.sizeY(), volume.sizeZ(),
                volume.minBlockX(), volume.minBlockY(), volume.minBlockZ(), seed);
        long wallUs = (System.nanoTime() - t0) / 1000;

        if (!r.usedNpu) {
            // Never take the world down with us: fall through to the vanilla sampler.
            NpuStats.BLOCKS.record(buffer.size(), 0, wallUs);
            return;
        }

        int n = Math.min(buffer.size(), r.density.length);
        for (int i = 0; i < n; i++) buffer.set(i, r.density[i]);
        NpuStats.BLOCKS.record(n, r.npuUs, wallUs);
        ci.cancel();
    }
}
