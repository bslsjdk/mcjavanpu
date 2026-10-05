package bslsjdk.mcjavanpu;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.Component;

public final class McJavaNpu implements ModInitializer {
    public static final String MOD_ID = "mcjavanpu";

    @Override
    public void onInitialize() {
        NpuLog.log("mod initialised");
        Thread.ofVirtual().name("mcjavanpu-init").start(McJavaNpu::boot);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));

        // Automatic entry point. Without this the NPU only ever ran when a command was
        // typed, which is why loading looked exactly like vanilla - it was vanilla.
        try {
            ServerChunkEvents.CHUNK_LOAD.register((world, chunk, newlyLoaded) -> NpuChunkAuto.onChunkLoad(world, chunk));
            ServerTickEvents.END_SERVER_TICK.register(server -> NpuChunkAuto.onServerTick(server));
            NpuLog.log("auto hooks registered (chunk load + server tick)");
        } catch (Throwable t) {
            NpuLog.error("auto hooks failed to register", t);
        }
        // Diagnostics run on their own. Nothing here needs a command, and
        // everything lands in the log file so a session can be analysed later.
        try { NpuAutoProbe.start(); } catch (Throwable t) { NpuLog.error("autoprobe start failed", t); }
        NpuLog.log("dedicated log: " + NpuLog.getPathString());
    }

    /**
     * Background bring-up. Runs at mod init: connect, read the switches, and if
     * autoWarmup is on, build + calibrate the graphs before the player ever asks
     * for anything. That is what makes the first real call fast instead of slow.
     */
    private static void boot() {
        try {
            NpuLog.log("boot: initialising runtime");
            NpuRuntime.init();
            boolean ok = NpuRuntime.isAvailable();
            NpuLog.log("boot: available=" + ok + " device=" + NpuRuntime.getDeviceInfo());
            if (!ok) { NpuLog.warn("boot: NPU service unavailable, staying idle"); return; }

            NpuConfig cfg = NpuConfig.get();
            NpuLog.log("boot: " + cfg.describe());
            if (!cfg.enabled) { NpuLog.log("boot: disabled by config, skipping warmup"); return; }
            if (!cfg.autoWarmup) { NpuLog.log("boot: autoWarmup off"); return; }

            // Give the world / renderer time to come up so the warmup does not
            // compete with chunk loading.
            try { Thread.sleep(20000L); } catch (InterruptedException ie) { return; }

            // Warmup exists to measure, so this is where the host reference is worth
            // paying for. Everywhere else it is pure overhead on the calling thread.
            // Warm-up is calibration, not production. It builds graphs and runs a host
            // reference on the calling thread, both of which production never pays. On
            // 2026-10-05 its samples put a median of 8624us into the guard window against
            // an 8000us budget, the guard degraded during boot, and the NPU then went
            // unused for the entire session. Marking the stretch keeps those timings out
            // of the health window and guarantees the calls are not refused.
            NpuGuard.setCalibrating(true);
            NpuLightAccel.setVerify(true);
            long t0 = System.nanoTime();
            NpuLightAccel.Result r = NpuLightAccel.propagate(cfg.lightBatch);
            long ms = (System.nanoTime() - t0) / 1000000L;
            NpuLog.log("warmup first (build+calibrate): " + r.summary() + " wall_ms=" + ms);

            long t1 = System.nanoTime();
            NpuLightAccel.Result r2 = NpuLightAccel.propagate(cfg.lightBatch);
            long ms2 = (System.nanoTime() - t1) / 1000000L;
            NpuLog.log("warmup steady: " + r2.summary() + " wall_ms=" + ms2);
            NpuLightAccel.setVerify(false);
            NpuGuard.setCalibrating(false);
            NpuLog.log("boot: done, graphs are hot");
        } catch (Throwable t) {
            NpuLog.error("boot failed", t);
        }
    }

    /**
     * Real-data demo: a batch of `points` points x 16 features x 32 outputs goes
     * through the binary IPC to the HTP, then the same multiply is done locally
     * so the command reports a genuine speedup and a correctness delta.
     */
    private static int runSubmit(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int points, int k, int w) {
        int m = points;
        byte[] A = new byte[m * k];
        byte[] B = new byte[k * w];
        java.util.Random rnd = new java.util.Random(1234);
        rnd.nextBytes(A);
        rnd.nextBytes(B);

        long t0 = System.nanoTime();
        NpuRuntime.MatMulResult res = NpuRuntime.submitMatMulInt8(A, B, m, k, w);
        long npuUs = (System.nanoTime() - t0) / 1000;
        if (!res.ok()) {
            final String err = res.error();
            context.getSource().sendSuccess(() -> Component.literal("[NPU] submit FAILED: " + err), false);
            return 0;
        }

        long t1 = System.nanoTime();
        // The service quantises A and B with step 0.001, so a product of two int8
        // values carries scaleA*scaleB = 1e-6. The reference must live in those
        // same units -- accumulating the raw integers made every comparison look
        // wrong by a factor of a million (~351629 across 256^3).
        final float Q = 1.0e-6f;
        float[] ref = new float[m * w];
        for (int i = 0; i < m; i++) {
            for (int p = 0; p < k; p++) {
                int av = A[i * k + p];
                for (int j = 0; j < w; j++) ref[i * w + j] += av * B[p * w + j] * Q;
            }
        }
        long javaUs = (System.nanoTime() - t1) / 1000;

        int bad = 0;
        float maxAbs = 0f;
        byte[] c = res.c();
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < w; j++) {
                float got = c[i * w + j] * res.scaleC();
                float d = Math.abs(got - ref[i * w + j]);
                if (d > 2.5f * res.scaleC() + 0.05f * Math.abs(ref[i * w + j])) bad++;
                if (d > maxAbs) maxAbs = d;
            }
        }
        final String line = "[NPU] submit points=" + m + " k=" + k + " w=" + w
                + " npu_us=" + npuUs + " java_us=" + javaUs
                + " speedup=" + String.format(java.util.Locale.ROOT, "%.2fx", javaUs / (double) Math.max(1, npuUs))
                + " bad=" + bad + "/" + (m * w)
                + " max_abs=" + String.format(java.util.Locale.ROOT, "%.4f", maxAbs)
                + " scaleC=" + res.scaleC();
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        System.out.println("[MCJavaNPU] " + line);
        return bad == 0 ? 1 : 0;
    }

    private static int runMatMulInt8(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int m, int k, int n) {
        String result = NpuRuntime.matMulInt8(m, k, n);
        context.getSource().sendSuccess(() -> Component.literal("[NPU] " + result), false);
        return result.startsWith("OK ") ? 1 : 0;
    }

    private static int runMatMul(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int m, int k, int n) {
        String result = NpuRuntime.matMul(m, k, n);
        context.getSource().sendSuccess(() -> Component.literal("[NPU] matmul=" + result), false);
        return result.startsWith("OK ") ? 1 : 0;
    }

    /**
     * Element-wise add of two arrays through the service, split internally into
     * fixed-shape ways and merged back into one result.
     *
     * Runs on a worker on purpose. The point of this command is to measure a batch
     * that takes seconds, and a handler that blocks the server thread for that long
     * stops the world instead of measuring it.
     *
     * The result goes to the log and not to chat: this thread outlives the command,
     * and handing a CommandSourceStack to it would touch game state from a thread
     * the game does not own. maxWay() is also deliberately not called here - it asks
     * the service, which would block the command thread for up to a read timeout.
     */
    private static int runBigAdd(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int total) {
        Thread.ofVirtual().name("mcjavanpu-bigadd").start(() -> {
            try {
                NpuLog.log("[NPU] bigadd " + NpuBigAddClient.selfTest(total, 0, 2));
            } catch (Throwable t) {
                NpuLog.error("[NPU] bigadd failed", t);
            }
        });
        context.getSource().sendSuccess(() -> Component.literal(
                "[NPU] bigadd started total=" + total + " (result in log)"), false);
        return 1;
    }

    /**
     * Shape-planned real-data submit: the caller gives the LOGICAL shape and
     * NpuDispatcher pads it to the hardware-friendly shape, runs one call, then
     * crops back. Reports both the logical and the planned shape.
     */
    private static int runSubmitPlanned(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                        int mActual, int kActual, int nActual) {
        byte[] A = new byte[mActual * kActual];
        byte[] B = new byte[kActual * nActual];
        java.util.Random rnd = new java.util.Random(1234);
        rnd.nextBytes(A);
        rnd.nextBytes(B);

        int[] sh = NpuDispatcher.planShape(mActual, kActual, nActual);
        int m = sh[0], k = sh[1], n = sh[2];
        long padCells = (long) m * k + (long) k * n;

        long t0 = System.nanoTime();
        NpuRuntime.MatMulResult res = NpuDispatcher.submit(A, B, mActual, kActual, nActual);
        long npuUs = (System.nanoTime() - t0) / 1000;
        if (!res.ok()) {
            final String err = res.error();
            context.getSource().sendSuccess(() -> Component.literal("[NPU] planned FAILED: " + err), false);
            return 0;
        }

        long t1 = System.nanoTime();
        final float Q = 1.0e-6f;
        float[] ref = new float[mActual * nActual];
        for (int i = 0; i < mActual; i++) {
            for (int p = 0; p < kActual; p++) {
                int av = A[i * kActual + p];
                for (int j = 0; j < nActual; j++) ref[i * nActual + j] += av * B[p * nActual + j] * Q;
            }
        }
        long javaUs = (System.nanoTime() - t1) / 1000;

        int bad = 0;
        float maxAbs = 0f;
        byte[] c = res.c();
        for (int i = 0; i < mActual; i++) {
            for (int j = 0; j < nActual; j++) {
                float got = c[i * nActual + j] * res.scaleC();
                float d = Math.abs(got - ref[i * nActual + j]);
                if (d > 2.5f * res.scaleC() + 0.05f * Math.abs(ref[i * nActual + j])) bad++;
                if (d > maxAbs) maxAbs = d;
            }
        }
        final String line = "[NPU] planned logical=" + mActual + "x" + kActual + "x" + nActual
                + " -> hw=" + m + "x" + k + "x" + n
                + " pad_cells=" + padCells
                + " npu_us=" + npuUs + " java_us=" + javaUs
                + " speedup=" + String.format(java.util.Locale.ROOT, "%.2fx", javaUs / (double) Math.max(1, npuUs))
                + " bad=" + bad + "/" + (mActual * nActual)
                + " max_abs=" + String.format(java.util.Locale.ROOT, "%.4f", maxAbs);
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        System.out.println("[MCJavaNPU] " + line);
        return bad == 0 ? 1 : 0;
    }

    /** Real 8x8x8 voxel light propagation, batched through the NPU. */
    private static int runLight(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int blocks) {
        long t0 = System.nanoTime();
        NpuLightAccel.Result r = NpuLightAccel.propagate(blocks);
        long wallUs = (System.nanoTime() - t0) / 1000;
        final String line = "[NPU] light " + r.summary() + " wall_us=" + wallUs;
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        System.out.println("[MCJavaNPU] " + line);
        return r.ok && r.bad == 0 ? 1 : 0;
    }

    /**
     * REAL data path: read an actual 8x8x8 block-light field around the player and
     * run it through the NPU propagation operator.
     *
     * Everything Minecraft-side is done through reflection on purpose: this file
     * then compiles against ANY 26.3 revision, and a signature change shows up as
     * a readable READ_FAILED message instead of a build break.
     */
    private static int runLightChunk(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int blocks) {
        String api = "(none)";
        byte[] cells = null;
        String err = null;
        int nzBlock = 0, nzSky = 0, skyMax = 0;
        try {
            CommandSourceStack src = context.getSource();
            Object level = src.getLevel();
            Object pos = src.getClass().getMethod("getPosition").invoke(src);
            Class<?> pc = pos.getClass();
            java.lang.reflect.Field fx = pc.getField("x"), fy = pc.getField("y"), fz = pc.getField("z");
            int bx = (int) Math.floor(((Number) fx.get(pos)).doubleValue());
            int by = (int) Math.floor(((Number) fy.get(pos)).doubleValue());
            int bz = (int) Math.floor(((Number) fz.get(pos)).doubleValue());

            Class<?> llCls = Class.forName("net.minecraft.world.level.LightLayer");
            Object blockLayer = null, skyLayer = null;
            Object[] consts = llCls.getEnumConstants();
            if (consts != null) {
                for (Object o : consts) {
                    if ("BLOCK".equals(String.valueOf(o))) blockLayer = o;
                    if ("SKY".equals(String.valueOf(o))) skyLayer = o;
                }
            }
            if (blockLayer == null) throw new IllegalStateException("LightLayer.BLOCK missing");

            Class<?> bpCls = Class.forName("net.minecraft.core.BlockPos");
            java.lang.reflect.Constructor<?> ctor = bpCls.getConstructor(int.class, int.class, int.class);
            java.lang.reflect.Method getBrightness = level.getClass().getMethod("getBrightness", llCls, bpCls);
            api = "getBrightness(LightLayer.BLOCK,BlockPos)";

            cells = new byte[NpuLightAccel.CELLS];
            nzBlock = 0; nzSky = 0; skyMax = 0;
            int i = 0;
            for (int y = 0; y < 8; y++)
                for (int z = 0; z < 8; z++)
                    for (int x = 0; x < 8; x++) {
                        Object bp = ctor.newInstance(bx + x, by + y, bz + z);
                        int bv = ((Number) getBrightness.invoke(level, blockLayer, bp)).intValue();
                        int sv = skyLayer == null ? 0 : ((Number) getBrightness.invoke(level, skyLayer, bp)).intValue();
                        if (bv > 0) nzBlock++;
                        if (sv > 0) nzSky++;
                        if (sv > skyMax) skyMax = sv;
                        cells[i++] = (byte) (Math.max(bv, sv) & 0xFF);
                    }
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }

        if (err != null) {
            final String line = "[NPU] lightchunk READ_FAILED api=" + api + " -> " + err;
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            System.out.println("[MCJavaNPU] " + line);
            return 0;
        }

        int mn = 255, mx = 0, sum = 0, nz = 0;
        for (byte b : cells) { int v = b & 0xFF; if (v < mn) mn = v; if (v > mx) mx = v; sum += v; if (v > 0) nz++; }
        final String stat = "real_light min=" + mn + " max=" + mx + " avg="
                + String.format(java.util.Locale.ROOT, "%.2f", sum / (double) NpuLightAccel.CELLS)
                + " nonzero=" + nz + "/512 (block " + nzBlock + ", sky " + nzSky + ", skyMax " + skyMax + ")";

        long t0 = System.nanoTime();
        NpuLightAccel.Result r = NpuLightAccel.propagateReal(cells, blocks);
        long wallUs = (System.nanoTime() - t0) / 1000;

        final String line = "[NPU] lightchunk " + stat + " | " + r.summary() + " wall_us=" + wallUs;
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        System.out.println("[MCJavaNPU] " + line);
        return r.ok ? 1 : 0;
    }

    /**
     * Start a sampling profile in the background and drop the ranked result in the log.
     * Deliberately asynchronous: a profiler that blocks the thread it is measuring lies.
     */
    private static int runProfile(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int seconds) {
        if (NpuProfiler.isRunning()) {
            final String l = "[NPU] profile already running";
            context.getSource().sendSuccess(() -> Component.literal(l), false);
            return 0;
        }
        NpuLog.log("profile start: " + seconds + "s");
        Thread t = new Thread(() -> {
            String r = NpuProfiler.sample(seconds);
            NpuLog.log("profile done\n" + r);
        }, "npu-profile");
        t.setDaemon(true);
        t.start();
        final String line = "[NPU] profiling for " + seconds + "s - top frames will land in logs/mcjavanpu-npu.log";
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        return 1;
    }

    /**
     * Force the game to throw its light away and build it again, for (2r+1)^2 sections.
     *
     * Without this, switching a mode changes nothing you can see: chunks that are
     * already lit keep the light they were computed with, by whichever mode was active
     * back then. Toggling setLightEnabled off and on is the engine's own "recompute this
     * section" switch, so the comparison is apples to apples.
     */
    private static int runReloadChunks(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                       int radius) {
        String info;
        try {
            Object level = context.getSource().getLevel();
            Object le = level.getClass().getMethod("getLightEngine").invoke(level);
            Object pos = context.getSource().getClass().getMethod("getPosition").invoke(context.getSource());
            Class<?> pc = pos.getClass();
            int bx = (int) Math.floor(((Number) pc.getField("x").get(pos)).doubleValue());
            int bz = (int) Math.floor(((Number) pc.getField("z").get(pos)).doubleValue());

            // LightEngine exposes setLightEnabled(ChunkPos, boolean). There is no SectionPos
            // overload - that is updateSectionStatus, which means something else entirely, and
            // asking for the wrong one is what produced NoSuchMethodException here. Toggling a
            // whole chunk column off and on is the engine's own "discard and rebuild this light"
            // path, which is exactly what a mode comparison needs.
            // The signature has moved more than once: SectionPos, then ChunkPos, and
            // 26.3 renamed it again. Rather than hard-code one more guess, look for any
            // two-arg method whose name mentions light+enabled and whose second
            // parameter is a boolean, then build the position argument to match
            // whatever its first parameter actually is.
            java.lang.reflect.Method setEnabled = null;
            for (java.lang.reflect.Method m : le.getClass().getMethods()) {
                String n = m.getName();
                if (m.getParameterCount() == 2
                        && m.getParameterTypes()[1] == boolean.class
                        && n.toLowerCase().contains("light")
                        && n.toLowerCase().contains("enabled")) {
                    setEnabled = m;
                    break;
                }
            }
            if (setEnabled == null) {
                // Nothing matched. Say what IS there, so the next fix is not another guess.
                StringBuilder dump = new StringBuilder("no light-enable method on "
                        + le.getClass().getName() + "; candidates:");
                for (java.lang.reflect.Method m : le.getClass().getMethods()) {
                    String n = m.getName().toLowerCase();
                    if (n.contains("light") || n.contains("enable")) {
                        dump.append("\n  ").append(m.getName()).append("(");
                        Class<?>[] ps = m.getParameterTypes();
                        for (int i = 0; i < ps.length; i++) {
                            if (i > 0) dump.append(", ");
                            dump.append(ps[i].getSimpleName());
                        }
                        dump.append(")");
                    }
                }
                NpuLog.warn(dump.toString());
                throw new NoSuchMethodException("setLightEnabled-like(?, boolean)");
            }

            int ccx = bx >> 4, ccz = bz >> 4;
            int cap = Math.max(0, Math.min(radius, 4));
            int done = 0;
            Class<?> firstArg = setEnabled.getParameterTypes()[0];
            for (int dx = -cap; dx <= cap; dx++) {
                for (int dz = -cap; dz <= cap; dz++) {
                    Object arg = buildPosArg(firstArg, ccx + dx, ccz + dz);
                    if (arg == null) break;
                    setEnabled.invoke(le, arg, false);
                    setEnabled.invoke(le, arg, true);
                    done++;
                }
            }
            info = "mode_light=" + NpuConfig.get().lightMode + " mode_chunk=" + NpuConfig.get().chunkMode
                    + " forced_recompute=" + done + " chunks"
                    + " via=" + setEnabled.getName() + "(" + firstArg.getSimpleName() + ",boolean)";
        } catch (Throwable t) {
            info = "FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        final String line = "[NPU] reloadchunks " + info;
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        NpuLog.log(line);
        return 0;
    }

    /**
     * Builds the position argument for whatever the resolved method actually wants.
     * Returns null when the type is one we cannot construct, so the caller skips
     * instead of throwing.
     */
    private static Object buildPosArg(Class<?> type, int cx, int cz) {
        try {
            String n = type.getName();
            if (n.endsWith("ChunkPos") || n.endsWith("SectionPos")) {
                java.lang.reflect.Constructor<?> c = type.getConstructor(int.class, int.class);
                return c.newInstance(cx, cz);
            }
            if (type == long.class || type == Long.class) {
                // ChunkPos.asLong(): pack the two ints into one long.
                return ((long) cx & 0xFFFFFFFFL) | (((long) cz & 0xFFFFFFFFL) << 32);
            }
            if (type == int.class || type == Integer.class) return cx;
        } catch (Throwable t) {
            NpuLog.warn("reloadchunks: cannot build " + type.getSimpleName() + " arg: " + t);
        }
        return null;
    }

    /**
     * Read (2r+1)^2 whole chunk sections -> fold into ONE NPU batch -> write back.
     *
     * apply=false : read-only probe, nothing in the world changes.
     * apply=true  : the propagated values are pushed through DataLayer.set, i.e.
     *               this replaces the serial BFS the light engine was about to run.
     */
    private static int runLightApply(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                                     int radius, boolean apply) {
        String info;
        // Three-way mode decides what actually happens:
        //   vanilla - nothing, the game keeps its own path (skip unless explicitly forced)
        //   npu     - the result is written back, replacing the game's serial propagation
        //   assist  - the NPU computes and reports, the game still finishes the job
        final String mode = NpuConfig.get().lightMode;
        final boolean writeBack = apply || "npu".equalsIgnoreCase(mode);
        if ("vanilla".equalsIgnoreCase(mode) && !apply) {
            final String line = "[NPU] lightapply mode=vanilla (skipped, the game does it itself)";
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            NpuLog.log(line);
            return 0;
        }
        try {
            Object level = context.getSource().getLevel();
            Class<?> ll = Class.forName("net.minecraft.world.level.LightLayer");
            Object blockLayer = null;
            for (Object o : ll.getEnumConstants()) if ("BLOCK".equals(String.valueOf(o))) blockLayer = o;
            Object le = level.getClass().getMethod("getLightEngine").invoke(level);
            Object listener = le.getClass().getMethod("getLayerListener", ll).invoke(le, blockLayer);
            Object pos = context.getSource().getClass().getMethod("getPosition").invoke(context.getSource());
            Class<?> pc = pos.getClass();
            int bx = (int) Math.floor(((Number) pc.getField("x").get(pos)).doubleValue());
            int by = (int) Math.floor(((Number) pc.getField("y").get(pos)).doubleValue());
            int bz = (int) Math.floor(((Number) pc.getField("z").get(pos)).doubleValue());
            Class<?> sp = Class.forName("net.minecraft.core.SectionPos");
            java.lang.reflect.Method spOf = sp.getMethod("of", int.class, int.class, int.class);
            int scx = bx >> 4, scz = bz >> 4;
            int scyPlayer = by >> 4;

            // Air-only sections get no DataLayer at all (the engine skips allocating
            // storage for them), so a player standing in the air has nothing to read.
            // Walk down (then up) to find a section that actually owns storage.
            java.lang.reflect.Method gdd = listener.getClass().getMethod("getDataLayerData", sp);
            int scy = Integer.MIN_VALUE;
            for (int dy = 0; dy <= 12 && scy == Integer.MIN_VALUE; dy++) {
                int[] cands = {scyPlayer - dy, scyPlayer + dy};
                for (int cy : cands) {
                    Object sec = spOf.invoke(null, scx, cy, scz);
                    Object probe = gdd.invoke(listener, sec);
                    if (probe instanceof java.util.Optional) probe = ((java.util.Optional<?>) probe).orElse(null);
                    if (probe != null) { scy = cy; break; }
                }
            }
            if (scy == Integer.MIN_VALUE) {
                scy = scyPlayer;
                NpuLog.log("lightapply: no allocated section within +/-12 of y-section " + scyPlayer);
            } else if (scy != scyPlayer) {
                NpuLog.log("lightapply: player section " + scyPlayer + " empty, using " + scy);
            }

            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            java.util.List<Object> layers = new java.util.ArrayList<>();
            int rows = 0, sections = 0, nz = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Object section = spOf.invoke(null, scx + dx, scy, scz + dz);
                    Object dl = listener.getClass().getMethod("getDataLayerData", sp).invoke(listener, section);
                    if (dl instanceof java.util.Optional) dl = ((java.util.Optional<?>) dl).orElse(null);
                    if (dl == null) continue;
                    sections++;
                    layers.add(dl);
                    java.lang.reflect.Method g = dl.getClass().getMethod("get", int.class, int.class, int.class);
                    for (int sy = 0; sy < 2; sy++)
                        for (int sz = 0; sz < 2; sz++)
                            for (int sx = 0; sx < 2; sx++) {
                                byte[] row = new byte[NpuLightAccel.CELLS];
                                for (int y = 0; y < 8; y++)
                                    for (int z = 0; z < 8; z++)
                                        for (int x = 0; x < 8; x++) {
                                            int v = ((Number) g.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z)).intValue();
                                            row[(y * 8 + z) * 8 + x] = (byte) (v & 0xFF);
                                            if (v > 0) nz++;
                                        }
                                buf.write(row, 0, row.length);
                                rows++;
                            }
                }
            }
            if (rows == 0) {
                info = "no DataLayer in range (sections not loaded)";
            } else {
                NpuLightAccel.Result r = NpuLightAccel.propagateBatch(buf.toByteArray(), rows);
                NpuStats.LIGHT.record((long) rows * NpuLightAccel.CELLS, r.npuUs, r.cpuUs);
                if (!r.ok) {
                    info = "FAIL " + r.error;
                } else if (!writeBack) {
                    // ASSIST: the NPU does the numeric part of the propagation and hands it
                    // to the CPU as a head start. It only ever raises a cell, never lowers
                    // one, so it can add light but can never darken the world or fight the
                    // engine's own propagation. The CPU then finishes the job from a much
                    // better starting point instead of from zero.
                    int raised = 0, row = 0;
                    for (Object dl : layers) {
                        java.lang.reflect.Method gv = dl.getClass().getMethod("get", int.class, int.class, int.class);
                        java.lang.reflect.Method st = dl.getClass().getMethod("set", int.class, int.class, int.class, int.class);
                        for (int sy = 0; sy < 2; sy++)
                            for (int sz = 0; sz < 2; sz++)
                                for (int sx = 0; sx < 2; sx++) {
                                    for (int y = 0; y < 8; y++)
                                        for (int z = 0; z < 8; z++)
                                            for (int x = 0; x < 8; x++) {
                                                int v = r.light(row, (y * 8 + z) * 8 + x);
                                                if (v <= 0) continue;
                                                int cur = ((Number) gv.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z)).intValue();
                                                if (v > cur) {
                                                    st.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z, v);
                                                    raised++;
                                                }
                                            }
                                    row++;
                                }
                    }
                    NpuLightAccel.noteWritten(raised);
                    info = "mode=" + mode + " ASSIST sections=" + sections + " rows=" + rows
                            + " raised=" + raised + "/" + (rows * NpuLightAccel.CELLS)
                            + " (cpu continues from this)" + " | " + r.summary();
                } else {
                    int written = 0, row = 0;
                    for (Object dl : layers) {
                        java.lang.reflect.Method st = dl.getClass().getMethod("set", int.class, int.class, int.class, int.class);
                        for (int sy = 0; sy < 2; sy++)
                            for (int sz = 0; sz < 2; sz++)
                                for (int sx = 0; sx < 2; sx++) {
                                    for (int y = 0; y < 8; y++)
                                        for (int z = 0; z < 8; z++)
                                            for (int x = 0; x < 8; x++) {
                                                int v = r.light(row, (y * 8 + z) * 8 + x);
                                                if (v > 0) {
                                                    st.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z, v);
                                                    written++;
                                                }
                                            }
                                    row++;
                                }
                    }
                    NpuLightAccel.noteWritten(written);
                    info = "mode=" + mode + " APPLIED sections=" + sections + " rows=" + rows + " written=" + written + " | " + r.summary();
                }
            }
        } catch (Throwable t) {
            info = "FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        final String line = "[NPU] lightapply " + info;
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        NpuLog.log(line);
        return 0;
    }

    /**
     * Fold (2r+1)^2 whole chunk sections into ONE NPU batch.
     *
     * Each section is 16x16x16 = eight 8x8x8 fields, so a radius of 1 gives
     * 9 sections x 8 = 72 rows in a single call. This is the batched alternative
     * to multi-threaded chunk work: the device cost barely changes with row count.
     */
    private static int runLightFold(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int radius) {
        String info;
        // Chunk work follows chunkMode the same way light follows lightMode:
        //   vanilla - hand back to the game, do not touch a thing
        //   npu     - the folded batch owns the section light and is written back
        //   assist  - the batch is computed and reported, the game still owns the result
        final String cmode = NpuConfig.get().chunkMode;
        if ("vanilla".equalsIgnoreCase(cmode)) {
            final String line = "[NPU] lightfold mode=vanilla (skipped, the game loads chunks itself)";
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            NpuLog.log(line);
            return 0;
        }
        try {
            Object level = context.getSource().getLevel();
            Class<?> ll = Class.forName("net.minecraft.world.level.LightLayer");
            Object blockLayer = null;
            for (Object o : ll.getEnumConstants()) if ("BLOCK".equals(String.valueOf(o))) blockLayer = o;
            Object le = level.getClass().getMethod("getLightEngine").invoke(level);
            Object listener = le.getClass().getMethod("getLayerListener", ll).invoke(le, blockLayer);
            Object pos = context.getSource().getClass().getMethod("getPosition").invoke(context.getSource());
            Class<?> pc = pos.getClass();
            int bx = (int) Math.floor(((Number) pc.getField("x").get(pos)).doubleValue());
            int by = (int) Math.floor(((Number) pc.getField("y").get(pos)).doubleValue());
            int bz = (int) Math.floor(((Number) pc.getField("z").get(pos)).doubleValue());
            Class<?> sp = Class.forName("net.minecraft.core.SectionPos");
            java.lang.reflect.Method spOf = sp.getMethod("of", int.class, int.class, int.class);
            int scx = bx >> 4, scy = by >> 4, scz = bz >> 4;

            java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
            java.util.List<Object> layers = new java.util.ArrayList<>();
            int rows = 0, sections = 0, nzTotal = 0;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    Object section = spOf.invoke(null, scx + dx, scy, scz + dz);
                    Object dl = listener.getClass().getMethod("getDataLayerData", sp).invoke(listener, section);
                    if (dl instanceof java.util.Optional) dl = ((java.util.Optional<?>) dl).orElse(null);
                    if (dl == null) continue;
                    java.lang.reflect.Method g = dl.getClass().getMethod("get", int.class, int.class, int.class);
                    sections++;
                    layers.add(dl);
                    for (int sy = 0; sy < 2; sy++)
                        for (int sz = 0; sz < 2; sz++)
                            for (int sx = 0; sx < 2; sx++) {
                                byte[] row = new byte[NpuLightAccel.CELLS];
                                for (int y = 0; y < 8; y++)
                                    for (int z = 0; z < 8; z++)
                                        for (int x = 0; x < 8; x++) {
                                            int v = ((Number) g.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z)).intValue();
                                            row[(y * 8 + z) * 8 + x] = (byte) (v & 0xFF);
                                            if (v > 0) nzTotal++;
                                        }
                                buf.write(row, 0, row.length);
                                rows++;
                            }
                }
            }
            if (rows == 0) {
                info = "no DataLayer in range (sections not loaded)";
            } else {
                NpuLightAccel.Result r = NpuLightAccel.propagateBatch(buf.toByteArray(), rows);
                if (!r.ok) {
                    info = "FAIL " + r.error;
                } else if (!"npu".equalsIgnoreCase(cmode)) {
                    // ASSIST for chunk work: same contract as light assist - the NPU raises
                    // what it can and the CPU keeps its own propagation, so the two work
                    // together rather than one waiting on the other.
                    int raised = 0, row = 0;
                    for (Object dl : layers) {
                        java.lang.reflect.Method gv = dl.getClass().getMethod("get", int.class, int.class, int.class);
                        java.lang.reflect.Method st = dl.getClass().getMethod("set", int.class, int.class, int.class, int.class);
                        for (int sy = 0; sy < 2; sy++)
                            for (int sz = 0; sz < 2; sz++)
                                for (int sx = 0; sx < 2; sx++) {
                                    for (int y = 0; y < 8; y++)
                                        for (int z = 0; z < 8; z++)
                                            for (int x = 0; x < 8; x++) {
                                                int v = r.light(row, (y * 8 + z) * 8 + x);
                                                if (v <= 0) continue;
                                                int cur = ((Number) gv.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z)).intValue();
                                                if (v > cur) {
                                                    st.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z, v);
                                                    raised++;
                                                }
                                            }
                                    row++;
                                }
                    }
                    info = "mode=" + cmode + " ASSIST folded sections=" + sections + " rows=" + rows
                            + " nonzero=" + nzTotal + " raised=" + raised + "/" + (rows * NpuLightAccel.CELLS)
                            + " (cpu continues from this)" + " | " + r.summary();
                } else {
                    int written = 0, row = 0;
                    for (Object dl : layers) {
                        java.lang.reflect.Method st = dl.getClass().getMethod("set", int.class, int.class, int.class, int.class);
                        for (int sy = 0; sy < 2; sy++)
                            for (int sz = 0; sz < 2; sz++)
                                for (int sx = 0; sx < 2; sx++) {
                                    for (int y = 0; y < 8; y++)
                                        for (int z = 0; z < 8; z++)
                                            for (int x = 0; x < 8; x++) {
                                                int v = r.light(row, (y * 8 + z) * 8 + x);
                                                if (v > 0) {
                                                    st.invoke(dl, sx * 8 + x, sy * 8 + y, sz * 8 + z, v);
                                                    written++;
                                                }
                                            }
                                    row++;
                                }
                    }
                    info = "mode=" + cmode + " FOLDED+APPLIED sections=" + sections + " rows=" + rows
                            + " written=" + written + " | " + r.summary();
                }
            }
        } catch (Throwable t) {
            info = "FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        final String line = "[NPU] lightfold " + info;
        context.getSource().sendSuccess(() -> Component.literal(line), false);
        NpuLog.log(line);
        return 0;
    }

    /**
     * Terrain smoke test: enqueue a small real terrain work set without blocking the game thread.
     * This tests the actual chunk-generation entry path, not the unrelated lightfold operator.
     */
    private static int runTerrainTest(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int radius) {
        try {
            Object level = context.getSource().getLevel();
            Object pos = context.getSource().getPosition();
            Class<?> pc = pos.getClass();
            int bx = (int) Math.floor(((Number) pc.getField("x").get(pos)).doubleValue());
            int by = (int) Math.floor(((Number) pc.getField("y").get(pos)).doubleValue());
            int bz = (int) Math.floor(((Number) pc.getField("z").get(pos)).doubleValue());
            int cx = bx >> 4, cz = bz >> 4;
            int side = radius * 2 + 1;
            // Pass the radius through. It used to be computed for the message and then not
            // given to the work set, so the reported "planned=81" described a request that
            // was never made.
            NpuTerrainAssist.requestWorkSet(cx, cz, 16, 384, 16, -64, side);
            final String line = "[NPU] terrain test queued center=" + cx + "," + cz
                    + " radius=" + radius + " planned=" + (side * side)
                    + " || " + NpuTerrainAssist.summary();
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            NpuLog.log(line);
            return 1;
        } catch (Throwable t) {
            final String line = "[NPU] terrain test FAIL " + t.getClass().getSimpleName() + ": " + t.getMessage();
            context.getSource().sendSuccess(() -> Component.literal(line), false);
            NpuLog.error(line, t);
            return 0;
        }
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("npu")
                .executes(context -> { context.getSource().sendSuccess(() -> Component.literal("[NPU] /npu status|test|addtest|benchmark"), false); return 1; })
                .then(Commands.literal("status").executes(context -> {
                    boolean available = NpuRuntime.isAvailable();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] runtime=" + NpuRuntime.isInitialized()
                            + " available=" + available + " device=" + NpuRuntime.getDeviceInfo()), false);
                    return available ? 1 : 0;
                }))
                .then(Commands.literal("inprocess").executes(context -> {
                    String r = NpuInProcessProbe.run();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] inprocess: " + r), false);
                    NpuLog.log("[NPU] inprocess: " + r);
                    return NpuInProcessProbe.isReady() ? 1 : 0;
                }))
                .then(Commands.literal("test").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(context -> {
                    NpuRuntime.TestResult result = NpuRuntime.test();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] test=" + result.name()
                            + " detail=" + result.detail()), false);
                    return result.success() ? 1 : 0;
                }))
                .then(Commands.literal("addtest").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(context -> {
                    float[] a = new float[16], b = new float[16];
                    for (int i=0;i<16;i++) { a[i]=i; b[i]=2f; }
                    String result = NpuRuntime.add(a,b);
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] addtest=" + result), false);
                    return result.startsWith("OK HTP_GRAPH_EXECUTE") ? 1 : 0;
                }))
                .then(Commands.literal("matmul")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> runMatMul(context, 256, 256, 256))
                        .then(Commands.argument("m", IntegerArgumentType.integer(16, 512))
                                .then(Commands.argument("k", IntegerArgumentType.integer(16, 512))
                                        .then(Commands.argument("n", IntegerArgumentType.integer(16, 512))
                                                .executes(context -> runMatMul(context,
                                                        IntegerArgumentType.getInteger(context, "m"),
                                                        IntegerArgumentType.getInteger(context, "k"),
                                                        IntegerArgumentType.getInteger(context, "n")))))))
                .then(Commands.literal("matmul8")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> runMatMulInt8(context, 256, 256, 256))
                        .then(Commands.argument("m", IntegerArgumentType.integer(16, 1024))
                                .then(Commands.argument("k", IntegerArgumentType.integer(16, 1024))
                                        .then(Commands.argument("n", IntegerArgumentType.integer(16, 1024))
                                                .executes(context -> runMatMulInt8(context,
                                                        IntegerArgumentType.getInteger(context, "m"),
                                                        IntegerArgumentType.getInteger(context, "k"),
                                                        IntegerArgumentType.getInteger(context, "n")))))))
                .then(Commands.literal("submit")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        // Square shapes only. Measured 2026-10-03 on HTP: int8
                        // requantisation is exact for m=k=n (256^3 -> 3.8x, 512^3 ->
                        // 29x, 1024^3 -> 147x, all bad=0) but saturates when n!=k. So
                        // the real-data demo uses squares.
                        .executes(context -> runSubmit(context, 256, 256, 256))
                        .then(Commands.argument("size", IntegerArgumentType.integer(256, 1024))
                                .executes(context -> {
                                    int s = IntegerArgumentType.getInteger(context, "size");
                                    return runSubmit(context, s, s, s);
                                })))
                .then(Commands.literal("submits")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        // Shape-planned submit: logical shape in, padded hw shape out.
                        // Defaults to 100x256x256, which the planner lifts to 128x256x256.
                        .executes(context -> runSubmitPlanned(context, 100, 256, 256))
                        .then(Commands.argument("m", IntegerArgumentType.integer(1, 4096))
                                .then(Commands.argument("k", IntegerArgumentType.integer(1, 4096))
                                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 4096))
                                                .executes(context -> runSubmitPlanned(context,
                                                        IntegerArgumentType.getInteger(context, "m"),
                                                        IntegerArgumentType.getInteger(context, "k"),
                                                        IntegerArgumentType.getInteger(context, "n")))))))
                .then(Commands.literal("bigadd")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        // Defaults to one chunk (16*16*384). The upper bound is a
                        // memory bound, not a device bound: the call holds both
                        // inputs and the output at once, 12 bytes per element, so
                        // 2M elements is ~24 MB inside the game process.
                        .executes(context -> runBigAdd(context, 98304))
                        .then(Commands.argument("total", IntegerArgumentType.integer(1024, 2_000_000))
                                .executes(context -> runBigAdd(context,
                                        IntegerArgumentType.getInteger(context, "total")))))
                .then(Commands.literal("terrain")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> runTerrainTest(context, 1))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 4))
                                .executes(context -> runTerrainTest(context, IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("light")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        // Batched light propagation: N blocks of 8x8x8 voxels in ONE NPU call.
                        .executes(context -> runLight(context, 64))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 4096))
                                .executes(context -> runLight(context, IntegerArgumentType.getInteger(context, "blocks")))))
                .then(Commands.literal("lightchunk")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        // REAL Minecraft block-light data through the NPU propagation operator.
                        .executes(context -> runLightChunk(context, 128))
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(1, 512))
                                .executes(context -> runLightChunk(context, IntegerArgumentType.getInteger(context, "blocks")))))
                .then(Commands.literal("config")
                        .executes(context -> {
                            final String line = "[NPU] " + NpuConfig.get().describe() + " log=" + NpuLog.getPathString();
                            context.getSource().sendSuccess(() -> Component.literal(line), false);
                            NpuLog.log(line);
                            return 1;
                        })
                        .then(Commands.literal("toggle")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(context -> {
                                            String k = StringArgumentType.getString(context, "key");
                                            NpuConfig.get().toggle(k);
                                            final String line = "[NPU] toggled " + k + " -> " + NpuConfig.get().describe();
                                            context.getSource().sendSuccess(() -> Component.literal(line), false);
                                            NpuLog.log(line);
                                            return 1;
                                        })))
                        .then(Commands.literal("save").executes(context -> {
                            NpuConfig.get().save();
                            context.getSource().sendSuccess(() -> Component.literal("[NPU] config saved"), false);
                            return 1;
                        })))
                .then(Commands.literal("lightapply")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> runLightApply(context, NpuConfig.get().lightFoldRadius, false))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 4))
                                .executes(context -> runLightApply(context, IntegerArgumentType.getInteger(context, "radius"), false))
                                .then(Commands.literal("apply")
                                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                                        .executes(context -> runLightApply(context, IntegerArgumentType.getInteger(context, "radius"), true)))))
                .then(Commands.literal("lightfold")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> runLightFold(context, 1))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 4))
                                .executes(context -> runLightFold(context, IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("perf")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuTelemetry.summary();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            NpuLog.log(l);
                            return 1;
                        })
                        .then(Commands.literal("dump")
                                .executes(context -> {
                                    NpuLog.log("telemetry dump \n" + NpuTelemetry.dump());
                                    final String l = "[NPU] telemetry dump written to logs/mcjavanpu-npu.log";
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    return 1;
                                }))
                        .then(Commands.literal("reset")
                                .executes(context -> {
                                    NpuTelemetry.reset();
                                    final String l = "[NPU] telemetry reset";
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    return 1;
                                })))
                .then(Commands.literal("assist")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuTerrainAssist.summary() + " || "
                                    + NpuRenderAssist.summary() + " || " + NpuChunkAuto.summary();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            NpuLog.log(l);
                            return 1;
                        })
                        .then(Commands.literal("clear")
                                .executes(context -> {
                                    NpuTerrainAssist.clear();
                                    final String l = "[NPU] assist caches cleared";
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    return 1;
                                })))
                .then(Commands.literal("diag")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] diag\n" + NpuDiagnostics.report();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            NpuLog.log("[NPU] diag\n" + NpuDiagnostics.report());
                            return 1;
                        })
                        .then(Commands.literal("reset")
                                .executes(context -> {
                                    NpuDiagnostics.reset();
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("[NPU] diagnostics cleared"), false);
                                    return 1;
                                })))
                .then(Commands.literal("preload")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuPreload.summary();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            NpuLog.log(l);
                            return 1;
                        })
                        .then(Commands.literal("on")
                                .executes(context -> {
                                    NpuPreload.setEnabled(true);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("[NPU] preload on"), false);
                                    return 1;
                                }))
                        .then(Commands.literal("off")
                                .executes(context -> {
                                    NpuPreload.setEnabled(false);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("[NPU] preload off"), false);
                                    return 1;
                                }))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8))
                                .executes(context -> {
                                    int r = IntegerArgumentType.getInteger(context, "radius");
                                    NpuPreload.setRadius(r);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("[NPU] preload radius " + r), false);
                                    return 1;
                                })))
                .then(Commands.literal("gate")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] terrain " + NpuTerrainGate.summary()
                                    + " | unsupported=" + NpuDfJson.lastUnsupported()
                                    + " | " + NpuTerrainVanilla.summary();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            NpuLog.log(l);
                            return 1;
                        })
                        .then(Commands.literal("open")
                                .executes(context -> {
                                    NpuTerrainGate.setTakeoverAllowed(true);
                                    final String l = "[NPU] terrain gate OPEN - interpreter will replace vanilla";
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    NpuLog.warn(l);
                                    return 1;
                                }))
                        .then(Commands.literal("close")
                                .executes(context -> {
                                    NpuTerrainGate.setTakeoverAllowed(false);
                                    final String l = "[NPU] terrain gate CLOSED - vanilla always wins";
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    NpuLog.log(l);
                                    return 1;
                                })))
                .then(Commands.literal("guard")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuGuard.summary();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return NpuGuard.isDegraded() ? 0 : 1;
                        })
                        .then(Commands.literal("reset")
                                .executes(context -> {
                                    NpuGuard.reset();
                                    final String l = "[NPU] guard reset";
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    NpuLog.log(l);
                                    return 1;
                                })))
                .then(Commands.literal("probe")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuAutoProbe.summary()
                                    + " | last report in " + NpuLog.getPathString();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        }))
                .then(Commands.literal("shape")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuShapeAdvisor.examples();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        })
                        .then(Commands.argument("m", IntegerArgumentType.integer(1, 65536))
                                .then(Commands.argument("k", IntegerArgumentType.integer(1, 65536))
                                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 65536))
                                                .executes(context -> {
                                                    final String l = "[NPU] " + NpuShapeAdvisor.advise(
                                                            IntegerArgumentType.getInteger(context, "m"),
                                                            IntegerArgumentType.getInteger(context, "k"),
                                                            IntegerArgumentType.getInteger(context, "n"));
                                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                                    return 1;
                                                })))))
                .then(Commands.literal("bench")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            // A sweep runs thousands of matmuls and a CPU reference for
                            // each; on the server thread that is a multi-second freeze.
                            context.getSource().sendSuccess(
                                    () -> Component.literal("[NPU] bench sweep started, results arrive in chat"), false);
                            Thread.ofVirtual().name("mcjavanpu-bench").start(() -> {
                                try {
                                    final String out = NpuBench.sweep();
                                    NpuLog.log(out);
                                    context.getSource().sendSuccess(
                                            () -> Component.literal("[NPU] " + out), false);
                                } catch (Throwable t) {
                                    final String l = "[NPU] bench failed: " + t;
                                    NpuLog.log(l);
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                }
                            });
                            return 1;
                        })
                        .then(Commands.argument("m", IntegerArgumentType.integer(1, 65536))
                                .then(Commands.argument("k", IntegerArgumentType.integer(1, 65536))
                                        .then(Commands.argument("n", IntegerArgumentType.integer(1, 65536))
                                                .executes(context -> {
                                                    final int m = IntegerArgumentType.getInteger(context, "m");
                                                    final int k = IntegerArgumentType.getInteger(context, "k");
                                                    final int nn = IntegerArgumentType.getInteger(context, "n");
                                                    context.getSource().sendSuccess(
                                                            () -> Component.literal("[NPU] bench started"), false);
                                                    Thread.ofVirtual().name("mcjavanpu-bench").start(() -> {
                                                        try {
                                                            final String out = NpuBench.run(m, k, nn, 1, 5).summary();
                                                            NpuLog.log(out);
                                                            context.getSource().sendSuccess(
                                                                    () -> Component.literal("[NPU] " + out), false);
                                                        } catch (Throwable t) {
                                                            context.getSource().sendSuccess(
                                                                    () -> Component.literal("[NPU] bench failed: " + t), false);
                                                        }
                                                    });
                                                    return 1;
                                                })))))
                .then(Commands.literal("diag")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] service=" + (NpuServiceClient.isAvailable() ? "UP" : "DOWN")
                                    + " lastFailure=" + (NpuServiceClient.lastFailure().isEmpty() ? "none" : NpuServiceClient.lastFailure())
                                    + " | " + NpuRuntime.getDiagnostics()
                                    + " | " + NpuStats.report().replace("\n", " ; ");
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        }))
                .then(Commands.literal("wgtest")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuWorldGen.selftest(12345L).replace("\n", " | ");
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        })
                        .then(Commands.argument("seed", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                .executes(context -> {
                                    long sd = com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "seed");
                                    final String l = "[NPU] " + NpuWorldGen.selftest(sd).replace("\n", " | ");
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    return 1;
                                })))
                .then(Commands.literal("noisecmp")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuNoiseCompare.run(12345L, 4096).replace("\n", " | ");
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        })
                        .then(Commands.argument("seed", com.mojang.brigadier.arguments.LongArgumentType.longArg())
                                .executes(context -> {
                                    long sd = com.mojang.brigadier.arguments.LongArgumentType.getLong(context, "seed");
                                    final String l = "[NPU] " + NpuNoiseCompare.run(sd, 4096).replace("\n", " | ");
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    return 1;
                                })))
                .then(Commands.literal("tengen")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] tengen: " + NpuTerrainGen.describe() + " | "
                                    + NpuTerrainGen.generate(16, 64, 16, 0, -64, 0, 12345L).summary();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        })
                        .then(Commands.literal("full")
                                .executes(context -> {
                                    final String l = "[NPU] tengen full: "
                                            + NpuTerrainGen.generate(16, 384, 16, 0, -64, 0, 12345L).summary();
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    return 1;
                                })))
                .then(Commands.literal("pipeline")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] pipeline: " + NpuTerrainPipeline.run().replace("\n", " | ");
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        }))
                .then(Commands.literal("terrainbench")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] terrain bench: " + NpuTerrainBench.run().replace("\n", " | ");
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            return 1;
                        }))
                .then(Commands.literal("terrain")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] terrain " + NpuTerrainHook.summary()
                                    + " | lattice " + NpuTerrainAccel.CELL_W + "x" + NpuTerrainAccel.CELL_H
                                    + " | noise feature " + (NpuStats.NOISE.enabled ? "on" : "off");
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            NpuLog.log(l);
                            return 1;
                        })
                        .then(Commands.literal("reset").executes(context -> {
                            NpuStats.NOISE.calls.set(0); NpuStats.NOISE.cells.set(0);
                            NpuStats.NOISE.npuUs.set(0); NpuStats.NOISE.hostUs.set(0);
                            context.getSource().sendSuccess(() -> Component.literal("[NPU] terrain counters cleared"), false);
                            return 1;
                        })))
                .then(Commands.literal("batch")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            final String l = "[NPU] " + NpuBatchManager.report() + "\n" + NpuBatchManager.shapeHint();
                            context.getSource().sendSuccess(() -> Component.literal(l), false);
                            NpuLog.log(l);
                            return 1;
                        })
                        .then(Commands.literal("reset").executes(context -> {
                            NpuBatchManager.reset();
                            context.getSource().sendSuccess(() -> Component.literal("[NPU] batch counters cleared"), false);
                            return 1;
                        }))
                        .then(Commands.argument("lattice", IntegerArgumentType.integer(1, 32))
                                .executes(context -> {
                                    NpuBatchManager.setLattice(IntegerArgumentType.getInteger(context, "lattice"));
                                    final String l = "[NPU] " + NpuBatchManager.report();
                                    context.getSource().sendSuccess(() -> Component.literal(l), false);
                                    return 1;
                                })))
                .then(Commands.literal("profile")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> runProfile(context, 10))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                .executes(context -> runProfile(context, IntegerArgumentType.getInteger(context, "seconds")))))
                .then(Commands.literal("reloadchunks")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> runReloadChunks(context, 2))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(0, 8))
                                .executes(context -> runReloadChunks(context, IntegerArgumentType.getInteger(context, "radius")))))
                .then(Commands.literal("info")
                        .requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal("[NPU] " + NpuRuntime.getDeviceInfo()), false);
                            return 1;
                        }))
                .then(Commands.literal("benchmark").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(context -> {
                    NpuRuntime.TestResult result = NpuRuntime.benchmark();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] benchmark=" + result.name()
                            + " detail=" + result.detail()), false);
                    return result.success() ? 1 : 0;
                })));
    }
}
