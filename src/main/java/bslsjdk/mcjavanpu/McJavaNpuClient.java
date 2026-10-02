package bslsjdk.mcjavanpu;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class McJavaNpuClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        KeyMapping key=KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.mcjavanpu.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F8, "category.mcjavanpu"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while(key.consumeClick()) client.setScreen(new NpuScreen());
        });
    }
}