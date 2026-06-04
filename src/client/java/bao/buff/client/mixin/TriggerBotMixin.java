package bao.buff.client.mixin;

import bao.buff.client.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.InteractionHand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Optional;

@Mixin(Minecraft.class)
public class TriggerBotMixin {
    @Shadow public net.minecraft.client.player.LocalPlayer player;
    @Shadow public net.minecraft.client.multiplayer.MultiPlayerGameMode gameMode;

    @Inject(at = @At("HEAD"), method = "tick")
    private void onTick(CallbackInfo info) {
        if (!Config.triggerBot || player == null || gameMode == null || player.level() == null) return;

        if (!Config.ignoreCooldown && player.getAttackStrengthScale(0.0f) < 1.0f) return;

        Entity target = getLatentTarget(3.0D + Config.triggerBotExtraReach);

        if (target instanceof LivingEntity living && living.isAlive() && target != player) {
            gameMode.attack(player, target);
            player.swing(InteractionHand.MAIN_HAND);
        }
    }

    private Entity getLatentTarget(double baseMaxReach) {
        if (player == null || player.level() == null) return null;

        Vec3 eyePosition = player.getEyePosition(1.0F);
        Vec3 viewVector = player.getViewVector(1.0F);
        Vec3 reachEnd = eyePosition.add(viewVector.x * baseMaxReach, viewVector.y * baseMaxReach, viewVector.z * baseMaxReach);

        ClipContext blockClipContext = new ClipContext(eyePosition, reachEnd, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player);
        HitResult blockHit = player.level().clip(blockClipContext);
        
        double currentMaxReach = baseMaxReach;
        if (blockHit.getType() == HitResult.Type.BLOCK) {
            currentMaxReach = eyePosition.distanceTo(blockHit.getLocation());
        }

        AABB searchBox = player.getBoundingBox().expandTowards(viewVector.scale(currentMaxReach)).inflate(0.5D); 
        List<LivingEntity> livingEntities = player.level().getEntitiesOfClass(
                LivingEntity.class, searchBox,
                entity -> entity != null && entity.isAlive() && entity != player && entity.isPickable()
        );

        Entity closestTarget = null;
        double closestDistance = currentMaxReach;

        Minecraft mc = Minecraft.getInstance();
        var connection = mc.getConnection();

        for (LivingEntity living : livingEntities) {
            double entityMaxReach = (living instanceof Player) ? currentMaxReach : Math.min(3.0D, currentMaxReach);
            double currentLimit = Math.min(closestDistance, entityMaxReach);

            float pickRadius = living.getPickRadius();
            AABB entityHitbox = living.getBoundingBox().inflate(pickRadius);
            
            if (entityHitbox.distanceToSqr(eyePosition) > currentLimit * currentLimit) {
                continue; 
            }

            if (entityHitbox.contains(eyePosition)) {
                closestTarget = living;
                closestDistance = 0.0D;
                continue; 
            }

            double dynamicReach = entityMaxReach;
            if (connection != null) {
                var localPlayerInfo = connection.getPlayerInfo(player.getUUID());
                if (localPlayerInfo != null) {
                    int playerPing = localPlayerInfo.getLatency();
                    Vec3 directionToTarget = new Vec3(living.getX() - player.getX(), 0.0D, living.getZ() - player.getZ());
                    double dirLengthSqr = directionToTarget.x * directionToTarget.x + directionToTarget.z * directionToTarget.z;

                    if (dirLengthSqr > 1.0E-5D) {
                        Vec3 horizontalDirection = directionToTarget.normalize();
                        Vec3 playerVelocity = player.getDeltaMovement();
                        Vec3 targetVelocity = living.getDeltaMovement();
                        
                        Vec3 relativeVelocity = playerVelocity.subtract(targetVelocity);
                        Vec3 horizontalRelativeVel = new Vec3(relativeVelocity.x, 0.0D, relativeVelocity.z);

                        double relativeForwardSpeedPerTick = horizontalRelativeVel.dot(horizontalDirection);
                        if (relativeForwardSpeedPerTick > 0.0D) {
                            double relativeSpeedSeconds = Math.min(6.5D, relativeForwardSpeedPerTick * 20.0D);
                            double networkCompensation = (playerPing / 1000.0D) * relativeSpeedSeconds;
                            dynamicReach = Math.min(entityMaxReach, dynamicReach + networkCompensation);
                        }
                    }
                }
            }

            double finalAllowedReach = (living instanceof Player) ? dynamicReach : Math.min(3.0D, dynamicReach);
            double effectiveLimit = Math.min(closestDistance, finalAllowedReach);

            Optional<Vec3> optionalHit = entityHitbox.clip(eyePosition, eyePosition.add(viewVector.x * effectiveLimit, viewVector.y * effectiveLimit, viewVector.z * effectiveLimit));
            if (optionalHit.isPresent()) {
                double distanceToHit = eyePosition.distanceTo(optionalHit.get());
                if (distanceToHit < closestDistance) {
                    closestTarget = living;
                    closestDistance = distanceToHit;
                }
            }
        }

        return closestTarget;
    }
}
