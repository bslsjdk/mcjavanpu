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

    private static int runMatMul(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context, int m, int k, int n) {
        String result = NpuRuntime.matMul(m, k, n);
        context.getSource().sendSuccess(() -> Component.literal("[NPU] matmul=" + result), false);
        return result.startsWith("OK ") ? 1 : 0;
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
                .then(Commands.literal("benchmark").requires(s -> s.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)).executes(context -> {
                    NpuRuntime.TestResult result = NpuRuntime.benchmark();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] benchmark=" + result.name()
                            + " detail=" + result.detail()), false);
                    return result.success() ? 1 : 0;
                })));
    }
}
