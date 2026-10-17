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
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket
import net.minecraft.network.protocol.game.ClientGamePacketListener
import net.minecraft.network.protocol.game.VecDelta
import net.minecraft.network.protocol.game.VecDeltaCodec
import net.minecraft.core.BlockPos
import net.minecraft.util.Mth
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

/**
 * Shows the population as villagers: every agent in the chunks a player can see is sent to that player as a
 * packet-only villager (no server entity). Two tiers keep thousands cheap:
 *  - near (within [AgentTuning.visibleRadius]): A*-walked by [Walker], moved every 2 ticks with head turns;
 *  - far (the rest of the view distance): follow their simulated position on the ground, moved every
 *    [AgentTuning.farUpdateTicks] ticks (or [AgentTuning.distantUpdateTicks] past 96 blocks) only when they moved,
 *    as small relative-move packets, all of a player's packets for a tick sent as one bundle.
 * Who sees whom is recomputed every [VIEW_INTERVAL] ticks, and new villagers are spawned nearest-first under a budget.
 */
class AgentDisplay(private val rt: Runtime, private val population: AgentPopulation) {
    private val agents: Agents get() = population.agents
    private val level: ServerLevel get() = rt.server.overworld()

    private var netId = IntArray(0)
    private var shownX = FloatArray(0)
    private var shownZ = FloatArray(0)
    private var shownY = FloatArray(0)
    private var shownYaw = FloatArray(0)
    private var shownPitch = FloatArray(0)
    private var lastSync = LongArray(0)
    /** Squared distance to the nearest viewer at the last view update; picks the far update rate. */
    private var nearest = FloatArray(0)
    val walker = com.grandstrategy.mc.agents.Walker(population) { rt.server.overworld() }
    var teleports = 0L
        private set
    private var skin = ByteArray(0)
    private val viewers = HashMap<UUID, IntOpenHashSet>()
    private val grid = Long2ObjectOpenHashMap<IntArrayList>()
    /** Near agents: walked by A*. */
    private val hot = IntOpenHashSet()
    /** Every agent shown to anyone. */
    private val shown = IntOpenHashSet()
    /** Shown but not near: stepped by the simulation a little more often so their motion reads smoothly. */
    private val warm = IntOpenHashSet()
    private val codecs = HashMap<Int, VecDeltaCodec>()
    private val outbox = HashMap<UUID, ArrayList<Packet<in ClientGamePacketListener>>>()
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

    fun warmAgents(): Iterable<Int> = warm

    fun isShown(i: Int): Boolean = shown.contains(i)

    val nearCount get() = hot.size
    val shownCount get() = shown.size

    private fun ensure() {
        val n = agents.capacity
        if (netId.size >= n) return
        netId = netId.copyOf(n)
        shownX = shownX.copyOf(n)
        shownZ = shownZ.copyOf(n)
        shownY = shownY.copyOf(n)
        shownYaw = shownYaw.copyOf(n)
        shownPitch = shownPitch.copyOf(n)
        lastSync = lastSync.copyOf(n)
        nearest = nearest.copyOf(n)
        skin = skin.copyOf(n).also { for (k in skin.size until n) it[k] = -1 }
    }

    fun tick() {
        val start = System.nanoTime()
        ticks++
        ensure()
        packetsLastTick = 0
        if (ticks % GRID_INTERVAL == 0L || grid.isEmpty()) rebuildGrid()
        if (ticks % VIEW_INTERVAL == 0L) updateViews()
        if (ticks % UPDATE_INTERVAL == 0L) moveNear()
        moveFar()
        walker.tick(hot, 0.05f)
        flush()
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

    private fun viewChunks(player: ServerPlayer): Int =
        minOf(population.tuning.displayChunks, rt.server.playerList.viewDistance, maxOf(2, player.requestedViewDistance()))

    private fun updateViews() {
        val tuning = population.tuning
        val near2 = tuning.visibleRadius.toDouble().let { it * it }
        val players = rt.server.playerList.players
        val online = HashSet<UUID>()
        val wants = HashMap<UUID, Pair<IntOpenHashSet, List<Pair<Int, Double>>>>()
        val nearUnion = IntOpenHashSet()
        java.util.Arrays.fill(nearest, Float.MAX_VALUE)
        for (player in players) {
            online += player.uuid
            if (player.level() != level) continue
            val px = player.x
            val pz = player.z
            val center = player.chunkPosition()
            val r = viewChunks(player)
            val all = ArrayList<Pair<Int, Double>>()
            for (dx in -r..r) for (dz in -r..r) {
                if (dx * dx + dz * dz > (r + 1) * (r + 1)) continue
                val list = grid.get(Chunks.pack(center.x + dx, center.z + dz)) ?: continue
                for (k in 0 until list.size) {
                    val i = list.getInt(k)
                    if (!agents.alive[i] || agents.sleeping(i)) continue
                    val ddx = agents.x[i] - px
                    val ddz = agents.z[i] - pz
                    val d = ddx * ddx + ddz * ddz
                    all += i to d
                    if (d < nearest[i]) nearest[i] = d.toFloat()
                }
            }
            all.sortBy { it.second }
            val want = IntOpenHashSet()
            for ((i, _) in all) {
                if (want.size >= tuning.maxShownPerPlayer) break
                want.add(i)
            }
            // Near tier with hysteresis: agents already walked by A* keep their place until well past the radius, so a
            // crowd at the edge does not flip between tiers (each flip could show as a small hop).
            val keep2 = (tuning.visibleRadius + 8.0).let { it * it }
            var guided = 0
            for ((i, d) in all) {
                if (guided >= tuning.maxVisiblePerPlayer) break
                if (hot.contains(i) && d <= keep2 && want.contains(i)) {
                    nearUnion.add(i)
                    guided++
                }
            }
            for ((i, d) in all) {
                if (guided >= tuning.maxVisiblePerPlayer || d > near2) break
                if (want.contains(i) && nearUnion.add(i)) guided++
            }
            wants[player.uuid] = want to all
        }
        // Near agents get the walker; ones that cannot be placed on the ground stay far.
        val failed = IntOpenHashSet()
        var snaps = SNAPS_PER_UPDATE
        val iterNear = nearUnion.iterator()
        while (iterNear.hasNext()) {
            val i = iterNear.nextInt()
            if (hot.contains(i) || agents.guided[i]) continue
            // Snap to ground from the height the player has been seeing, so turning near is not a visible hop.
            if (codecs.containsKey(i)) agents.y[i] = shownY[i]
            if (snaps <= 0 || !walker.take(i, strict = codecs.containsKey(i))) failed.add(i)
            snaps--
        }
        nearUnion.removeAll(failed)
        val iterOld = hot.iterator()
        while (iterOld.hasNext()) {
            val i = iterOld.nextInt()
            if (!nearUnion.contains(i)) walker.release(i)
        }
        hot.clear()
        hot.addAll(nearUnion)
        // Per player: hide what left the view, spawn what entered (nearest first, within the spawn budget).
        shown.clear()
        for (player in players) {
            val seen = viewers.getOrPut(player.uuid) { IntOpenHashSet() }
            val (want, ordered) = wants[player.uuid] ?: (IntOpenHashSet() to emptyList())
            val gone = IntOpenHashSet()
            val iter = seen.iterator()
            while (iter.hasNext()) {
                val i = iter.nextInt()
                if (!want.contains(i)) gone.add(i)
            }
            hide(player, seen, gone)
            var budget = tuning.spawnsPerUpdate
            for ((i, _) in ordered) {
                if (budget <= 0) break
                if (!want.contains(i) || seen.contains(i)) continue
                if (show(player, i)) {
                    seen.add(i)
                    budget--
                }
            }
            shown.addAll(seen)
        }
        warm.clear()
        val iterShown = shown.iterator()
        while (iterShown.hasNext()) {
            val i = iterShown.nextInt()
            if (!hot.contains(i)) warm.add(i)
        }
        for (uuid in viewers.keys.filter { it !in online }) removePlayer(uuid)
        shownTotal = viewers.values.sumOf { it.size }
    }

    private fun pose(i: Int): Pair<Float, Float> {
        val working = agents.state[i] == com.grandstrategy.sim.AgentState.WORK
        val yaw = walker.yaw[i] ?: shownYaw[i]
        return yaw to (if (working) 28f else 0f)
    }

    /** Heading from the agent's last shown position to where it is now, for agents not driven by the walker. */
    private fun heading(i: Int, x: Float, z: Float): Float {
        val dx = x - shownX[i]
        val dz = z - shownZ[i]
        if (dx * dx + dz * dz < 0.0004f) return shownYaw[i]
        return (Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())) - 90.0).toFloat()
    }

    private fun moveNear() {
        val iter = hot.iterator()
        while (iter.hasNext()) {
            val i = iter.nextInt()
            if (netId[i] == 0) continue
            val x = agents.x[i]
            val z = agents.z[i]
            val y = if (agents.y[i].isNaN()) shownY[i] else agents.y[i]
            val (yaw, pitch) = pose(i)
            send(i, x, y, z, yaw, pitch, near = true)
        }
    }

    private fun moveFar() {
        val far = population.tuning.farUpdateTicks.coerceAtLeast(1)
        val distant = population.tuning.distantUpdateTicks.coerceAtLeast(far)
        val iter = warm.iterator()
        while (iter.hasNext()) {
            val i = iter.nextInt()
            if (netId[i] == 0 || !agents.alive[i]) continue
            val every = if (nearest[i] > DISTANT * DISTANT) distant else far
            if ((i + ticks) % every != 0L) continue
            val x = agents.x[i]
            val z = agents.z[i]
            val y = farY(i, x, z) ?: continue
            val pitch = if (agents.state[i] == com.grandstrategy.sim.AgentState.WORK) 28f else 0f
            send(i, x, y, z, heading(i, x, z), pitch, near = false)
        }
    }

    /**
     * Ground height for an agent the walker isn't guiding. If the surface under it is something built (a roof, a wall
     * top, a tree trunk) it keeps its last height, i.e. it walks through the building at street level instead of
     * popping onto the roof.
     */
    private fun farY(i: Int, x: Float, z: Float): Float? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        if (!level.chunkSource.hasChunk(bx shr 4, bz shr 4)) return null
        val h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz)
        val below = level.getBlockState(BlockPos(bx, h - 1, bz))
        val built = com.grandstrategy.mc.build.BlockClass.constructed(below) && !com.grandstrategy.mc.build.RoadBlocks.road(below) &&
            !below.`is`(net.minecraft.world.level.block.Blocks.FARMLAND)
        if (built && shownY[i] != 0f && kotlin.math.abs(shownY[i] - h) < 16) return shownY[i]
        return h.toFloat()
    }

    /** Sends a move for agent [i] to everyone who sees it, if it moved or turned enough to matter. */
    private fun send(i: Int, x: Float, y: Float, z: Float, yaw: Float, pitch: Float, near: Boolean) {
        val dx = x - shownX[i]
        val dz = z - shownZ[i]
        val dy = y - shownY[i]
        val moved = dx * dx + dz * dz + dy * dy >= (if (near) 0.0004f else 0.01f)
        val turned = kotlin.math.abs(yaw - shownYaw[i]) > (if (near) 2f else 12f) || kotlin.math.abs(pitch - shownPitch[i]) > 2f
        if (!moved && !turned) return
        if (near) {
            val jump = kotlin.math.sqrt(dx * dx + dz * dz + dy * dy)
            if (jump > TELEPORT_DISTANCE) {
                teleports++
                if (teleports <= 40) com.grandstrategy.GrandStrategy.LOGGER.warn(
                    "Agent {} jumped {} blocks (state {}, guided {}) from {} {} {} to {} {} {}", agents.uid[i], "%.2f".format(jump), agents.state[i], agents.guided[i],
                    "%.1f".format(shownX[i]), "%.1f".format(shownY[i]), "%.1f".format(shownZ[i]), "%.1f".format(x), "%.1f".format(y), "%.1f".format(z)
                )
            }
        }
        val headTurned = kotlin.math.abs(yaw - shownYaw[i]) > (if (near) 2f else 20f)
        shownX[i] = x
        shownZ[i] = z
        shownY[i] = y
        shownYaw[i] = yaw
        shownPitch[i] = pitch
        val pos = Vec3(x.toDouble(), y.toDouble(), z.toDouble())
        val codec = codecs.getOrPut(i) { VecDeltaCodec().also { it.setBase(pos) } }
        // Small steps go as relative moves (~12 bytes); an exact sync now and then stops rounding drift.
        val delta: VecDelta? = if (ticks - lastSync[i] >= RESYNC_TICKS) null else codec.tryEncode(pos)
        val move: Packet<in ClientGamePacketListener> = if (delta == null) {
            lastSync[i] = ticks
            ClientboundEntityPositionSyncPacket(netId[i], PositionPath.of(pos), yaw, pitch, true)
        } else {
            ClientboundMoveEntityPacket.PosRot(netId[i], delta, Mth.packDegrees(yaw), Mth.packDegrees(pitch), true)
        }
        codec.setBase(pos)
        val head = if (headTurned) headPacket(netId[i], yaw) else null
        for ((uuid, seen) in viewers) {
            if (!seen.contains(i)) continue
            val box = outbox.getOrPut(uuid) { ArrayList() }
            box += move
            bytesEstimate += if (delta == null) 60 else 14
            if (head != null) {
                box.add(head)
                bytesEstimate += 5
            }
        }
    }

    private fun flush() {
        for ((uuid, list) in outbox) {
            if (list.isEmpty()) continue
            val player = rt.server.playerList.getPlayer(uuid)
            if (player != null) {
                var k = 0
                while (k < list.size) {
                    val part = list.subList(k, minOf(list.size, k + BUNDLE_LIMIT))
                    player.connection.send(if (part.size == 1) part[0] else ClientboundBundlePacket(ArrayList(part)))
                    packetsLastTick += part.size
                    k += BUNDLE_LIMIT
                }
            }
            list.clear()
        }
    }

    private fun show(player: ServerPlayer, i: Int): Boolean {
        if (netId[i] == 0) netId[i] = level.nextEntityId
        // An agent already on someone's screen spawns where the others see it, so everyone shares one delta base.
        val already = codecs.containsKey(i)
        val x = if (already) shownX[i] else agents.x[i]
        val z = if (already) shownZ[i] else agents.z[i]
        val y = if (already) shownY[i] else if (agents.guided[i] && !agents.y[i].isNaN()) agents.y[i] else (farY(i, x, z) ?: return false)
        if (!already) {
            shownX[i] = x
            shownZ[i] = z
            shownY[i] = y
            val (yaw, pitch) = pose(i)
            shownYaw[i] = yaw
            shownPitch[i] = pitch
            codecs[i] = VecDeltaCodec().also { it.setBase(Vec3(x.toDouble(), y.toDouble(), z.toDouble())) }
            lastSync[i] = ticks
        }
        val yaw = shownYaw[i]
        val pitch = shownPitch[i]
        if (skin[i] < 0) skin[i] = skinFor(i, x, z).toByte()
        val uuid = UUID(0x6D696E656CL, agents.uid[i].toLong())
        val box = outbox.getOrPut(player.uuid) { ArrayList() }
        box += ClientboundAddEntityPacket(netId[i], uuid, x.toDouble(), y.toDouble(), z.toDouble(), pitch, yaw, EntityTypes.VILLAGER, 0, Vec3.ZERO, yaw.toDouble())
        box += ClientboundSetEntityDataPacket(netId[i], template(skin[i].toInt()))
        tool(i)?.let { item ->
            box += ClientboundSetEquipmentPacket(netId[i], listOf(com.mojang.datafixers.util.Pair(EquipmentSlot.MAINHAND, ItemStack(item))))
        }
        bytesEstimate += 100
        return true
    }

    private fun tool(i: Int): net.minecraft.world.item.Item? = when (Job.entries[agents.job[i].toInt()]) {
        Job.FARMER -> Items.IRON_HOE
        Job.LUMBERJACK -> Items.IRON_AXE
        Job.MINER -> Items.IRON_PICKAXE
        Job.BUILDER -> Items.IRON_SHOVEL
        Job.MERCHANT -> Items.EMERALD
        Job.SCHOLAR -> Items.BOOK
        Job.SOLDIER -> Items.IRON_SWORD
        Job.CHILD -> null
    }

    private fun hide(player: ServerPlayer, seen: IntOpenHashSet, gone: IntOpenHashSet) {
        if (gone.isEmpty()) return
        val ids = IntArrayList()
        val iter = gone.iterator()
        while (iter.hasNext()) {
            val i = iter.nextInt()
            seen.remove(i)
            if (netId[i] != 0) ids.add(netId[i])
            if (viewers.values.none { it.contains(i) }) codecs.remove(i)
        }
        if (ids.isNotEmpty()) {
            outbox.getOrPut(player.uuid) { ArrayList() } += ClientboundRemoveEntitiesPacket(ids)
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
        shown.remove(i)
        warm.remove(i)
        codecs.remove(i)
        walker.forget(i)
    }

    fun removePlayer(uuid: UUID) {
        viewers.remove(uuid)?.let { dropUnseen(it) }
    }

    /**
     * Forgets the shared position base of agents nobody sees any more. Without this, a player who reconnects would get
     * them spawned at the last position anyone saw, and the next update would show the gap as a jump.
     */
    private fun dropUnseen(ids: IntOpenHashSet) {
        val iter = ids.iterator()
        while (iter.hasNext()) {
            val i = iter.nextInt()
            if (viewers.values.none { it.contains(i) }) codecs.remove(i)
        }
    }

    fun hideAll() {
        for ((uuid, seen) in viewers) {
            val player = rt.server.playerList.getPlayer(uuid) ?: continue
            hide(player, seen, IntOpenHashSet(seen))
        }
        flush()
        viewers.clear()
        shown.clear()
        warm.clear()
        codecs.clear()
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

    private fun headPacket(id: Int, yaw: Float): ClientboundRotateHeadPacket {
        val buf = FriendlyByteBuf(Unpooled.buffer(8))
        buf.writeVarInt(id)
        buf.writeByte(floor(yaw * 256f / 360f).toInt())
        return ClientboundRotateHeadPacket.STREAM_CODEC.decode(buf)
    }

    private companion object {
        const val GRID_INTERVAL = 10L
        const val VIEW_INTERVAL = 10L
        const val UPDATE_INTERVAL = 2L
        const val TELEPORT_DISTANCE = 1.2f
        const val SNAPS_PER_UPDATE = 40
        const val RESYNC_TICKS = 100L
        const val DISTANT = 96f
        const val BUNDLE_LIMIT = 4000
    }
}
