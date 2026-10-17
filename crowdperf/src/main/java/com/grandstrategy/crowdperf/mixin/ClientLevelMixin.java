package com.grandstrategy.crowdperf.mixin;

import com.grandstrategy.crowdperf.CrowdEntity;
import com.grandstrategy.crowdperf.CrowdPerf;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A batched villager only needs to move: {@code commonTick} keeps its position interpolating towards what the server
 * sent, and the body faces where it walks. The full tick (fluids, equipment, head turning, walk animation) is skipped;
 * it resumes as soon as the villager is close enough to be drawn as a model again.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void crowdperf$lightTick(Entity entity, CallbackInfo ci) {
        if (!((CrowdEntity) entity).crowdperf$batched() || !CrowdPerf.config().lightTick || entity.isVehicle()) return;
        entity.commonTick();
        if (entity instanceof LivingEntity living) {
            living.yBodyRotO = living.yBodyRot;
            living.yBodyRot = living.getYRot();
        }
        ci.cancel();
    }
}
