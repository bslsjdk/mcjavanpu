package bslsjdk.mcjavanpu;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

public final class McJavaNpuClient implements ClientModInitializer {
    private static KeyMapping openScreenKey;

    @Override
    public void onInitializeClient() {
        openScreenKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mcjavanpu.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                "category.mcjavanpu"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openScreenKey.consumeClick()) {
                client.setScreen(new NpuScreen());
            }
        });
    }
}