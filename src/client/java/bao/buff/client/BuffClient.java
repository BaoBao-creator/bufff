package bao.buff.client;

import bao.buff.Buff;
import bao.buff.client.gui.BuffMainMenu;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class BuffClient implements ClientModInitializer {
    private static final KeyMapping.Category MAIN_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("buff", "main"));

    private static KeyMapping openMenuKey;
    private static KeyMapping toggleTriggerBotKey;
    private static KeyMapping switchTriggerModeKey;
    private static KeyMapping switchAttributeSwapModeKey;
    private static KeyMapping useSpearLungeKey;

    @Override
    public void onInitializeClient() {
        Config.load();
        HudRenderCallback.EVENT.register(StrongholdTracker::renderHud);
        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(StrongholdTracker::renderWorldMarker);
        ClientEntityEvents.ENTITY_LOAD.register(StrongholdTracker::onEntityLoad);
        ClientEntityEvents.ENTITY_UNLOAD.register(StrongholdTracker::onEntityUnload);

        openMenuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.buff.open_menu",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_RIGHT_SHIFT,
            MAIN_CATEGORY
        ));

        toggleTriggerBotKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.buff.trigger_bot.toggle",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            MAIN_CATEGORY
        ));

        switchTriggerModeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.buff.trigger_bot.switch_mode",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            MAIN_CATEGORY
        ));

        switchAttributeSwapModeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.buff.attribute_swap.switch_mode",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            MAIN_CATEGORY
        ));

        useSpearLungeKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
            "key.buff.spear_lunge.activate",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            MAIN_CATEGORY
        ));


        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            StrongholdTracker.onClientTick(client);

            while (openMenuKey.consumeClick()) {
                openMainMenu(client);
            }

            while (toggleTriggerBotKey.consumeClick()) {
                Config.triggerBot = !Config.triggerBot;
                Config.save();
                showHotkeyMessage(client, "Trigger Bot: " + Config.getTriggerBotStatusName());
            }

            while (switchTriggerModeKey.consumeClick()) {
                Config.ignoreCooldown = !Config.ignoreCooldown;
                Config.save();
                showHotkeyMessage(client, "Trigger Bot Mode: " + Config.getTriggerBotModeName());
            }

            while (switchAttributeSwapModeKey.consumeClick()) {
                Config.cycleAttributeSwapMode();
                Config.save();
                showHotkeyMessage(client, "Attribute Swap: " + Config.getAttributeSwapModeName());
            }

            while (useSpearLungeKey.consumeClick()) {
                if (!Config.spearLungeEnabled) {
                    continue;
                }

                if (!SpearLungeManager.tryActivate(client)) {
                    showHotkeyMessage(client, "Spear Lunge: No spear with lunge found in hotbar");
                }
            }
        });
    }

    private static void openMainMenu(net.minecraft.client.Minecraft client) {
        if (client.screen != null) {
            return;
        }

        try {
            client.setScreen(new BuffMainMenu());
        } catch (RuntimeException exception) {
            Buff.LOGGER.error("Failed to open the Buff main menu", exception);
            if (client.player != null) {
                client.player.displayClientMessage(Component.literal("Could not open the Buff menu. Check the log for details."), true);
            }
        }
    }

    private static void showHotkeyMessage(net.minecraft.client.Minecraft client, String message) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal(message), true);
        }
    }
}
