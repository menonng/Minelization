package com.grandstrategy.mc

import com.grandstrategy.sim.AgentPopulation
import com.grandstrategy.sim.Agents
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Job
import io.netty.buffer.Unpooled
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundRotateHeadPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.BiomeTags
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.entity.PositionPath
import net.minecraft.world.entity.npc.villager.Villager
import net.minecraft.world.entity.npc.villager.VillagerData
import net.minecraft.world.entity.npc.villager.VillagerProfession
import net.minecraft.world.entity.npc.villager.VillagerType
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.floor

class AgentDisplay(private val rt: Runtime, private val population: AgentPopulation) {
    private val agents: Agents get() = population.agents
    private val level: ServerLevel get() = rt.server.overworld()

    private var netId = IntArray(0)
    private var shownX = FloatArray(0)
    private var shownZ = FloatArray(0)
    private var shownY = FloatArray(0)
    private var skin = ByteArray(0)
    private val viewers = HashMap<UUID, IntOpenHashSet>()
    private val grid = Long2ObjectOpenHashMap<IntArrayList>()
    private val hot = IntOpenHashSet()
    private val templates = HashMap<Int, List<SynchedEntityData.DataValue<*>>>()
    private var ticks = 0L

    var lastNanos = 0L
        private set
    var shownTotal = 0
        private set
    var packetsLastTick = 0
        private set
    var packetsTotal = 0L
        private set
    var bytesEstimate = 0L
        private set

    init {
        agents.onRemoved { i -> forget(i) }
    }

    fun hotAgents(): Iterable<Int> = hot

    private fun ensure() {
        val n = agents.capacity
        if (netId.size >= n) return
        netId = netId.copyOf(n)
        shownX = shownX.copyOf(n)
        shownZ = shownZ.copyOf(n)
        shownY = shownY.copyOf(n)
        skin = skin.copyOf(n).also { for (k in skin.size until n) it[k] = -1 }
    }

    fun tick() {
        val start = System.nanoTime()
        ticks++
        ensure()
        packetsLastTick = 0
        if (ticks % GRID_INTERVAL == 0L || grid.isEmpty()) rebuildGrid()
        if (ticks % UPDATE_INTERVAL == 0L) update()
        packetsTotal += packetsLastTick
        lastNanos = System.nanoTime() - start
    }

    private fun rebuildGrid() {
        for (list in grid.values) list.clear()
        agents.forEachAlive { i ->
            grid.computeIfAbsent(agents.chunkOf(i)) { IntArrayList() }.add(i)
        }
        grid.values.removeIf { it.isEmpty }
    }

    private fun update() {
        val tuning = population.tuning
        val radius = tuning.visibleRadius.toDouble()
        val r2 = radius * radius
        val chunkRadius = (tuning.visibleRadius shr 4) + 1
        val players = rt.server.playerList.players
        val online = HashSet<UUID>()
        hot.clear()
        for (player in players) {
            online += player.uuid
            val seen = viewers.getOrPut(player.uuid) { IntOpenHashSet() }
            if (player.level() != level) {
                hide(player, seen, IntOpenHashSet(seen))
                continue
            }
            val px = player.x
            val pz = player.z
            val center = player.chunkPosition()
            val candidates = IntArrayList()
            val dists = ArrayList<Pair<Int, Double>>()
            for (dx in -chunkRadius..chunkRadius) for (dz in -chunkRadius..chunkRadius) {
                val list = grid.get(Chunks.pack(center.x + dx, center.z + dz)) ?: continue
                for (k in 0 until list.size) {
                    val i = list.getInt(k)
                    if (!agents.alive[i]) continue
                    val ddx = agents.x[i] - px
                    val ddz = agents.z[i] - pz
                    val d = ddx * ddx + ddz * ddz
                    if (d <= r2) dists += i to d
                }
            }
            if (dists.size > tuning.maxVisiblePerPlayer) dists.sortBy { it.second }
            for (k in 0 until minOf(dists.size, tuning.maxVisiblePerPlayer)) candidates.add(dists[k].first)
            val want = IntOpenHashSet(candidates)
            val gone = IntOpenHashSet()
            val iter = seen.iterator()
            while (iter.hasNext()) {
                val i = iter.nextInt()
                if (!want.contains(i)) gone.add(i)
            }
            hide(player, seen, gone)
            val iterWant = want.iterator()
            while (iterWant.hasNext()) {
                val i = iterWant.nextInt()
                hot.add(i)
                if (seen.add(i)) show(player, i)
            }
        }
        viewers.keys.retainAll(online)
        broadcastMoves()
        shownTotal = viewers.values.sumOf { it.size }
    }

    private fun broadcastMoves() {
        val iter = hot.iterator()
        while (iter.hasNext()) {
            val i = iter.nextInt()
            if (netId[i] == 0) continue
            val x = agents.x[i]
            val z = agents.z[i]
            val dx = x - shownX[i]
            val dz = z - shownZ[i]
            if (dx * dx + dz * dz < 0.0004f) continue
            val y = ground(x, z) ?: shownY[i]
            val yaw = (Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())) - 90.0).toFloat()
            shownX[i] = x
            shownZ[i] = z
            shownY[i] = y
            val sync = ClientboundEntityPositionSyncPacket(netId[i], PositionPath.of(Vec3(x.toDouble(), y.toDouble(), z.toDouble())), yaw, 0f, true)
            val head = headPacket(netId[i], yaw)
            for ((uuid, seen) in viewers) {
                if (!seen.contains(i)) continue
                val player = rt.server.playerList.getPlayer(uuid) ?: continue
                player.connection.send(sync)
                player.connection.send(head)
                packetsLastTick += 2
                bytesEstimate += 44
            }
        }
    }

    private fun show(player: ServerPlayer, i: Int) {
        if (netId[i] == 0) netId[i] = level.nextEntityId
        val x = agents.x[i]
        val z = agents.z[i]
        val y = ground(x, z) ?: return
        shownX[i] = x
        shownZ[i] = z
        shownY[i] = y
        if (skin[i] < 0) skin[i] = skinFor(i, x, z).toByte()
        val uuid = UUID(0x6D696E656CL, agents.uid[i].toLong())
        player.connection.send(
            ClientboundAddEntityPacket(netId[i], uuid, x.toDouble(), y.toDouble(), z.toDouble(), 0f, 0f, EntityTypes.VILLAGER, 0, Vec3.ZERO, 0.0)
        )
        player.connection.send(ClientboundSetEntityDataPacket(netId[i], template(skin[i].toInt())))
        packetsLastTick += 2
        bytesEstimate += 80
    }

    private fun hide(player: ServerPlayer, seen: IntOpenHashSet, gone: IntOpenHashSet) {
        if (gone.isEmpty()) return
        val ids = IntArrayList()
        val iter = gone.iterator()
        while (iter.hasNext()) {
            val i = iter.nextInt()
            seen.remove(i)
            if (netId[i] != 0) ids.add(netId[i])
        }
        if (ids.isNotEmpty()) {
            player.connection.send(ClientboundRemoveEntitiesPacket(ids))
            packetsLastTick++
        }
    }

    private fun forget(i: Int) {
        if (i >= netId.size) return
        val id = netId[i]
        for ((uuid, seen) in viewers) {
            if (!seen.remove(i) || id == 0) continue
            rt.server.playerList.getPlayer(uuid)?.connection?.send(ClientboundRemoveEntitiesPacket(id))
        }
        netId[i] = 0
        skin[i] = -1
        hot.remove(i)
    }

    fun removePlayer(uuid: UUID) {
        viewers.remove(uuid)
    }

    fun hideAll() {
        for ((uuid, seen) in viewers) {
            val player = rt.server.playerList.getPlayer(uuid) ?: continue
            hide(player, seen, IntOpenHashSet(seen))
        }
        viewers.clear()
    }

    private fun ground(x: Float, z: Float): Float? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        if (!level.chunkSource.hasChunk(bx shr 4, bz shr 4)) return null
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz).toFloat()
    }

    private fun skinFor(i: Int, x: Float, z: Float): Int {
        val biome = level.getUncachedNoiseBiome(floor(x).toInt() shr 2, 64 shr 2, floor(z).toInt() shr 2)
        val type = when {
            biome.`is`(BiomeTags.IS_JUNGLE) -> 1
            biome.`is`(BiomeTags.IS_SAVANNA) -> 3
            biome.`is`(BiomeTags.IS_TAIGA) -> 6
            biome.unwrapKey().map { "desert" in it.identifier().path || "badlands" in it.identifier().path }.orElse(false) -> 0
            biome.unwrapKey().map { "snow" in it.identifier().path || "frozen" in it.identifier().path || "ice" in it.identifier().path }.orElse(false) -> 4
            biome.unwrapKey().map { "swamp" in it.identifier().path }.orElse(false) -> 5
            else -> 2
        }
        return type * 16 + agents.job[i]
    }

    private fun template(key: Int): List<SynchedEntityData.DataValue<*>> = templates.getOrPut(key) {
        val types = listOf(VillagerType.DESERT, VillagerType.JUNGLE, VillagerType.PLAINS, VillagerType.SAVANNA, VillagerType.SNOW, VillagerType.SWAMP, VillagerType.TAIGA)
        val job = Job.entries[key % 16]
        val profession: ResourceKey<VillagerProfession> = when (job) {
            Job.FARMER -> VillagerProfession.FARMER
            Job.LUMBERJACK -> VillagerProfession.FLETCHER
            Job.MINER -> VillagerProfession.TOOLSMITH
            Job.BUILDER -> VillagerProfession.MASON
            Job.MERCHANT -> VillagerProfession.CARTOGRAPHER
            Job.SCHOLAR -> VillagerProfession.LIBRARIAN
            Job.SOLDIER -> VillagerProfession.WEAPONSMITH
            Job.CHILD -> VillagerProfession.NONE
        }
        val villager = Villager(EntityTypes.VILLAGER, level)
        villager.setVillagerData(
            VillagerData(
                BuiltInRegistries.VILLAGER_TYPE.getOrThrow(types[(key / 16).coerceIn(0, types.size - 1)]),
                BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(profession),
                2
            )
        )
        if (job == Job.CHILD) villager.age = -24000
        villager.entityData.nonDefaultValues ?: emptyList()
    }

    private fun headPacket(id: Int, yaw: Float): Packet<*> {
        val buf = FriendlyByteBuf(Unpooled.buffer(8))
        buf.writeVarInt(id)
        buf.writeByte(floor(yaw * 256f / 360f).toInt())
        return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf)
    }

    private companion object {
        const val GRID_INTERVAL = 10L
        const val UPDATE_INTERVAL = 2L
    }
}
