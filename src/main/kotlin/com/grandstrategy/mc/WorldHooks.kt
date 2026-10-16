package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Settlements
import net.minecraft.ChatFormatting
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.StructureTags
import net.minecraft.world.entity.animal.golem.IronGolem
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.AABB
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

class WorldHooks(private val runtime: Runtime) {
    private val level: ServerLevel get() = runtime.server.overworld()
    private val pendingCounts = HashMap<Int, Long>()
    private val lastTerritory = HashMap<UUID, Long>()
    private var probesLeft = -1
    private var ticks = 0L

    fun onChunkLoad(world: ServerLevel, chunk: LevelChunk) {
        if (world != level) return
        val key = chunk.pos.pack()
        val structures = world.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        for ((structure, start) in chunk.allStarts) {
            if (!start.isValid) continue
            if (!structures.wrapAsHolder(structure).`is`(StructureTags.VILLAGE)) continue
            val box = start.boundingBox
            val land = ArrayList<Long>()
            for (cx in (box.minX() shr 4)..(box.maxX() shr 4)) for (cz in (box.minZ() shr 4)..(box.maxZ() shr 4)) {
                val c = Chunks.pack(cx, cz)
                if (Chunks.distance(c, key) <= 3) land += c
            }
            if (key !in land) land += key
            Settlements.register(runtime.sim, key, land)
        }
        runtime.sim.settlementAt(key)?.let { if (it.center == key) pendingCounts[it.id] = ticks + 60 }
    }

    fun startScouting() {
        if (!runtime.state.seeded) probesLeft = runtime.settings.villageProbes
    }

    fun tick(now: Long) {
        ticks = now
        if (probesLeft > 0 && now % 20 == 0L) probe()
        if (probesLeft == 0) {
            probesLeft = -1
            runtime.seedWorld()
        }
        if (now % 10 == 0L) borders()
        if (now % 40 == 0L) {
            markers()
            counts(now)
        }
        if (now % 1200 == 0L) {
            for (s in runtime.state.settlements.values) {
                if (level.chunkSource.hasChunk(Chunks.x(s.center), Chunks.z(s.center))) pendingCounts.putIfAbsent(s.id, now)
            }
        }
    }

    private fun probe() {
        val index = runtime.settings.villageProbes - probesLeft
        probesLeft--
        val center = runtime.sim.spawnCenter
        val angle = index * (2 * Math.PI / runtime.settings.villageProbes.coerceAtLeast(1))
        val radius = runtime.settings.seedRadiusChunks * 16 * 0.6
        val x = Chunks.x(center) * 16 + (cos(angle) * radius).toInt()
        val z = Chunks.z(center) * 16 + (sin(angle) * radius).toInt()
        val found = try {
            level.findNearestMapStructure(StructureTags.VILLAGE, BlockPos(x, 64, z), 40, false)
        } catch (e: Exception) {
            GrandStrategy.LOGGER.warn("Village probe failed", e)
            null
        } ?: return
        val key = Chunks.pack(found.x shr 4, found.z shr 4)
        Settlements.register(runtime.sim, key, Chunks.around(key, 1))
    }

    private fun counts(now: Long) {
        val due = pendingCounts.filterValues { it <= now }.keys
        for (id in due) {
            pendingCounts.remove(id)
            val s = runtime.state.settlements[id] ?: continue
            if (!level.chunkSource.hasChunk(Chunks.x(s.center), Chunks.z(s.center))) continue
            val minX = s.land.minOf { Chunks.x(it) } * 16.0
            val minZ = s.land.minOf { Chunks.z(it) } * 16.0
            val maxX = (s.land.maxOf { Chunks.x(it) } + 1) * 16.0
            val maxZ = (s.land.maxOf { Chunks.z(it) } + 1) * 16.0
            val box = AABB(minX, level.minY.toDouble(), minZ, maxX, level.maxY.toDouble(), maxZ)
            val villagers = level.getEntitiesOfClass(Villager::class.java, box).size
            val golems = level.getEntitiesOfClass(IronGolem::class.java, box).size
            s.population = villagers * VILLAGER_WEIGHT
            s.golems = golems
            s.observed = true
        }
    }

    private fun markers() {
        if (!runtime.settings.capitalMarkers) return
        for (nation in runtime.state.alive()) {
            if (nation.markerPlaced) continue
            val cx = Chunks.x(nation.capital)
            val cz = Chunks.z(nation.capital)
            if (!level.chunkSource.hasChunk(cx, cz)) continue
            nation.markerPlaced = true
            val x = cx * 16 + 8
            val z = cz * 16 + 8
            val pos = BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z)
            val current = level.getBlockState(pos)
            if (!(current.isAir || current.`is`(BlockTags.REPLACEABLE)) || !level.getBlockState(pos.below()).isSolid) {
                GrandStrategy.LOGGER.info("Skipped capital banner of {}: no free ground at {}", nation.name, pos)
                continue
            }
            val banner = Blocks.BANNER.pick(Texts.dye(nation))
            level.setBlockAndUpdate(pos, banner.defaultBlockState())
            GrandStrategy.LOGGER.info("Placed capital banner of {} at {}", nation.name, pos)
        }
    }

    private fun borders() {
        for (player in runtime.server.playerList.players) {
            if (player.level() != level) continue
            val chunk = player.chunkPosition().pack()
            val owner = runtime.sim.ownerOf(chunk)
            val settlement = runtime.sim.settlementAt(chunk)
            val territory = when {
                owner != null -> owner.id.toLong()
                settlement != null -> -settlement.id.toLong()
                else -> 0L
            }
            val previous = lastTerritory.put(player.uuid, territory)
            if (previous == null || previous == territory) continue
            val text = when {
                owner != null -> {
                    val town = settlement?.let { " · ${it.name}" } ?: ""
                    val capital = if (owner.capital == chunk) " · 수도" else ""
                    Component.empty().append(Texts.nation(owner)).append(Texts.plain(" 영토$town$capital", ChatFormatting.WHITE))
                }
                settlement != null -> Texts.plain("자유 마을 ${settlement.name}", ChatFormatting.GREEN)
                else -> Texts.plain("무주지 · ${runtime.sim.terrain(chunk).label}", ChatFormatting.GRAY)
            }
            player.sendOverlayMessage(text)
        }
    }

    fun forget(player: UUID) {
        lastTerritory.remove(player)
    }

    private companion object {
        const val VILLAGER_WEIGHT = 5.0
    }
}
