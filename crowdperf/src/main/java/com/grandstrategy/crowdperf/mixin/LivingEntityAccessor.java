package com.grandstrategy.crowdperf.mixin;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Head turning state, which the light tick advances the way LivingEntity.aiStep does. */
@Mixin(LivingEntity.class)
public interface LivingEntityAccessor {
    @Accessor("lerpHeadSteps")
    int crowdperf$lerpHeadSteps();

    @Accessor("lerpHeadSteps")
    void crowdperf$setLerpHeadSteps(int steps);

    @Accessor("lerpYHeadRot")
    double crowdperf$lerpYHeadRot();

    @Invoker("lerpHeadRotationStep")
    void crowdperf$lerpHeadRotationStep(int steps, double target);
}
