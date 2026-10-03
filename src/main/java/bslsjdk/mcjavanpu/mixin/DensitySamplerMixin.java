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

        // Takeover: generate here and now, vanilla never runs.
        //
        // Batching is not optional here. One submission costs ~50 ms of fixed overhead
        // (IPC round trip, graph lookup, QNN dispatch) regardless of how tiny the matrices are,
        // and the volume of one chunk is 225 lattice points - far too little work to amortise
        // that. The element budget allows exactly four chunks per call (4 x 225 x 16 = 14400 of
        // 16384), so every miss generates the current chunk plus its three neighbours, serves
        // this one immediately, and parks the rest. Chunk number two through four of the same
        // batch then cost a map lookup instead of another 50 ms.
        float[] parked = NpuTerrainAssist.peekTakeover(cx, cz, oy);
        if (parked != null) {
            int np = Math.min(buffer.size(), parked.length);
            for (int i = 0; i < np; i++) buffer.set(i, parked[i]);
            NpuTerrainAssist.countTakeoverServed();
            NpuStats.BLOCKS.record(np, 0, 0);
            ci.cancel();
            return;
        }

        float[] mine = null;
        long npuUs = 0, interpUs = 0;
        long t0 = System.nanoTime();

        int batchSize = Math.min(BATCH_SIDE * BATCH_SIDE,
                NpuTerrainLattice.maxChunksPerSubmit(sx, sy, sz) * BATCH_SIDE * BATCH_SIDE);
        batchSize = Math.max(1, Math.min(batchSize, 4));
        int[] cxs = new int[batchSize];
        int[] czs = new int[batchSize];
        int[] oys = new int[batchSize];
        long[] seeds = new long[batchSize];
        for (int b = 0; b < batchSize; b++) {
            int bx = cx + (b % BATCH_SIDE);
            int bz = cz + (b / BATCH_SIDE);
            cxs[b] = bx; czs[b] = bz; oys[b] = oy;
            long bx0 = bx << 4, bz0 = bz << 4;
            seeds[b] = bx0 * 341873128712L ^ bz0 * 132897987541L ^ oy * 42317861L;
        }
        long[] on = new long[1], op = new long[1], oi = new long[1];
        float[][] batch = NpuTerrainLattice.generateMulti(batchSize, cxs, czs, oys, seeds,
                sx, sy, sz, on, op, oi);
        long wallUs = (System.nanoTime() - t0) / 1000;
        npuUs = on[0]; interpUs = oi[0];
        if (batch.length > 0) mine = batch[0];

        if (mine == null) {
            // Never take the world down with us: fall through to the vanilla sampler.
            NpuStats.BLOCKS.record(buffer.size(), 0, wallUs);
            return;
        }

        for (int b = 1; b < batch.length; b++) {
            NpuTerrainAssist.put(cxs[b], czs[b], oys[b], batch[b]);
        }
        NpuTerrainAssist.countTakeoverBatch();

        int n = Math.min(buffer.size(), mine.length);
        for (int i = 0; i < n; i++) buffer.set(i, mine[i]);
        NpuStats.BLOCKS.record(n, npuUs + interpUs, wallUs);
        ci.cancel();
    }
}
