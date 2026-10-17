package com.grandstrategy.crowdperf;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;

/**
 * Which precipitation falls on a column at a height, remembered for a couple of seconds. The weather renderer asks
 * this for a few hundred columns every frame; the real answer needs a blended biome lookup each time.
 */
public final class PrecipitationCache {
    private static final Biome.Precipitation[] VALUES = Biome.Precipitation.values();
    /** Answers are kept this many game ticks (a chunk that loads in is noticed within that time). */
    private static final long LIFETIME = 40;
    private static final Long2LongOpenHashMap CACHE = new Long2LongOpenHashMap();
    private static ClientLevel cachedLevel;

    static {
        CACHE.defaultReturnValue(Long.MIN_VALUE);
    }

    private PrecipitationCache() {}

    /** The precipitation at {@code pos}: cached by column and height, refreshed every {@link #LIFETIME} ticks. */
    public static Biome.Precipitation get(ClientLevel level, BlockPos pos) {
        if (level != cachedLevel || CACHE.size() > 8192) {
            CACHE.clear();
            cachedLevel = level;
        }
        long column = (long) pos.getX() << 32 | (pos.getZ() & 0xFFFFFFFFL);
        long epoch = level.getGameTime() / LIFETIME;
        // value: epoch (high bits) | height (20 bits) | precipitation (4 bits)
        long cached = CACHE.get(column);
        int y = pos.getY() & 0xFFFFF;
        if (cached != Long.MIN_VALUE && cached >>> 24 == epoch && ((cached >>> 4) & 0xFFFFF) == y) return VALUES[(int) (cached & 0xF)];
        Biome.Precipitation value = level.getPrecipitationAt(pos);
        CACHE.put(column, epoch << 24 | (long) y << 4 | value.ordinal());
        return value;
    }
}
