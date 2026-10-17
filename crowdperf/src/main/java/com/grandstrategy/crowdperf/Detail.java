package com.grandstrategy.crowdperf;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;

/**
 * Level of detail for villagers that are still drawn as full models. A villager is a body plus up to three clothing
 * overlays (biome, profession, level), a held item and a shadow, each a separate draw. Past
 * {@link CrowdConfig#detailDistance} only the body is drawn: the extras are a few pixels there.
 */
public final class Detail {
    private Detail() {}

    public static boolean reduced(EntityRenderState state) {
        double d = CrowdPerf.config().detailDistance;
        return state instanceof VillagerRenderState && state.distanceToCameraSq > d * d;
    }
}
