package bao.buff.client.gui;

import bao.buff.client.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class TriggerBotSettingsScreen extends Screen {
    private final Screen lastScreen;

    public TriggerBotSettingsScreen(Screen lastScreen) {
        super(Component.literal("Trigger Bot Settings"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2 - 100;
        int centerY = 60;

        this.addRenderableWidget(Button.builder(getToggleLabel(), button -> {
            Config.triggerBot = !Config.triggerBot;
            Config.save();
            button.setMessage(getToggleLabel());
        }).bounds(centerX, centerY, 200, 20).build());

        this.addRenderableWidget(Button.builder(getModeLabel(), button -> {
            Config.ignoreCooldown = !Config.ignoreCooldown;
            Config.save();
            button.setMessage(getModeLabel());
        }).bounds(centerX, centerY + 30, 200, 20).build());

        this.addRenderableWidget(new AbstractSliderButton(
            centerX,
            centerY + 60,
            200,
            20,
            Component.literal("Player Reach"),
            reachToSliderValue(Config.triggerBotReach)
        ) {
            {
                updateMessage();
            }

            @Override
            protected void updateMessage() {
                setMessage(Component.literal("Player Reach: §e" + formatReach(sliderValueToReach(this.value)) + " blocks"));
            }

            @Override
            protected void applyValue() {
                Config.triggerBotReach = sliderValueToReach(this.value);
                Config.save();
            }
        });

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            this.minecraft.setScreen(lastScreen);
        }).bounds(centerX, this.height - 40, 200, 20).build());
    }

    private static double reachToSliderValue(double reach) {
        double range = Config.TRIGGER_BOT_REACH_MAX - Config.TRIGGER_BOT_REACH_MIN;
        if (range <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, (reach - Config.TRIGGER_BOT_REACH_MIN) / range));
    }

    private static double sliderValueToReach(double value) {
        double range = Config.TRIGGER_BOT_REACH_MAX - Config.TRIGGER_BOT_REACH_MIN;
        double reach = Config.TRIGGER_BOT_REACH_MIN + value * range;
        return Math.round(reach * 100.0D) / 100.0D;
    }

    private static String formatReach(double reach) {
        return String.format(java.util.Locale.ROOT, "%.2f", reach);
    }

    private Component getToggleLabel() {
        return Component.literal("Trigger Bot: " + (Config.triggerBot ? "§aON" : "§cOFF"));
    }

    private Component getModeLabel() {
        if (Config.ignoreCooldown) {
            return Component.literal("Mode: §eSpam Click (1 tick)");
        }
        return Component.literal("Mode: §bCheck Cooldown");
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }
}
