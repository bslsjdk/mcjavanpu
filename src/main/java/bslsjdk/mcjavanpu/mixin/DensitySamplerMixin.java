package bslsjdk.mcjavanpu.mixin;

import bslsjdk.mcjavanpu.NpuChunkWork;
import bslsjdk.mcjavanpu.NpuConfig;
import bslsjdk.mcjavanpu.NpuParity;
import bslsjdk.mcjavanpu.NpuLog;
import bslsjdk.mcjavanpu.NpuStats;
import bslsjdk.mcjavanpu.NpuTerrainAssist;
import bslsjdk.mcjavanpu.NpuTerrainLattice;
import bslsjdk.mcjavanpu.NpuTerrainGen;
import bslsjdk.mcjavanpu.NpuTerrainGate;
import bslsjdk.mcjavanpu.NpuTerrainHook;
import bslsjdk.mcjavanpu.NpuTerrainLock;
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

    /**
     * How long locked takeover waits for its volume before declaring a miss.
     *
     * Generous on purpose: this mode exists to measure the NPU path, and a timeout that fires
     * early would turn a slow-but-working pipeline into a row of holes. Blocking worldgen for
     * this long is unacceptable for play and acceptable for a benchmark, which is why it only
     * applies when the world is locked to takeover.
     */
    private static final long TAKEOVER_WAIT_NS = 8_000_000_000L;


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

        // The mode is frozen per world, not read live. A benchmark where the generator can
        // change halfway through measures nothing.
        final String mode = NpuTerrainLock.acquire(NpuChunkWork.worldSeed());
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

        // Locked takeover: this world is produced by ONE path from the first chunk to the last.
        //
        // The old rule was "the game thread never waits for the NPU". That is correct for an
        // assist - a miss must degrade to vanilla rather than stutter - but it makes measurement
        // impossible, because a miss hands the chunk to vanilla and the world ends up half one
        // generator and half the other. No timing can then be attributed to either.
        //
        // So takeover waits, and when nothing arrives it writes a visible sentinel rather than
        // falling back. The point is that a failure looks like a failure: holes in the world,
        // counted and logged. Vanilla terrain mixed in would look like success and measure as
        // noise.
        float[] mine = NpuTerrainAssist.peekTakeover(cx, cz, oy);

        if (mine == null && !NpuTerrainGate.worthComputing()) {
            // The pipeline will not produce anything at all, so waiting would only stall for
            // the full timeout and end up here anyway.
            fillMissing(buffer);
            NpuTerrainLock.recordFailure("pipeline refused to compute: " + NpuTerrainGate.lastReason());
            NpuStats.BLOCKS.record(0, 0, 0);
            ci.cancel();
            return;
        }

        if (mine == null) {
            NpuTerrainAssist.requestWorkSet(cx, cz, sx, sy, sz, oy);
            long deadline = System.nanoTime() + TAKEOVER_WAIT_NS;
            while (System.nanoTime() < deadline) {
                mine = NpuTerrainAssist.peekTakeover(cx, cz, oy);
                if (mine != null) break;
                try {
                    Thread.sleep(0, 200_000);            // 0.2 ms
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        if (mine != null && !NpuTerrainGate.allowWrite(cx, cz, mine, buffer.size())) {
            fillMissing(buffer);
            NpuTerrainLock.recordFailure("gate rejected: " + NpuTerrainGate.lastReason());
            NpuStats.BLOCKS.record(0, 0, 0);
            ci.cancel();
            return;
        }

        if (mine != null) {
            int n = Math.min(buffer.size(), mine.length);
            for (int i = 0; i < n; i++) buffer.set(i, mine[i]);
            NpuTerrainAssist.countTakeoverServed();
            NpuStats.BLOCKS.record(n, 0, 0);
            ci.cancel();
            return;
        }

        fillMissing(buffer);
        NpuTerrainLock.recordFailure("no NPU volume within " + (TAKEOVER_WAIT_NS / 1_000_000L) + "ms");
        NpuStats.BLOCKS.record(0, 0, 0);
        ci.cancel();
    }

    /**
     * Marks a volume as NOT generated.
     *
     * A constant, finite density: solidly negative, so the chunk comes out as air and the gap is
     * obvious on screen. Deliberately not vanilla's numbers and deliberately not NaN - NaN would
     * poison everything downstream in ways that have nothing to do with the failure we want to
     * see, and vanilla numbers would make the failure indistinguishable from success.
     */
    private static void fillMissing(DensityBuffer buffer) {
        for (int i = 0; i < buffer.size(); i++) buffer.set(i, NpuTerrainLock.MISSING);
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
