package bao.buff.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

public class AttributeSwapManager {
    public static int slotToRevert = -1;
    public static int ticksToWait = 0;

    public static void onTick() {
        if (slotToRevert != -1) {
            if (ticksToWait > 0) {
                ticksToWait--;
            } else {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.getInventory().setSelectedSlot(slotToRevert);
                    mc.player.connection.send(new ServerboundSetCarriedItemPacket(slotToRevert));
                }
                slotToRevert = -1;
            }
        }
    }

    public static boolean canTriggerAttributeSwap(ItemStack stack) {
        return stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(ItemTags.SPEARS);
    }

    public static int findAxe(Inventory inventory) {
        for (int i = 0; i < 9; i++) {
            if (inventory.getItem(i).is(ItemTags.AXES)) {
                return i;
            }
        }
        return -1;
    }

    public static int findMace(Inventory inventory, ResourceKey<Enchantment> enchantKey) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inventory.getItem(i);
            if (stack.is(Items.MACE) && hasEnchantment(stack, enchantKey)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean hasEnchantment(ItemStack stack, ResourceKey<Enchantment> targetKey) {
        for (Holder<Enchantment> holder : stack.getEnchantments().keySet()) {
            if (holder.is(targetKey)) {
                return true;
            }
        }
        return false;
    }
}
