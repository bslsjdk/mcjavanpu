package bslsjdk.mcjavanpu;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * Predictive preload: asks for the chunks the player is about to need, instead of
 * only reacting to chunks the game has already loaded.
 *
 * Why this is the one render-adjacent thing worth putting on the NPU.
 *
 * Chunk work has three properties that make it an NPU target at all:
 *
 *   - it is off the frame path. A chunk requested now is needed seconds from now,
 *     not this frame, so an answer that arrives late is still useful.
 *   - it is a large regular batch. One chunk column is tens of thousands of
 *     density points, or a stack of 8x8x8 light blocks: exactly the shape the HTP
 *     wants, and nothing like branch-heavy per-frame logic.
 *   - it is pure maths with no state to get wrong in the meantime.
 *
 * Compare that with anything that has to be correct this frame - frustum culling,
 * particle integration, vertex transforms - where the round trip alone costs more
 * than doing the work on the CPU, and a late answer is a visibly wrong picture.
 *
 * So this class does NOT try to accelerate rendering. It makes the CPU spend less
 * time later, by having the answer ready before it is asked for.
 *
 * It is reactive-only today: NpuChunkAuto fires when a chunk loads, which means the
 * work starts after the game already wants it. Prediction moves the same work
 * earlier, into time the player is not waiting on.
 *
 * Everything here is bounded and non-blocking. A preload that can stall the server
 * tick is worse than no preload.
 */
public final class NpuPreload {

    /** How far ahead of the player to preload, in chunks. */
    private static volatile int radius = 3;
    /** Most chunks to enqueue per sweep. */
    private static volatile int perSweep = 8;
    /** Ticks between sweeps. */
    private static volatile int intervalTicks = 20;
    private static volatile boolean enabled = true;

    private static final java.util.concurrent.atomic.AtomicLong SWEEPS =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong ENQUEUED =
            new java.util.concurrent.atomic.AtomicLong();

    private static int tickCounter = 0;
    /** Last chunk the player was seen in, so we only re-sweep when they move. */
    private static volatile long lastPlayerChunk = Long.MIN_VALUE;

    private NpuPreload() {}

    public static boolean isEnabled() { return enabled; }
    public static void setEnabled(boolean v) {
        enabled = v;
        NpuLog.log("preload " + (v ? "enabled" : "disabled"));
    }

    public static int radius() { return radius; }
    public static void setRadius(int v) { radius = Math.max(1, Math.min(8, v)); }

    /**
     * Called every server tick. Cheap: it reads one player's position and, at most
     * once a second, enqueues a bounded ring of chunks ahead of them.
     */
    public static void onServerTick(ServerLevel level) {
        NpuConfig cfg = NpuConfig.get();
        if (!enabled || cfg == null || !cfg.enabled || level == null) return;

        // Nothing to preload into if both paths are vanilla.
        if ("vanilla".equalsIgnoreCase(cfg.lightMode)
                && "vanilla".equalsIgnoreCase(cfg.chunkMode)) return;

        if (++tickCounter < intervalTicks) return;
        tickCounter = 0;

        int[] pos = playerChunk(level);
        if (pos == null) return;

        int pcx = pos[0], pcz = pos[1];
        long key = (((long) pcx) << 32) ^ (pcz & 0xFFFFFFFFL);
        if (key == lastPlayerChunk) return;   // player has not changed chunk since last sweep
        lastPlayerChunk = key;

        // Direction of travel, so we preload where they are going rather than a
        // full circle. Falls back to a full ring when we cannot tell.
        int dx = pos[2], dz = pos[3];

        int added = 0;
        for (int r = 1; r <= radius && added < perSweep; r++) {
            for (int i = -r; i <= r && added < perSweep; i++) {
                for (int j = -r; j <= r && added < perSweep; j++) {
                    // outer ring only
                    if (Math.max(Math.abs(i), Math.abs(j)) != r) continue;
                    // when we know the direction, skip the chunks behind
                    if ((dx != 0 || dz != 0) && (i * dx + j * dz) < 0) continue;
                    NpuChunkAuto.requestAhead(pcx + i, pcz + j);
                    added++;
                }
            }
        }
        SWEEPS.incrementAndGet();
        ENQUEUED.addAndGet(added);
    }

    /**
     * Reads the first player's chunk and their direction of travel.
     * Returns {chunkX, chunkZ, dirX, dirZ}; dir is 0,0 when unknown.
     *
     * Everything is guarded because this runs on the server tick and must never
     * throw - a preload that breaks world loading is worse than none.
     */
    private static int[] playerChunk(ServerLevel level) {
        try {
            List<ServerPlayer> players = level.players();
            if (players == null || players.isEmpty()) return null;
            ServerPlayer p = players.get(0);
            if (p == null) return null;

            int cx = ((int) Math.floor(p.getX())) >> 4;
            int cz = ((int) Math.floor(p.getZ())) >> 4;

            int dx = 0, dz = 0;
            try {
                // getDirection() is the cheapest honest signal: it is the facing
                // the game itself uses, and it is already quantised.
                Object dir = p.getClass().getMethod("getDirection").invoke(p);
                String s = String.valueOf(dir);
                if (s.contains("NORTH")) dz = -1;
                else if (s.contains("SOUTH")) dz = 1;
                else if (s.contains("WEST")) dx = -1;
                else if (s.contains("EAST")) dx = 1;
            } catch (Throwable ignored) {
                // direction is an optimisation, not a requirement
            }
            return new int[]{cx, cz, dx, dz};
        } catch (Throwable t) {
            NpuLog.error("preload: could not read player position", t);
            return null;
        }
    }

    public static String summary() {
        return "preload=" + (enabled ? "on" : "off")
                + " radius=" + radius
                + " sweeps=" + SWEEPS.get()
                + " enqueued=" + ENQUEUED.get();
    }

    public static void resetStats() {
        SWEEPS.set(0); ENQUEUED.set(0);
    }
}
