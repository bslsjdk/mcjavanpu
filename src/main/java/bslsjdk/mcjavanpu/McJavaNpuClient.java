package bslsjdk.mcjavanpu;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;

public final class McJavaNpuClient implements ClientModInitializer {
    private static KeyMapping openScreenKey;

    @Override
    public void onInitializeClient() {
        // The native QNN/HTP runtime must be initialized before the status
        // screen can report availability. Keep it initialized for the client
        // lifetime so the QNN context can be reused by future workloads.
        NpuRuntime.init();

        KeyMapping.Category category = KeyMapping.Category.register(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("mcjavanpu", "npu")
        );

        openScreenKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.mcjavanpu.open",
                InputConstants.Type.KEYBOARD,
                InputConstants.UNKNOWN.getValue(),
                category
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openScreenKey.consumeClick()) {
                client.gui.setScreen(new NpuScreen());
            }
        });
    }
}
