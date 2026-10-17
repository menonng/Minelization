package com.grandstrategy.crowdperf.mixin;

import com.grandstrategy.crowdperf.CrowdPerf;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villagers are drawn as far as terrain is: anywhere in the chunks the client renders, instead of vanilla's ~64 block
 * cutoff for small mobs. Far ones are cheap (see {@link com.grandstrategy.crowdperf.Impostors}).
 */
@Mixin(Entity.class)
public abstract class CrowdRenderDistanceMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void crowdperf$toViewDistance(double distance, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof AbstractVillager) || !CrowdPerf.config().renderToViewDistance) return;
        double blocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        cir.setReturnValue(distance < blocks * blocks);
    }
}
