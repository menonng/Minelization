package com.grandstrategy.client.mixin;

import com.grandstrategy.client.cutscene.CutscenePlayer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {
    @Inject(method = "extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;)V", at = @At("HEAD"), cancellable = true)
    private void grandstrategy$hideToasts(GuiGraphicsExtractor graphics, CallbackInfo info) {
        if (CutscenePlayer.INSTANCE.getActive()) {
            info.cancel();
        }
    }
}
