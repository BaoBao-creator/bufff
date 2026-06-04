package bao.buff.client;

import bao.buff.client.mixin.MinecraftAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.Locale;

public final class SpearLungeManager {
    private SpearLungeManager() {
    }

    public static boolean tryActivate(Minecraft client) {
        if (!Config.spearLungeEnabled || client == null || client.player == null || client.gameMode == null || client.screen != null) {
            return false;
        }

        Inventory inventory = client.player.getInventory();
        int originalSlot = inventory.getSelectedSlot();
        int spearSlot = findLungeSpearSlot(inventory);
        if (spearSlot == -1) {
            return false;
        }

        inventory.setSelectedSlot(spearSlot);
        ((MinecraftAccessor) client).buff$invokeStartAttack();

        if (spearSlot != originalSlot) {
            inventory.setSelectedSlot(originalSlot);
            if (client.getConnection() != null) {
                client.getConnection().send(new ServerboundSetCarriedItemPacket(originalSlot));
            }
        }

        return true;
    }

    private static int findLungeSpearSlot(Inventory inventory) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isLungeSpear(stack)) {
                return slot;
            }
        }

        return -1;
    }

    private static boolean isLungeSpear(ItemStack stack) {
        return !stack.isEmpty() && isSpear(stack) && hasLunge(stack);
    }

    private static boolean isSpear(ItemStack stack) {
        String itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().toLowerCase(Locale.ROOT);
        if (itemKey.contains("spear")) {
            return true;
        }

        String displayName = stack.getHoverName().getString().toLowerCase(Locale.ROOT);
        return displayName.contains("spear");
    }

    private static boolean hasLunge(ItemStack stack) {
        for (Holder<Enchantment> holder : stack.getEnchantments().keySet()) {
            if (isLungeEnchantment(holder)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isLungeEnchantment(Holder<Enchantment> holder) {
        String holderName = holder.toString().toLowerCase(Locale.ROOT);
        if (holderName.contains("lunge")) {
            return true;
        }

        return holder.value().description().getString().toLowerCase(Locale.ROOT).contains("lunge");
    }
}
