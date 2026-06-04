package bao.buff.client.gui;

import bao.buff.client.Config;
import bao.buff.client.StrongholdTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

public class StrongholdTrackerSettingsScreen extends Screen {
    private static final int PANEL_COLOR = 0x6F000000;
    private static final int TITLE_COLOR = 0xFFFFD966;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int HIGHLIGHT_COLOR = 0xFFA5FF8C;
    private final Screen lastScreen;

    public StrongholdTrackerSettingsScreen(Screen lastScreen) {
        super(Component.literal("Stronghold Tracker"));
        this.lastScreen = lastScreen;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2 - 110;
        int buttonWidth = 220;

        this.addRenderableWidget(Button.builder(getToggleLabel(), button -> {
            Config.strongholdTrackerEnabled = !Config.strongholdTrackerEnabled;
            Config.save();
            button.setMessage(getToggleLabel());
        }).bounds(centerX, 44, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Reset Saved Eyes"), button -> {
            StrongholdTracker.manualReset();
        }).bounds(centerX, 72, buttonWidth, 20).build());

        this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> {
            this.minecraft.setScreen(lastScreen);
        }).bounds(centerX, this.height - 40, buttonWidth, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 18, TITLE_COLOR);

        int panelWidth = Math.min(this.width - 32, 300);
        int panelLeft = (this.width - panelWidth) / 2;
        int panelTop = 102;
        int panelBottom = this.height - 52;
        int lineHeight = this.font.lineHeight + 3;

        List<DetailLine> detailLines = List.of(
            new DetailLine(StrongholdTracker.getSavedEyesLine(), TEXT_COLOR),
            new DetailLine(StrongholdTracker.getLatestThrowLine(), TEXT_COLOR),
            new DetailLine(StrongholdTracker.getOverworldEstimateLine(), StrongholdTracker.hasEstimate() ? HIGHLIGHT_COLOR : TEXT_COLOR),
            new DetailLine(StrongholdTracker.getNetherEstimateLine(), TEXT_COLOR),
            new DetailLine(StrongholdTracker.getActiveTargetLine(), TEXT_COLOR),
            new DetailLine(StrongholdTracker.getAccuracyLine(), TEXT_COLOR),
            new DetailLine("Dimension: " + StrongholdTracker.getDimensionLabel(), TEXT_COLOR),
            new DetailLine("Status: " + StrongholdTracker.getStatusMessage(), TEXT_COLOR)
        );

        guiGraphics.fill(panelLeft - 6, panelTop - 6, panelLeft + panelWidth + 6, panelBottom + 6, PANEL_COLOR);

        int y = panelTop;
        for (DetailLine line : detailLines) {
            List<FormattedCharSequence> wrappedLines = new ArrayList<>(this.font.split(Component.literal(line.text()), panelWidth));
            if (wrappedLines.isEmpty()) {
                continue;
            }

            for (FormattedCharSequence wrappedLine : wrappedLines) {
                if (y + this.font.lineHeight > panelBottom) {
                    guiGraphics.drawString(this.font, "...", panelLeft, y, TEXT_COLOR, false);
                    return;
                }

                guiGraphics.drawString(this.font, wrappedLine, panelLeft, y, line.color(), false);
                y += lineHeight;
            }

            y += 2;
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(lastScreen);
        }
    }

    private Component getToggleLabel() {
        return Component.literal("Stronghold Tracker: " + (Config.strongholdTrackerEnabled ? "ON" : "OFF"));
    }

    private record DetailLine(String text, int color) {
    }
}
