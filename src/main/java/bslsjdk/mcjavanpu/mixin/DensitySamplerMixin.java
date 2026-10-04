package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuConfig;
import bslsjdk.mcjavanpu.NpuParity;
import bslsjdk.mcjavanpu.NpuLog;
import bslsjdk.mcjavanpu.NpuStats;
import bslsjdk.mcjavanpu.NpuTerrainAssist;
import bslsjdk.mcjavanpu.NpuTerrainLattice;
import bslsjdk.mcjavanpu.NpuTerrainGen;
import bslsjdk.mcjavanpu.NpuTerrainGate;
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
        // Proof of life. Mixin forbids non-private statics on this class, so the counter and the
        // one-shot log live in NpuStats. Seeing MIXIN_ACTIVE in the log is the difference between
        // "the hook works" and "we silently failed to inject and everything is vanilla".
        NpuStats.recordMixinSeen();
        NpuStats.announceMixinOnce();
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
            // Assist is not automatically safe. A cache hit here still replaces the
            // vanilla volume with whatever the interpreter produced, so it goes
            // through the same gate as takeover. When the gate refuses we do NOT
            // cancel, and vanilla generates the chunk exactly as it always would.
            if (!NpuTerrainGate.allowWrite(cx, cz, prepared, buffer.size())) {
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
            if (!NpuTerrainGate.allowWrite(cx, cz, mine, buffer.size())) {
                // Same reasoning as below: a refused write means vanilla should generate this
                // chunk, not that the worldgen thread should die.
                NpuStats.BLOCKS.record(0, 0, 0);
                NpuLog.error("TERRAIN_NPU_ONLY_FAIL gate rejected prepared volume at " + cx + "," + cz
                        + " - falling back to vanilla", null);
                return;
            }
            int n = Math.min(buffer.size(), mine.length);
            for (int i = 0; i < n; i++) buffer.set(i, mine[i]);
            NpuTerrainAssist.countTakeoverServed();
            NpuStats.BLOCKS.record(n, 0, 0);
            ci.cancel();
            return;
        }

        // Explicit NPU mode is intentionally fail-fast for testing: if the NPU result
        // is not ready, do not silently let vanilla fill this volume. That would make
        // the test indistinguishable from a successful NPU takeover.
        // "npu" used to mean TAKEOVER: the NPU owns the whole volume, and if its result was
        // not ready this threw and killed the worldgen thread. That is the honest definition of
        // takeover - all NPU, no fallback - but it is not something this codebase can deliver:
        // NpuTerrainVanilla.fill() contains no NPU call at all, so "takeover" was a CPU
        // interpreter wearing the name, with a crash as the failure mode.
        //
        // What we actually have is ASSIST: the NPU contributes the part it is good at (bulk
        // regular arithmetic - noise), and the CPU keeps everything branch- or state-dependent
        // (spline, range_choice, cache, carvers, surface rules). A miss is therefore normal and
        // must degrade to vanilla, never to a crash. Logging it once is how a genuinely broken
        // pipeline still gets noticed.
        if ("npu".equalsIgnoreCase(mode)) {
            NpuLog.error("TERRAIN_NPU_ONLY_FAIL no prepared NPU volume at " + cx + "," + cz
                    + " - this is assist, not takeover, so falling back to vanilla", null);
        }

        // If the gate would refuse the result anyway, do not spend anything producing
        // it. This is the difference between "the mod is a passive observer" and
        // "the mod is quietly evaluating terrain in the background for nothing".
        if (!NpuTerrainGate.worthComputing()) return;

        // Bounded and non-blocking: it either lands in the queue or is dropped. Either way this
        // call returns immediately.
        // 9x9 work set, not just this chunk. One request plans 81 chunks and the background batcher
        // executes them as many 4-chunk submissions, producing results continuously while the player
        // is still walking towards them. Queueing only the immediate neighbours left the prefetcher
        // with nothing to do between two submissions.
        NpuTerrainAssist.requestWorkSet(cx, cz, sx, sy, sz, oy);
    }

    /**
     * Runs after vanilla has filled the buffer.
     *
     * This is the only moment when the buffer holds the game's own numbers and we can prove - or
     * fail to prove - that our interpreter reproduces them. NpuParity decides whether this chunk is
     * worth keeping; see that class for the budget and the decision rule. Deliberately passive: it
     * reads, never writes, and when the gate is open it does nothing at all.
     */
    @Inject(method = "sampleVolume", at = @At("RETURN"), require = 0)
    private void mcjavanpu$afterSampleVolume(DensityBuffer buffer, DensityVolume volume, CallbackInfo ci) {
        try {
            NpuParity.offer(buffer, volume);
        } catch (Throwable t) {
            NpuLog.error("parity offer failed", t);
        }
    }
}
