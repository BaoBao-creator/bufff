package bao.buff.client.gui;

import bao.buff.client.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SpearLungeSettingsScreen extends Screen {
    private final Screen lastScreen;

    public SpearLungeSettingsScreen(Screen lastScreen) {
        super(Component.literal("Spear Lunge"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2 - 100;

        this.addRenderableWidget(Button.builder(getToggleLabel(), button -> {
            Config.spearLungeEnabled = !Config.spearLungeEnabled;
            Config.save();
            button.setMessage(getToggleLabel());
        }).bounds(centerX, 60, 200, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            this.minecraft.setScreen(lastScreen);
        }).bounds(centerX, this.height - 40, 200, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    private Component getToggleLabel() {
        return Component.literal("Spear Lunge: " + Config.getSpearLungeStatusName());
    }
}
