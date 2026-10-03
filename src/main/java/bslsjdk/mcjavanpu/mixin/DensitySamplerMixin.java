package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuConfig;
import bslsjdk.mcjavanpu.NpuStats;
import bslsjdk.mcjavanpu.NpuTerrainAssist;
import bslsjdk.mcjavanpu.NpuTerrainLattice;
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

    /**
     * Side of the chunk square generated per submission.
     *
     * Two by two is what the element budget supports honestly (4 x 225 x 16 = 14400 <= 16384),
     * and it matches how chunks actually load: the player walks, and the next chunk is a
     * neighbour, not something twenty chunks away.
     */
    private static final int BATCH_SIDE = 2;

    @Inject(method = "sampleVolume", at = @At("HEAD"), cancellable = true, require = 0)
    private void mcjavanpu$onSampleVolume(DensityBuffer buffer, DensityVolume volume, CallbackInfo ci) {
        if (volume == null || buffer == null) return;
        NpuTerrainHook.onVolumeShape(volume.sizeX(), volume.sizeY(), volume.sizeZ());

        if (!NpuConfig.get().enabled) return;

        final String mode = NpuConfig.get().chunkMode;
        final boolean takeover = "npu".equalsIgnoreCase(mode);
        final boolean assist = "assist".equalsIgnoreCase(mode);
        if (!takeover && !assist) return;
        if (!NpuStats.BLOCKS.enabled) return;

        int sx = volume.sizeX(), sy = volume.sizeY(), sz = volume.sizeZ();
        int ox = volume.minBlockX(), oy = volume.minBlockY(), oz = volume.minBlockZ();
        int cx = ox >> 4, cz = oz >> 4;

        if (assist) {
            // Assist = the work was done earlier on a background thread.
            //
            // A hit costs a memory copy and vanilla never runs for this chunk.
            // A miss falls straight through to the vanilla sampler, which is exactly what would
            // have happened without us, plus a prefetch request so the NEXT time this chunk is
            // touched (or its neighbours) we may hit. Nothing here ever waits on the NPU, which
            // is the whole point: an assist that can stall is worse than no assist.
            float[] prepared = NpuTerrainAssist.take(cx, cz, sx, sy, sz, oy);
            if (prepared == null) {
                NpuStats.BLOCKS.record(0, 0, 0);
                return;
            }
            int n = Math.min(buffer.size(), prepared.length);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) buffer.set(i, prepared[i]);
            long us = (System.nanoTime() - t0) / 1000;
            NpuStats.BLOCKS.record(n, 0, us);
            ci.cancel();
            return;
        }

        // Takeover, done the only way it can be done without stuttering.
        //
        // The previous version generated the chunk inline. That was wrong, and the symptom was
        // exactly what the player reported: a hitch every time new terrain appeared. One inline
        // submission costs ~50 ms of fixed overhead (IPC + graph lookup + QNN dispatch) and that
        // was being paid on the game thread, per chunk, in the middle of world generation.
        //
        // The rule is now absolute: the game thread never waits for the NPU. Look in the cache
        // first - if the background batcher already produced this volume, take it, and vanilla
        // never runs for this chunk (that is the "takeover" part). If it is not there yet, hand
        // the request to the background queue and fall through to vanilla. The next time this
        // terrain is touched the volume will be waiting, and by then the batcher has also done
        // the neighbours and the walk-ahead, so the hit rate rises as the player moves.
        float[] mine = NpuTerrainAssist.peekTakeover(cx, cz, oy);
        if (mine != null) {
            int n = Math.min(buffer.size(), mine.length);
            for (int i = 0; i < n; i++) buffer.set(i, mine[i]);
            NpuTerrainAssist.countTakeoverServed();
            NpuStats.BLOCKS.record(n, 0, 0);
            ci.cancel();
            return;
        }

        // Bounded and non-blocking: it either lands in the queue or is dropped. Either way this
        // call returns immediately.
        NpuTerrainAssist.request(cx, cz, sx, sy, sz, oy);
    }
}
