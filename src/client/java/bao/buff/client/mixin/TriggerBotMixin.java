// Đường dẫn: src/client/java/bao/buff/client/mixin/TriggerBotMixin.java

package bao.buff.client.mixin;

import bao.buff.client.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class TriggerBotMixin {
    @Shadow public HitResult hitResult;
    @Shadow public net.minecraft.client.player.LocalPlayer player;
    @Shadow public net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode;

    @Inject(at = @At("HEAD"), method = "tick")
    private void onTick(CallbackInfo info) {
        // Chỉ chạy nếu Trigger Bot được bật và đang ở trong thế giới
        if (Config.triggerBot && player != null && hitResult != null) {
            
            // Kiểm tra xem crosshair có đang chỉ vào một Entity sống hay không
            if (hitResult.getType() == HitResult.Type.ENTITY) {
                Entity target = ((EntityHitResult) hitResult).getEntity();
                
                if (target instanceof LivingEntity livingTarget && livingTarget.isAlive()) {
                    
                    // Kiểm tra Cooldown: Nếu bật IgnoreCooldown thì bỏ qua, 
                    // nếu không thì phải đợi thanh lực đầy (>= 1.0f)
                    if (Config.ignoreCooldown || player.getAttackStrengthScale(0.0f) >= 1.0f) {
                        
                        // Thực hiện đòn đánh
                        gameMode.attack(player, target);
                        player.swing(InteractionHand.MAIN_HAND);
                    }
                }
            }
        }
    }
}