package com.grandstrategy.crowdperf.mixin;

import com.grandstrategy.crowdperf.Detail;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Far villagers cast no shadow; see {@link Detail}. */
@Mixin(EntityRenderer.class)
public abstract class EntityShadowMixin {
    @Inject(method = "extractShadow", at = @At("HEAD"), cancellable = true)
    private void crowdperf$skipFar(EntityRenderState state, Minecraft minecraft, Level level, CallbackInfo ci) {
        if (Detail.reduced(state)) ci.cancel();
    }
}
