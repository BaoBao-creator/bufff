package bao.buff.client.mixin;

import bao.buff.client.Config;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemInHandRenderer.class)
public class ItemInHandRendererMixin {

    @Inject(method = "renderItem", at = @At("HEAD"))
    private void onRenderItem(
        LivingEntity entity,
        ItemStack itemStack,
        ItemDisplayContext displayContext,
        PoseStack poseStack,
        SubmitNodeCollector submitNodeCollector,
        int light,
        CallbackInfo ci
    ) {
        if (displayContext.firstPerson() && Config.smallItemEnabled) {
            float scale = (float) Config.itemScale;
            poseStack.scale(scale, scale, scale);
        }
    }
}
