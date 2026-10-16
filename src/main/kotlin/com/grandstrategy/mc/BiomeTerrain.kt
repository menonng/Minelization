package com.grandstrategy.mc

import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Terrain
import com.grandstrategy.sim.TerrainProvider
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BiomeTags

class BiomeTerrain(private val level: ServerLevel) : TerrainProvider {
    override fun terrain(chunk: Long): Terrain {
        val x = Chunks.x(chunk) * 16 + 8
        val z = Chunks.z(chunk) * 16 + 8
        val biome = level.getUncachedNoiseBiome(x shr 2, SAMPLE_Y shr 2, z shr 2)
        val path = biome.unwrapKey().map { it.identifier().path }.orElse("")
        return when {
            biome.`is`(BiomeTags.IS_OCEAN) || biome.`is`(BiomeTags.IS_DEEP_OCEAN) -> Terrain.WATER
            biome.`is`(BiomeTags.IS_RIVER) -> Terrain.RIVER
            biome.`is`(BiomeTags.IS_BEACH) -> Terrain.COAST
            biome.`is`(BiomeTags.IS_MOUNTAIN) -> Terrain.MOUNTAIN
            biome.`is`(BiomeTags.IS_BADLANDS) -> Terrain.BADLANDS
            biome.`is`(BiomeTags.IS_HILL) -> Terrain.HILLS
            biome.`is`(BiomeTags.IS_JUNGLE) -> Terrain.JUNGLE
            path == "desert" -> Terrain.DESERT
            "snowy" in path || "frozen" in path || "ice" in path -> Terrain.SNOWY
            biome.`is`(BiomeTags.IS_TAIGA) -> Terrain.TAIGA
            biome.`is`(BiomeTags.IS_FOREST) -> Terrain.FOREST
            biome.`is`(BiomeTags.IS_SAVANNA) -> Terrain.SAVANNA
            else -> Terrain.PLAINS
        }
    }

    private companion object {
        const val SAMPLE_Y = 160
    }
}
