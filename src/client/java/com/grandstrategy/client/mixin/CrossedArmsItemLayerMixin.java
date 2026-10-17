package com.grandstrategy.client.mixin;

import com.grandstrategy.client.render.VillagerLod;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.layers.CrossedArmsItemLayer;
import net.minecraft.client.renderer.entity.state.HoldingEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far villagers skip this layer; see {@link VillagerLod}. */
@Mixin(CrossedArmsItemLayer.class)
public abstract class CrossedArmsItemLayerMixin {
    @Inject(method = "submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ILnet/minecraft/client/renderer/entity/state/HoldingEntityRenderState;FF)V", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$skipFar(PoseStack pose, SubmitNodeCollector collector, int light, HoldingEntityRenderState state, float yRot, float xRot, CallbackInfo ci) {
        if (VillagerLod.far(state)) ci.cancel();
    }
}
