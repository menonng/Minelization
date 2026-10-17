package com.grandstrategy.client.render;

import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.VillagerRenderState;

/**
 * Level of detail for villagers. A villager is a body plus up to three clothing overlays (biome, profession, level),
 * a held item and a shadow, each a separate draw. Past {@link #FULL_DETAIL} blocks only the body is drawn: at that
 * distance the extras are a few pixels, and dropping them cuts the cost of a crowd to about a fifth.
 */
public final class VillagerLod {
    public static final double FULL_DETAIL = 40.0;

    private VillagerLod() {}

    public static boolean far(EntityRenderState state) {
        return state instanceof VillagerRenderState && state.distanceToCameraSq > FULL_DETAIL * FULL_DETAIL;
    }
}
