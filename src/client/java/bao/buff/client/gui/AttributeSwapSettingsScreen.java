package bao.buff.client.gui;

import bao.buff.client.Config;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class AttributeSwapSettingsScreen extends Screen {
    private final Screen lastScreen;

    private Button breachBtn;
    private Button windBtn;
    private Button smartBtn;

    public AttributeSwapSettingsScreen(Screen lastScreen) {
        super(Component.literal("Attribute Swap Setup"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2 - 100;

        breachBtn = Button.builder(getLabel("Mode 1 - Breach Mace", Config.swapBreachMace), button -> toggleMode(Config.ATTRIBUTE_SWAP_MODE_BREACH))
            .bounds(centerX, 40, 200, 20)
            .build();

        windBtn = Button.builder(getLabel("Mode 2 - Wind Mace", Config.swapWindMace), button -> toggleMode(Config.ATTRIBUTE_SWAP_MODE_WIND))
            .bounds(centerX, 70, 200, 20)
            .build();

        smartBtn = Button.builder(getLabel("Mode 3 - Smart Mace", Config.smartMaceSwap), button -> toggleMode(Config.ATTRIBUTE_SWAP_MODE_SMART))
            .bounds(centerX, 100, 200, 20)
            .build();

        Button breakShieldBtn = Button.builder(getLabel("Break Shield (Axe)", Config.breakShield), button -> {
            Config.breakShield = !Config.breakShield;
            Config.save();
            button.setMessage(getLabel("Break Shield (Axe)", Config.breakShield));
        }).bounds(centerX, 140, 200, 20).build();

        Button backBtn = Button.builder(Component.literal("Back"), button -> this.minecraft.setScreen(lastScreen))
            .bounds(centerX, this.height - 40, 200, 20)
            .build();

        this.addRenderableWidget(breachBtn);
        this.addRenderableWidget(windBtn);
        this.addRenderableWidget(smartBtn);
        this.addRenderableWidget(breakShieldBtn);
        this.addRenderableWidget(backBtn);
    }

    private void toggleMode(int mode) {
        if (Config.getAttributeSwapMode() == mode) {
            Config.setAttributeSwapMode(Config.ATTRIBUTE_SWAP_MODE_OFF);
        } else {
            Config.setAttributeSwapMode(mode);
        }

        updateButtons();
        Config.save();
    }

    private Component getLabel(String name, boolean state) {
        return Component.literal(name + ": " + (state ? "ON" : "OFF"));
    }

    private void updateButtons() {
        breachBtn.setMessage(getLabel("Mode 1 - Breach Mace", Config.swapBreachMace));
        windBtn.setMessage(getLabel("Mode 2 - Wind Mace", Config.swapWindMace));
        smartBtn.setMessage(getLabel("Mode 3 - Smart Mace", Config.smartMaceSwap));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFF00);
    }
}
