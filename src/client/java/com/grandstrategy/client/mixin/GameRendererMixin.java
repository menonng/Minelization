package com.grandstrategy.client.mixin;

import com.grandstrategy.client.cutscene.CutscenePlayer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$hideHand(CallbackInfo info) {
        if (CutscenePlayer.INSTANCE.getActive()) {
            info.cancel();
        }
    }
}
