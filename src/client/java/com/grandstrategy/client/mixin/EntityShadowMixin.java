package com.grandstrategy.client.mixin;

import com.grandstrategy.client.render.VillagerLod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far villagers cast no shadow; see {@link VillagerLod}. */
@Mixin(EntityRenderer.class)
public abstract class EntityShadowMixin {
    @Inject(method = "extractShadow", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$skipFar(EntityRenderState state, Minecraft minecraft, Level level, CallbackInfo ci) {
        if (VillagerLod.far(state)) ci.cancel();
    }
}
