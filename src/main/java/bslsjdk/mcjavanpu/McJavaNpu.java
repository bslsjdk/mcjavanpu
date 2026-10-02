package bslsjdk.mcjavanpu;

import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class McJavaNpu implements ModInitializer {
    public static final String MOD_ID = "mcjavanpu";

    @Override
    public void onInitialize() {
        NpuRuntime.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> registerCommands(dispatcher));
        System.out.println("[MCJavaNPU] initialized");
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("npu")
                .then(Commands.literal("status").executes(context -> {
                    boolean available = NpuRuntime.isAvailable();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] runtime=" + NpuRuntime.isInitialized()
                            + " available=" + available + " device=" + NpuRuntime.getDeviceInfo()), false);
                    return available ? 1 : 0;
                }))
                .then(Commands.literal("test").executes(context -> {
                    NpuRuntime.TestResult result = NpuRuntime.test();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] test=" + result.name()
                            + " detail=" + result.detail()), false);
                    return result.success() ? 1 : 0;
                }))
                .then(Commands.literal("benchmark").executes(context -> {
                    NpuRuntime.TestResult result = NpuRuntime.benchmark();
                    context.getSource().sendSuccess(() -> Component.literal("[NPU] benchmark=" + result.name()
                            + " detail=" + result.detail()), false);
                    return result.success() ? 1 : 0;
                })));
    }
}
