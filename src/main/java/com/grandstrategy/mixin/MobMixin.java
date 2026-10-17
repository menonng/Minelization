package com.grandstrategy.mixin;

import com.grandstrategy.mc.CutsceneGuard;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Mob.class)
public abstract class MobMixin {
    @Inject(method = "canAttack(Lnet/minecraft/world/entity/LivingEntity;)Z", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$ignoreCutscene(LivingEntity target, CallbackInfoReturnable<Boolean> info) {
        if (target != null && (CutsceneGuard.INSTANCE.isGuarded(target.getUUID()) || com.grandstrategy.mc.GodMode.protects(target))) {
            info.setReturnValue(false);
        }
    }

    @Inject(method = "setTarget(Lnet/minecraft/world/entity/LivingEntity;)V", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$dropCutsceneTarget(LivingEntity target, CallbackInfo info) {
        if (target != null && (CutsceneGuard.INSTANCE.isGuarded(target.getUUID()) || com.grandstrategy.mc.GodMode.protects(target))) {
            info.cancel();
        }
    }
}
