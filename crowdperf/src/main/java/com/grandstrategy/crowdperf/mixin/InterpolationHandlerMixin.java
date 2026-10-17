package com.grandstrategy.crowdperf.mixin;

import com.grandstrategy.crowdperf.CrowdEntity;
import com.grandstrategy.crowdperf.CrowdPerf;
import net.minecraft.world.entity.AbstractInterpolationHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Smooth walking for villagers whose positions arrive rarely. The client eases an entity to each new position over a
 * fixed number of ticks (3 for villagers); a server that updates a distant crowd only every 4 to 12 ticks then makes
 * it walk, stop, walk. Here each villager's easing lasts as long as the gap between its updates, measured as they
 * arrive, so it walks continuously at the cost of one update of delay.
 */
@Mixin(AbstractInterpolationHandler.class)
public abstract class InterpolationHandlerMixin {
    @Shadow
    @Final
    protected Entity entity;
    @Shadow
    protected int interpolationSteps;

    @Inject(method = "interpolateTo(Lnet/minecraft/world/entity/PositionPath;FF)V", at = @At("HEAD"))
    private void crowdperf$matchUpdateRate(PositionPath position, float yRot, float xRot, CallbackInfo ci) {
        if (!(this.entity instanceof AbstractVillager) || !CrowdPerf.config().smoothMotion) return;
        CrowdEntity crowd = (CrowdEntity) this.entity;
        int now = this.entity.tickCount;
        int gap = now - crowd.crowdperf$lastUpdate();
        crowd.crowdperf$setLastUpdate(now);
        float interval = crowd.crowdperf$updateInterval();
        // Long gaps are a villager that stood still (nothing is sent then), not the update rate.
        if (gap >= 1 && gap <= 40) {
            interval = interval <= 0 ? gap : interval * 0.6F + gap * 0.4F;
            crowd.crowdperf$setUpdateInterval(interval);
        }
        if (interval > 0) this.interpolationSteps = Math.clamp(Math.round(interval), 2, 20);
    }
}
