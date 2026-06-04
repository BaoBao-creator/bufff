package bao.buff.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

public class BuffMainMenu extends Screen {
    private EditBox searchBox;
    private final List<Feature> allFeatures = new ArrayList<>();
    private final List<Button> featureButtons = new ArrayList<>();

    public BuffMainMenu() {
        super(Component.literal("Buff Mod Menu"));

        allFeatures.add(new Feature(
            "Small Items",
            () -> Component.literal("Small Items >"),
            () -> openSubScreen(new SmallItemSettingsScreen(this))
        ));

        allFeatures.add(new Feature(
            "Attribute Swap",
            () -> Component.literal("Attribute Swap >"),
            () -> openSubScreen(new AttributeSwapSettingsScreen(this))
        ));

        allFeatures.add(new Feature(
            "Trigger Bot",
            () -> Component.literal("Trigger Bot >"),
            () -> openSubScreen(new TriggerBotSettingsScreen(this))
        ));

        allFeatures.add(new Feature(
            "Stronghold Tracker",
            () -> Component.literal("Stronghold Tracker >"),
            () -> openSubScreen(new StrongholdTrackerSettingsScreen(this))
        ));

        allFeatures.add(new Feature(
            "Spear Lunge",
            () -> Component.literal("Spear Lunge >"),
            () -> openSubScreen(new SpearLungeSettingsScreen(this))
        ));

        allFeatures.sort(Comparator.comparing(feature -> feature.baseName));
    }

    @Override
    protected void init() {
        this.searchBox = new EditBox(this.font, this.width / 2 - 100, 40, 200, 20, Component.literal("Search..."));
        this.searchBox.setHint(Component.literal("Tim kiem chuc nang..."));
        this.searchBox.setResponder(this::updateSearch);

        this.addRenderableWidget(this.searchBox);
        this.setInitialFocus(this.searchBox);
        updateSearch(this.searchBox.getValue());
    }

    private void updateSearch(String query) {
        String normalizedQuery = query == null ? "" : query.toLowerCase(Locale.ROOT);

        for (Button button : featureButtons) {
            this.removeWidget(button);
        }
        featureButtons.clear();

        int yOffset = 70;
        for (Feature feature : allFeatures) {
            if (feature.baseName.toLowerCase(Locale.ROOT).contains(normalizedQuery)) {
                Button button = Button.builder(feature.labelSupplier.get(), pressed -> {
                    feature.action.run();
                    pressed.setMessage(feature.labelSupplier.get());
                }).bounds(this.width / 2 - 100, yOffset, 200, 20).build();

                this.addRenderableWidget(button);
                featureButtons.add(button);
                yOffset += 25;
            }
        }
    }

    private void openSubScreen(Screen screen) {
        if (this.minecraft != null) {
            this.minecraft.setScreen(screen);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 15, 0xFFFF00);
    }

    private record Feature(String baseName, Supplier<Component> labelSupplier, Runnable action) {
    }
}
