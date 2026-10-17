package com.grandstrategy.crowdperf.mixin;

import com.grandstrategy.crowdperf.CrowdEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Batched villagers are skipped by the normal entity pass before anything is computed for them: no culling test, no
 * render state, no model. That per-entity work is most of a crowd's cost.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
    @Inject(method = "isEntityVisible", at = @At("HEAD"), cancellable = true)
    private void crowdperf$skipBatched(Entity entity, Frustum frustum, double camX, double camY, double camZ, float partialTicks, long chunkFadeDuration,
                                       CallbackInfoReturnable<Boolean> cir) {
        if (((CrowdEntity) entity).crowdperf$batched()) cir.setReturnValue(false);
    }
}
