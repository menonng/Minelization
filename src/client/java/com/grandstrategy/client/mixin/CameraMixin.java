package com.grandstrategy.client.mixin;

import com.grandstrategy.client.cutscene.CutscenePlayer;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(double x, double y, double z);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Inject(method = "alignWithEntity(F)V", at = @At("TAIL"))
    private void grandstrategy$cutsceneCamera(float partialTicks, CallbackInfo info) {
        double[] pose = CutscenePlayer.INSTANCE.cameraPose();
        if (pose == null) {
            return;
        }
        this.setRotation((float) pose[3], (float) pose[4]);
        this.setPosition(pose[0], pose[1], pose[2]);
        this.detached = true;
    }
}
