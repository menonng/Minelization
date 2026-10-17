package com.grandstrategy.crowdperf.mixin;

import com.grandstrategy.crowdperf.CrowdPerf;
import com.grandstrategy.crowdperf.PrecipitationCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Rain and snow are laid out every frame column by column around the camera, and each column asks the level which
 * precipitation falls there: a biome lookup that blends neighbouring biome cells. The answer for a column at a given
 * height does not change from frame to frame, so it is cached (see {@link PrecipitationCache}).
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
    @Redirect(
        method = "extractRenderState",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;")
    )
    private Biome.Precipitation crowdperf$cachedPrecipitation(ClientLevel level, BlockPos pos) {
        if (!CrowdPerf.config().weatherCache) return level.getPrecipitationAt(pos);
        return PrecipitationCache.get(level, pos);
    }
}
