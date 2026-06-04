package bao.buff.client.mixin;

import bao.buff.client.AttributeSwapManager;
import bao.buff.client.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantments;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

    @Inject(method = "attack", at = @At("HEAD"))
    private void onAttack(Player player, Entity target, CallbackInfo ci) {
        if (!AttributeSwapManager.canTriggerAttributeSwap(player.getMainHandItem())) {
            return;
        }

        int currentSlot = player.getInventory().getSelectedSlot();
        int targetSlot = -1;

        if (Config.breakShield && target instanceof LivingEntity livingTarget && livingTarget.isBlocking()) {
            targetSlot = AttributeSwapManager.findAxe(player.getInventory());
        }

        if (targetSlot == -1) {
            if (Config.smartMaceSwap) {
                if (player.getY() >= target.getY() + 2.0D) {
                    targetSlot = AttributeSwapManager.findMace(player.getInventory(), Enchantments.WIND_BURST);
                } else {
                    targetSlot = AttributeSwapManager.findMace(player.getInventory(), Enchantments.BREACH);
                }
            } else if (Config.swapBreachMace) {
                targetSlot = AttributeSwapManager.findMace(player.getInventory(), Enchantments.BREACH);
            } else if (Config.swapWindMace) {
                targetSlot = AttributeSwapManager.findMace(player.getInventory(), Enchantments.WIND_BURST);
            }
        }

        if (targetSlot != -1 && targetSlot != currentSlot) {
            player.getInventory().setSelectedSlot(targetSlot);

            if (Minecraft.getInstance().getConnection() != null) {
                Minecraft.getInstance().getConnection().send(new ServerboundSetCarriedItemPacket(targetSlot));
            }

            AttributeSwapManager.slotToRevert = currentSlot;
            AttributeSwapManager.ticksToWait = 0;
        }
    }
}
