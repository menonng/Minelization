package com.grandstrategy.client.mixin;

import com.grandstrategy.client.cutscene.CutscenePlayer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$hideHud(GuiGraphicsExtractor graphics, DeltaTracker delta, CallbackInfo info) {
        if (CutscenePlayer.INSTANCE.getActive()) {
            info.cancel();
        }
    }
}
