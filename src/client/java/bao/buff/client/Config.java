package bao.buff.client;

import bao.buff.Buff;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CONFIG_FILE_NAME = "buff.json";

    public static final int ATTRIBUTE_SWAP_MODE_OFF = 0;
    public static final int ATTRIBUTE_SWAP_MODE_BREACH = 1;
    public static final int ATTRIBUTE_SWAP_MODE_WIND = 2;
    public static final int ATTRIBUTE_SWAP_MODE_SMART = 3;
    public static final double TRIGGER_BOT_REACH_MIN = 3.0D;
    public static final double TRIGGER_BOT_REACH_MAX = 6.0D;

    public static boolean smallItemEnabled = true;
    public static double itemScale = 0.6;
    public static boolean swapBreachMace = false;
    public static boolean swapWindMace = false;
    public static boolean smartMaceSwap = false;
    public static boolean breakShield = false;
    public static boolean triggerBot = false;
    public static boolean ignoreCooldown = false;
    public static double triggerBotReach = 3.35D;
    public static boolean strongholdTrackerEnabled = false;
    public static boolean spearLungeEnabled = false;

    public static int getAttributeSwapMode() {
        if (swapBreachMace) {
            return ATTRIBUTE_SWAP_MODE_BREACH;
        }
        if (swapWindMace) {
            return ATTRIBUTE_SWAP_MODE_WIND;
        }
        if (smartMaceSwap) {
            return ATTRIBUTE_SWAP_MODE_SMART;
        }
        return ATTRIBUTE_SWAP_MODE_OFF;
    }

    public static void setAttributeSwapMode(int mode) {
        swapBreachMace = mode == ATTRIBUTE_SWAP_MODE_BREACH;
        swapWindMace = mode == ATTRIBUTE_SWAP_MODE_WIND;
        smartMaceSwap = mode == ATTRIBUTE_SWAP_MODE_SMART;
        normalize();
    }

    public static int cycleAttributeSwapMode() {
        int nextMode = switch (getAttributeSwapMode()) {
            case ATTRIBUTE_SWAP_MODE_BREACH -> ATTRIBUTE_SWAP_MODE_WIND;
            case ATTRIBUTE_SWAP_MODE_WIND -> ATTRIBUTE_SWAP_MODE_SMART;
            default -> ATTRIBUTE_SWAP_MODE_BREACH;
        };

        setAttributeSwapMode(nextMode);
        return nextMode;
    }

    public static String getAttributeSwapModeName() {
        return switch (getAttributeSwapMode()) {
            case ATTRIBUTE_SWAP_MODE_BREACH -> "Mode 1 - Breach Mace";
            case ATTRIBUTE_SWAP_MODE_WIND -> "Mode 2 - Wind Mace";
            case ATTRIBUTE_SWAP_MODE_SMART -> "Mode 3 - Smart Mace";
            default -> "Off";
        };
    }

    public static String getTriggerBotStatusName() {
        return triggerBot ? "ON" : "OFF";
    }

    public static String getTriggerBotModeName() {
        return ignoreCooldown ? "Spam Click (1 tick)" : "Check Cooldown";
    }

    public static String getTriggerBotReachName() {
        return String.format(java.util.Locale.ROOT, "%.2f blocks", triggerBotReach);
    }

    public static String getSpearLungeStatusName() {
        return spearLungeEnabled ? "ON" : "OFF";
    }

    public static void load() {
        Path configPath = getConfigPath();
        if (Files.notExists(configPath)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            apply(data);
        } catch (IOException | JsonParseException exception) {
            Buff.LOGGER.error("Failed to load config from {}", configPath, exception);
            save();
        }
    }

    public static void save() {
        normalize();

        Path configPath = getConfigPath();
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                GSON.toJson(snapshot(), writer);
            }
        } catch (IOException exception) {
            Buff.LOGGER.error("Failed to save config to {}", configPath, exception);
        }
    }

    private static void apply(ConfigData data) {
        if (data == null) {
            normalize();
            return;
        }

        if (data.smallItemEnabled != null) {
            smallItemEnabled = data.smallItemEnabled;
        }
        if (data.itemScale != null) {
            itemScale = data.itemScale;
        }
        if (data.swapBreachMace != null) {
            swapBreachMace = data.swapBreachMace;
        }
        if (data.swapWindMace != null) {
            swapWindMace = data.swapWindMace;
        }
        if (data.smartMaceSwap != null) {
            smartMaceSwap = data.smartMaceSwap;
        }
        if (data.breakShield != null) {
            breakShield = data.breakShield;
        }
        if (data.triggerBot != null) {
            triggerBot = data.triggerBot;
        }
        if (data.ignoreCooldown != null) {
            ignoreCooldown = data.ignoreCooldown;
        }
        if (data.triggerBotReach != null) {
            triggerBotReach = data.triggerBotReach;
        }
        if (data.strongholdTrackerEnabled != null) {
            strongholdTrackerEnabled = data.strongholdTrackerEnabled;
        }
        if (data.spearLungeEnabled != null) {
            spearLungeEnabled = data.spearLungeEnabled;
        }
        normalize();
    }

    private static ConfigData snapshot() {
        ConfigData data = new ConfigData();
        data.smallItemEnabled = smallItemEnabled;
        data.itemScale = itemScale;
        data.swapBreachMace = swapBreachMace;
        data.swapWindMace = swapWindMace;
        data.smartMaceSwap = smartMaceSwap;
        data.breakShield = breakShield;
        data.triggerBot = triggerBot;
        data.ignoreCooldown = ignoreCooldown;
        data.triggerBotReach = triggerBotReach;
        data.strongholdTrackerEnabled = strongholdTrackerEnabled;
        data.spearLungeEnabled = spearLungeEnabled;
        return data;
    }

    private static void normalize() {
        itemScale = Math.max(0.0, Math.min(1.0, itemScale));
        triggerBotReach = Math.max(TRIGGER_BOT_REACH_MIN, Math.min(TRIGGER_BOT_REACH_MAX, triggerBotReach));

        int enabledModes = 0;
        if (swapBreachMace) {
            enabledModes++;
        }
        if (swapWindMace) {
            enabledModes++;
        }
        if (smartMaceSwap) {
            enabledModes++;
        }

        if (enabledModes > 1) {
            if (smartMaceSwap) {
                swapBreachMace = false;
                swapWindMace = false;
            } else if (swapBreachMace) {
                swapWindMace = false;
                smartMaceSwap = false;
            } else {
                swapBreachMace = false;
                smartMaceSwap = false;
            }
        }
    }

    private static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
    }

    private static class ConfigData {
        private Boolean smallItemEnabled;
        private Double itemScale;
        private Boolean swapBreachMace;
        private Boolean swapWindMace;
        private Boolean smartMaceSwap;
        private Boolean breakShield;
        private Boolean triggerBot;
        private Boolean ignoreCooldown;
        private Double triggerBotReach;
        private Boolean strongholdTrackerEnabled;
        private Boolean spearLungeEnabled;
    }
}
