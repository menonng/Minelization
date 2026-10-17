package com.grandstrategy.client.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Villagers are the population, so they are drawn as far as terrain is: anywhere in the chunks the client renders,
 * instead of vanilla's ~64 block cutoff for small mobs. Far ones are drawn cheaply (see {@link VillagerLodMixin}).
 */
@Mixin(Entity.class)
public abstract class VillagerRenderDistanceMixin {
    @Inject(method = "shouldRenderAtSqrDistance", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$villagersToRenderDistance(double distance, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof Villager)) return;
        double blocks = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0;
        cir.setReturnValue(distance < blocks * blocks);
    }
}
