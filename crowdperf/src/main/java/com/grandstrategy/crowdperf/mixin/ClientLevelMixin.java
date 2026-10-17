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
 * A batched villager only needs what the crowd shader draws: {@code commonTick} keeps its position interpolating
 * towards what the server sent, the head eases towards its target as in {@code aiStep}, the body faces where it walks
 * and the walk cycle advances with the distance moved. The rest of the tick (fluids, equipment, effects) is skipped;
 * it resumes as soon as the villager is close enough to be drawn as a model again.
 */
// Applied before other mods' hooks on the same method (Entity Culling cancels it at HEAD to run its own tick), so a
// batched villager gets the light tick instead of a full one.
@Mixin(value = ClientLevel.class, priority = 500)
public abstract class ClientLevelMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void crowdperf$lightTick(Entity entity, CallbackInfo ci) {
        if (!((CrowdEntity) entity).crowdperf$batched() || !CrowdPerf.config().lightTick || entity.isVehicle()) return;
        if (entity instanceof LivingEntity living) {
            living.yHeadRotO = living.yHeadRot;
            living.yBodyRotO = living.yBodyRot;
        }
        entity.commonTick();
        if (entity instanceof LivingEntity living) {
            LivingEntityAccessor head = (LivingEntityAccessor) living;
            int steps = head.crowdperf$lerpHeadSteps();
            if (steps > 0) {
                head.crowdperf$lerpHeadRotationStep(steps, head.crowdperf$lerpYHeadRot());
                head.crowdperf$setLerpHeadSteps(steps - 1);
            }
            living.yBodyRot = living.getYRot();
            living.calculateEntityAnimation(false);
        }
        ci.cancel();
    }
}
