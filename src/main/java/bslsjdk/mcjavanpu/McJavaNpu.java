package bslsjdk.mcjavanpu;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.network.chat.Component;

public final class McJavaNpu implements ModInitializer {
    public static final String MOD_ID = "mcjavanpu";

    @Override
    public void onInitialize() {
        Thread.ofVirtual().name("mcjavanpu-init").start(NpuRuntime::init);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        System.out.println("[MCJavaNPU] initialized");
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

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("npu")
                .executes(context -> { context.getSource().sendSuccess(() -> Component.literal("[NPU] /npu status|test|addtest|benchmark"), false); return 1; })
                .then(Commands.literal("status").executes(context -> {
                    boolean available = NpuRuntime.isAvailable();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] runtime=" + NpuRuntime.isInitialized()
                            + " available=" + available + " device=" + NpuRuntime.getDeviceInfo()), false);
                    return available ? 1 : 0;
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
