package com.grandstrategy.mc.build

import com.grandstrategy.sim.Box
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap

object BlockClass {
    // 26.3 split #dirt: grass, podzol and mycelium moved to #grass_blocks; #substrate_overworld covers dirt, grass, moss and mud.
    fun terrain(state: BlockState): Boolean =
        state.`is`(BlockTags.BASE_STONE_OVERWORLD) || state.`is`(BlockTags.SUBSTRATE_OVERWORLD) || state.`is`(BlockTags.SAND) ||
            state.`is`(BlockTags.ORES) || state.`is`(BlockTags.ICE) || state.`is`(BlockTags.SNOW) ||
            state.`is`(Blocks.GRAVEL) || state.`is`(Blocks.CLAY) || state.`is`(Blocks.BEDROCK) || state.`is`(Blocks.SANDSTONE) ||
            state.`is`(Blocks.RED_SANDSTONE) || state.`is`(Blocks.SNOW_BLOCK) || state.`is`(Blocks.POWDER_SNOW) ||
            state.`is`(Blocks.OBSIDIAN) || !state.fluidState.isEmpty

    fun vegetation(state: BlockState): Boolean =
        state.`is`(BlockTags.LEAVES) || state.`is`(BlockTags.FLOWERS) || state.`is`(BlockTags.SAPLINGS) ||
            state.`is`(BlockTags.REPLACEABLE) || state.`is`(BlockTags.CROPS) || state.`is`(Blocks.BAMBOO) || state.`is`(Blocks.CACTUS) ||
            state.`is`(Blocks.SUGAR_CANE) || state.`is`(Blocks.VINE) || state.`is`(Blocks.COCOA) || state.`is`(Blocks.MOSS_BLOCK) ||
            state.`is`(Blocks.MOSS_CARPET) || state.`is`(Blocks.BROWN_MUSHROOM_BLOCK) || state.`is`(Blocks.RED_MUSHROOM_BLOCK) ||
            state.`is`(Blocks.MUSHROOM_STEM) || state.`is`(Blocks.BEE_NEST) || state.`is`(Blocks.MANGROVE_ROOTS) || state.`is`(Blocks.MUD) ||
            state.`is`(Blocks.PUMPKIN) || state.`is`(Blocks.MELON) || state.`is`(Blocks.SWEET_BERRY_BUSH) || state.`is`(Blocks.BIG_DRIPLEAF) ||
            state.`is`(Blocks.BIG_DRIPLEAF_STEM) || state.`is`(Blocks.GLOW_LICHEN) || state.`is`(Blocks.SNOW)

    fun constructed(state: BlockState): Boolean = !state.isAir && !terrain(state) && !vegetation(state)

    fun constructedAt(level: ServerLevel, pos: BlockPos, state: BlockState = level.getBlockState(pos)): Boolean {
        if (!constructed(state)) return false
        if (!state.`is`(BlockTags.LOGS)) return true
        for (dx in -2..2) for (dy in -2..2) for (dz in -2..2) {
            if (level.getBlockState(pos.offset(dx, dy, dz)).`is`(BlockTags.LEAVES)) return false
        }
        return true
    }

    fun ground(level: ServerLevel, x: Int, z: Int): Int = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z)

    fun loaded(level: ServerLevel, box: Box): Boolean = box.chunks().all { level.chunkSource.hasChunk(com.grandstrategy.sim.Chunks.x(it), com.grandstrategy.sim.Chunks.z(it)) }

    fun medianGround(level: ServerLevel, minX: Int, minZ: Int, maxX: Int, maxZ: Int): Int {
        val heights = ArrayList<Int>()
        var x = minX
        while (x <= maxX) {
            var z = minZ
            while (z <= maxZ) {
                heights += ground(level, x, z)
                z += 2
            }
            x += 2
        }
        heights.sort()
        return heights[heights.size / 2]
    }

    fun waterRatio(level: ServerLevel, minX: Int, minZ: Int, maxX: Int, maxZ: Int): Double {
        var water = 0
        var total = 0
        var x = minX
        while (x <= maxX) {
            var z = minZ
            while (z <= maxZ) {
                val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1
                if (!level.getBlockState(BlockPos(x, top, z)).fluidState.isEmpty) water++
                total++
                z += 2
            }
            x += 2
        }
        return if (total == 0) 0.0 else water.toDouble() / total
    }
}

object Detector {
    private fun nearestConstructed(level: ServerLevel, pos: BlockPos): BlockPos? {
        if (BlockClass.constructedAt(level, pos)) return pos
        var best: BlockPos? = null
        var bestD = Int.MAX_VALUE
        for (dx in -3..3) for (dy in -3..3) for (dz in -3..3) {
            val d = dx * dx + dy * dy + dz * dz
            if (d >= bestD) continue
            val p = pos.offset(dx, dy, dz)
            if (BlockClass.constructedAt(level, p)) {
                best = p
                bestD = d
            }
        }
        return best
    }

    const val MAX_BLOCKS = 40_000
    const val MAX_SPAN = 40

    fun detect(level: ServerLevel, clicked: BlockPos): Box? {
        val start = nearestConstructed(level, clicked) ?: return null
        val seen = HashSet<Long>()
        val queue = ArrayDeque<BlockPos>()
        queue += start
        seen += start.asLong()
        var minX = start.x; var minY = start.y; var minZ = start.z
        var maxX = start.x; var maxY = start.y; var maxZ = start.z
        while (queue.isNotEmpty()) {
            val p = queue.removeFirst()
            minX = minOf(minX, p.x); minY = minOf(minY, p.y); minZ = minOf(minZ, p.z)
            maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y); maxZ = maxOf(maxZ, p.z)
            if (seen.size > MAX_BLOCKS) break
            for (dir in net.minecraft.core.Direction.entries) {
                val n = p.relative(dir)
                if (n.x - start.x !in -MAX_SPAN..MAX_SPAN || n.z - start.z !in -MAX_SPAN..MAX_SPAN || n.y - start.y !in -MAX_SPAN..MAX_SPAN) continue
                if (!level.chunkSource.hasChunk(n.x shr 4, n.z shr 4)) continue
                if (!seen.add(n.asLong())) continue
                if (BlockClass.constructedAt(level, n)) queue += n
            }
        }
        return Box(minX, minY, minZ, maxX, maxY, maxZ)
    }
}
