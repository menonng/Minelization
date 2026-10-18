package com.grandstrategy.mc.build

import com.grandstrategy.mc.Runtime
import com.grandstrategy.sim.Balance
import com.grandstrategy.sim.Box
import com.grandstrategy.sim.Building
import com.grandstrategy.sim.BuildingKind
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Districts
import com.grandstrategy.sim.Nation
import com.grandstrategy.sim.Outcome
import com.grandstrategy.sim.Road
import com.grandstrategy.sim.RoadGround
import com.grandstrategy.sim.RoadKind
import com.grandstrategy.sim.RoadNetwork
import com.grandstrategy.sim.RoadPathfinder
import com.grandstrategy.sim.RoadLots
import com.grandstrategy.sim.TechBranch
import com.grandstrategy.sim.Terrain
import com.grandstrategy.sim.WorkOrder
import com.grandstrategy.sim.WorkType
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.Heightmap

/**
 * Reads the live world for road planning. Land nobody has loaded is guessed from the chunk's terrain class (flat,
 * passable unless it is water or mountain), so AI towns far from any player still lay out streets; the blocks are
 * fitted to the real ground when builders pave them after the chunk loads.
 */
class WorldRoadGround(private val level: ServerLevel, private val terrain: (Long) -> Terrain) : RoadGround {
    private val heights = HashMap<Long, Int>()
    private val costs = HashMap<Long, Double>()

    private fun loaded(x: Int, z: Int) = level.chunkSource.hasChunk(x shr 4, z shr 4)

    override fun exact(x: Int, z: Int): Boolean = loaded(x, z)

    override fun height(x: Int, z: Int): Int? {
        val key = Chunks.pack(x, z)
        heights[key]?.let { return if (it == Int.MIN_VALUE) null else it }
        val h = when {
            loaded(x, z) -> RoadBlocks.groundY(level, x, z)
            terrain(Chunks.pack(x shr 4, z shr 4)).let { it == Terrain.WATER || it == Terrain.MOUNTAIN } -> Int.MIN_VALUE
            else -> level.seaLevel
        }
        heights[key] = h
        return if (h == Int.MIN_VALUE) null else h
    }

    override fun cost(x: Int, z: Int): Double = costs.getOrPut(Chunks.pack(x, z)) {
        val y = height(x, z) ?: return@getOrPut Double.POSITIVE_INFINITY
        if (!loaded(x, z)) return@getOrPut 0.5
        val surface = level.getBlockState(BlockPos(x, y, z))
        val above = level.getBlockState(BlockPos(x, y + 1, z))
        when {
            surface.fluidState.`is`(FluidTags.LAVA) -> Double.POSITIVE_INFINITY
            !surface.fluidState.isEmpty -> 6.0
            surface.`is`(Blocks.DIRT_PATH) || RoadBlocks.paving(surface) -> -1.0
            surface.`is`(Blocks.FARMLAND) || above.`is`(BlockTags.CROPS) -> 10.0
            surface.`is`(BlockTags.LOGS) || above.`is`(BlockTags.LOGS) -> Double.POSITIVE_INFINITY
            !BlockClass.terrain(surface) && !BlockClass.vegetation(surface) -> Double.POSITIVE_INFINITY
            above.`is`(BlockTags.LEAVES) || surface.`is`(BlockTags.LEAVES) -> 3.0
            surface.`is`(BlockTags.SAND) || surface.`is`(Blocks.GRAVEL) -> 0.3
            else -> 0.0
        }
    }
}

/**
 * Road materials. Paving follows the nation's era as defined in content.json (dirt path in the stone age, cobble in
 * classical times, concrete in the modern age...), so editing that file changes what new and repaved roads look like.
 */
object RoadBlocks {
    private val LEGACY = setOf(
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE, Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
        Blocks.POLISHED_ANDESITE, Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.GRAY),
        Blocks.CONCRETE.pick(net.minecraft.world.item.DyeColor.LIGHT_GRAY), Blocks.COARSE_DIRT
    )
    private val LEGACY_BRIDGES = setOf(Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE)

    /** Paving and bridge blocks of every era, rebuilt whenever the content is reloaded. */
    private var cachedFor: com.grandstrategy.sim.ContentConfig? = null
    private var pavingSet: Set<net.minecraft.world.level.block.Block> = LEGACY
    private var bridgeSet: Set<net.minecraft.world.level.block.Block> = LEGACY_BRIDGES
    private val byId = HashMap<String, BlockState>()

    private fun refresh() {
        val content = com.grandstrategy.sim.Content.config
        if (cachedFor === content) return
        cachedFor = content
        byId.clear()
        pavingSet = LEGACY + content.eras.flatMap { e -> e.road + listOfNotNull(e.plaza) }.mapNotNull { block(it)?.block }
        bridgeSet = LEGACY_BRIDGES + content.eras.mapNotNull { block(it.bridge)?.block }
    }

    /** A block by id ("minecraft:stone_bricks"), or null if no such block exists. */
    fun block(id: String): BlockState? = byId.getOrPut(id) {
        val parsed = net.minecraft.resources.Identifier.tryParse(id) ?: return null
        val b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getOptional(parsed).orElse(null) ?: return null
        b.defaultBlockState()
    }

    fun paving(state: BlockState): Boolean {
        refresh()
        return state.block in pavingSet
    }

    /** Top block a road would replace: the motion-blocking surface, looking under snow layers. */
    fun groundY(level: ServerLevel, x: Int, z: Int): Int {
        var y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1
        val pos = BlockPos.MutableBlockPos(x, y, z)
        while (y > level.minY && level.getBlockState(pos.setY(y)).`is`(Blocks.SNOW)) y--
        return y
    }

    fun bridge(state: BlockState): Boolean {
        refresh()
        return state.block in bridgeSet
    }

    /** Anything our roads (or vanilla village paths) are made of; villagers walk these more willingly. */
    fun road(state: BlockState): Boolean = state.`is`(Blocks.DIRT_PATH) || paving(state)

    fun era(nation: Nation?): com.grandstrategy.sim.EraDef {
        val content = com.grandstrategy.sim.Content.config
        return nation?.let { content.era(it) } ?: content.eras.first()
    }

    /** Surface block for a column, varied by position so long roads look worn rather than printed. */
    fun surface(era: com.grandstrategy.sim.EraDef, kind: RoadKind, x: Int, z: Int): BlockState {
        val r = ((x * 73856093) xor (z * 19349663)).let { (it xor (it ushr 13)) and 0xFF }
        if (kind == RoadKind.PLAZA) era.plaza?.let { id -> if (r < 190) block(id)?.let { return it } }
        val ids = era.road.ifEmpty { listOf("minecraft:dirt_path") }
        // The first block covers about 70% of the road; the others share the rest.
        val id = if (ids.size == 1 || r < 180) ids[0] else ids[1 + (r % (ids.size - 1))]
        return block(id) ?: Blocks.DIRT_PATH.defaultBlockState()
    }

    fun bridge(era: com.grandstrategy.sim.EraDef): BlockState = block(era.bridge) ?: Blocks.OAK_PLANKS.defaultBlockState()
}

/**
 * Lays out streets on its own, WorldBox style: first a small plaza at the capital, then a street from every finished
 * building's door to the nearest road, then highways from the nation's towns. Each plan becomes an ordinary ROAD work
 * order, so builders walk out and pave it block by block, the same way a player's order is carried out.
 * Players can lay roads themselves and tear any road up; torn-up columns are never paved automatically again.
 */
class RoadPlanner(private val rt: Runtime, private val work: WorkManager) {
    val network = RoadNetwork(rt.state)
    private val retryAt = HashMap<String, Long>()
    private val failures = HashMap<String, Int>()
    /** Why the last planning attempt for a nation ended, for `/gs work roads`. */
    private val lastReason = HashMap<Int, String>()
    /** Nations whose builders found no free lot on the streets; the next plan opens a new street for them. */
    private val frontage = HashSet<Int>()
    /** Era each nation was last seen in, and roads waiting to be repaved in a new era's material. */
    private val lastEra = HashMap<Int, String>()
    private val repaveQueue = HashMap<Int, ArrayDeque<Int>>()
    private var ticks = 0L
    private var cursor = 0
    var planned = 0
        private set
    var failed = 0
        private set

    private val state get() = rt.state
    private val level: ServerLevel get() = rt.server.overworld()
    private val tuning get() = Balance.config.roads

    fun tick() {
        ticks++
        if (ticks % tuning.planIntervalTicks.coerceAtLeast(1) != 0L) return
        val nations = state.alive()
        if (nations.isEmpty()) return
        repeat(nations.size) {
            val nation = nations[Math.floorMod(cursor++, nations.size)]
            if (planFor(nation)) return
        }
    }

    /**
     * Road orders still being worked. Orders on unloaded land finish abstractly and only wait to be placed when the
     * chunk loads, so they don't hold the town back — up to a hard cap, so an unwatched town can't queue forever.
     */
    fun pending(nation: Int): Int {
        val roads = state.orders.values.filter { it.nation == nation && it.type == WorkType.ROAD }
        return if (roads.size >= MAX_QUEUED) Int.MAX_VALUE else roads.count { !it.pendingPlacement }
    }

    private companion object {
        const val MAX_QUEUED = 10
    }

    private fun cooling(key: String) = (retryAt[key] ?: 0L) > ticks

    private fun coolDown(key: String, ticks: Long) {
        retryAt[key] = this.ticks + ticks
    }

    /** Plans at most one road for the nation. True when something was ordered. */
    fun planFor(nation: Nation): Boolean {
        fun stop(reason: String): Boolean {
            lastReason[nation.id] = reason
            return false
        }
        if (pending(nation.id) >= tuning.maxPendingPerNation) {
            // A district may go ahead of the queue: their number is bounded by the nation's land, and a town nobody
            // watches would otherwise sit behind orders waiting for their chunks to load and never spread out.
            if (network.hasNetwork(nation.id) && district(nation, ground())) return true
            return stop("공사 대기 중")
        }
        val era = com.grandstrategy.sim.Content.config.era(nation).id
        val before = lastEra.put(nation.id, era)
        if (before != null && before != era) {
            repaveQueue[nation.id] = ArrayDeque(state.roads.values.filter { it.nation == nation.id && it.built }.map { it.id })
        }
        repaveQueue[nation.id]?.let { queue ->
            while (queue.isNotEmpty()) {
                val road = state.roads[queue.removeFirst()] ?: continue
                if (state.orders.values.any { it.road == road.id }) continue
                road.built = false
                work.orderRoad(nation, road, null, false)
                lastReason[nation.id] = "새 시대에 맞춰 길 다시 포장 (남은 ${queue.size}개)"
                return true
            }
            repaveQueue.remove(nation.id)
        }
        val ground = ground()
        if (widenOld(nation, ground)) return true
        val capitalX = Chunks.x(nation.capital) * 16 + 8
        val capitalZ = Chunks.z(nation.capital) * 16 + 8
        val hub = hub(nation, ground) ?: return stop(if (ground.height(capitalX, capitalZ) == null) "수도가 불러와지지 않음" else "수도 주변에 빈 땅이 없음")
        if (!network.hasNetwork(nation.id)) {
            val blocked = blocker(nation, Chunks.x(hub), Chunks.z(hub), 4)
            if (cooling("plaza:${nation.id}")) return stop("광장 재시도 대기")
            val cells = RoadPathfinder.plaza(Chunks.x(hub), Chunks.z(hub), 2, ground, blocked)
            if (cells.size < 5) {
                coolDown("plaza:${nation.id}", 1200)
                return stop("광장 자리가 고르지 않음 (${cells.size}칸, 중심 ${Chunks.x(hub)}, ${Chunks.z(hub)})")
            }
            lastReason[nation.id] = "광장"
            return order(nation, RoadKind.PLAZA, cells)
        }
        val buildings = state.buildings.values
            .filter { it.nation == nation.id && it.complete && !it.linked && it.kind != BuildingKind.WALLS }
            .sortedBy { dist2(it.box.centerX, it.box.centerZ, hub) }
        for (b in buildings.take(4)) {
            val key = "b:${b.id}"
            if (cooling(key)) continue
            val blocked = blocker(nation, b.box.centerX.toInt(), b.box.centerZ.toInt(), tuning.streetRange + 16)
            if (street(nation, b, ground, blocked)) {
                lastReason[nation.id] = "골목길 (건물 #${b.id})"
                return true
            }
        }
        for (town in state.settlements.values) {
            if (town.owner != nation.id) continue
            val key = "t:${town.id}"
            if (cooling(key)) continue
            val blocked = blocker(nation, Chunks.x(town.center) * 16 + 8, Chunks.z(town.center) * 16 + 8, tuning.highwayRange + 16)
            if (highway(nation, town.center, key, ground, blocked)) {
                lastReason[nation.id] = "간선 도로 (${town.name})"
                return true
            }
        }
        // Checked on every pass, not only when the streets run out of lots: lots almost never run out, so a district
        // that waited for that was never founded and the capital kept packing.
        if (district(nation, ground)) return true
        if (nation.id in frontage || buildings.isEmpty() && network.cellsOf(nation.id).size < 40) {
            if (extend(nation, ground, hub)) {
                frontage -= nation.id
                lastReason[nation.id] = "새 길 (집 지을 자리)"
                return true
            }
            return stop("새 길을 낼 곳을 못 찾음")
        }
        return stop(if (buildings.isEmpty()) "할 일 없음" else "건물 ${buildings.size}개 연결 실패 또는 대기")
    }

    private fun ground() = WorldRoadGround(level) { rt.sim.terrain(it) }

    /**
     * Roads laid before roads were three blocks wide get their sides added, one road per planning pass (players'
     * own roads are left as they drew them). Unfinished ones wait until they are built.
     */
    private fun widenOld(nation: Nation, ground: RoadGround): Boolean {
        if (tuning.width <= 1) return false
        val narrow = state.roads.values.filter {
            it.nation == nation.id && !it.wide && it.built && (it.kind == RoadKind.STREET || it.kind == RoadKind.HIGHWAY)
        }
        if (narrow.isEmpty()) return false
        // Several old roads of one kind go into one order (up to about 300 new columns), so a town with hundreds of
        // narrow streets is widened in tens of orders, not hundreds.
        val kind = narrow.first().kind
        val extra = LinkedHashSet<Long>()
        var count = 0
        for (road in narrow) {
            if (road.kind != kind) continue
            if (count >= 12 || extra.size >= 300) break
            road.wide = true
            count++
            if (road.cells.isEmpty()) continue
            val xs = road.cells.map { Chunks.x(it) }
            val zs = road.cells.map { Chunks.z(it) }
            val range = maxOf(xs.max() - xs.min(), zs.max() - zs.min()) / 2 + 8
            val blocked = blocker(nation, (xs.min() + xs.max()) / 2, (zs.min() + zs.max()) / 2, range)
            for (c in RoadPathfinder.widen(road.cells, tuning.width, ground, blocked)) if (!network.isRoad(c)) extra += c
        }
        if (extra.isEmpty()) return false
        lastReason[nation.id] = "옛 길 넓히기 (${count}개, 남은 ${narrow.size - count}개)"
        return order(nation, kind, extra.toList())
    }

    /**
     * Gives each administrative district big enough for one its own town core (see [Districts]): a plaza near its seat
     * and a highway to the rest of the roads. Waits a while after a failed attempt.
     */
    private fun district(nation: Nation, ground: RoadGround): Boolean {
        if (cooling("district:${nation.id}")) return false
        val district = Districts.needingCore(state, nation).firstOrNull { !cooling("core:${it.id}") } ?: return false
        if (newDistrict(nation, district, ground)) {
            frontage -= nation.id
            lastReason[nation.id] = "행정구역 ${district.name} 중심 광장"
            return true
        }
        coolDown("core:${district.id}", 2400)
        return false
    }

    /**
     * Lays a district's town core: picks a chunk of it (its seat, towns and fertile land first), lays a plaza at its
     * centre and a highway from there to the network. New houses then prefer it, so people spread over the land
     * instead of all crowding the capital.
     */
    private fun newDistrict(nation: Nation, district: com.grandstrategy.sim.District, ground: RoadGround): Boolean {
        val candidates = Districts.coreSpots(state, district, { rt.sim.terrain(it) }) { rt.sim.rng.nextDouble() }.filter { !cooling("dc:$it") }
        val own = network.cellsOf(nation.id)
        if (own.isEmpty()) return false
        for (c in candidates.take(4)) {
            val x = Chunks.x(c) * 16 + 8
            val z = Chunks.z(c) * 16 + 8
            val blocked = blocker(nation, x, z, tuning.highwayRange + 16)
            // The plaza is what makes the spot a town core, so a spot without room for one is no good.
            val plaza = if (ground.height(x, z) == null || blocked(x, z)) emptyList() else RoadPathfinder.plaza(x, z, 2, ground, blocked).filter { !network.isRoad(it) }
            if (plaza.size < 5) {
                coolDown("dc:$c", 6000)
                continue
            }
            val target = own.minBy { dist2(x.toDouble(), z.toDouble(), it) }
            val path = RoadPathfinder.find(
                ground, x, z, Chunks.x(target), Chunks.z(target),
                goal = { network.nationAt(it) == nation.id },
                existing = { network.nationAt(it) == nation.id },
                blocked = blocked, range = tuning.highwayRange, maxNodes = tuning.highwayNodes, greed = 1.3
            )
            if (path == null) {
                failed++
                coolDown("dc:$c", 6000)
                continue
            }
            if (!order(nation, RoadKind.PLAZA, plaza)) continue
            val wide = RoadPathfinder.widen(path, tuning.width, ground, blocked).filter { !network.isRoad(it) }
            if (wide.isNotEmpty()) order(nation, RoadKind.HIGHWAY, wide)
            com.grandstrategy.GrandStrategy.LOGGER.info("Town core for {} district {} at chunk {}", nation.name, district.name, Chunks.format(c))
            return true
        }
        return false
    }

    fun wantFrontage(nation: Int) {
        frontage += nation
    }

    /**
     * Opens a new street: from a point some 18-28 blocks out from a random existing road cell, inside our own land and
     * away from other streets, back to the network. Points nearer the plaza win, so the town grows in rings.
     */
    private fun extend(nation: Nation, ground: RoadGround, hub: Long): Boolean {
        val own = network.cellsOf(nation.id)
        if (own.isEmpty()) return false
        val rng = rt.sim.rng
        val boxes = state.buildings.values.filter { it.kind != BuildingKind.WALLS }.map { it.box }
        var best: Long? = null
        var from: Long? = null
        var bestScore = Double.MAX_VALUE
        repeat(32) {
            val c = own[rng.nextInt(own.size)]
            val d = rng.nextInt(4)
            val len = 18 + rng.nextInt(11)
            val tx = Chunks.x(c) + intArrayOf(1, -1, 0, 0)[d] * len
            val tz = Chunks.z(c) + intArrayOf(0, 0, 1, -1)[d] * len
            if (state.owners[Chunks.pack(tx shr 4, tz shr 4)] != nation.id) return@repeat
            if (Chunks.pack(tx, tz) in state.roadBans) return@repeat
            if (boxes.any { tx >= it.minX - 2 && tx <= it.maxX + 2 && tz >= it.minZ - 2 && tz <= it.maxZ + 2 }) return@repeat
            if (network.anyIn(tx - 7, tz - 7, tx + 7, tz + 7)) return@repeat
            if (ground.height(tx, tz) == null || ground.cost(tx, tz) >= 6.0) return@repeat
            val score = kotlin.math.sqrt(dist2(tx.toDouble(), tz.toDouble(), hub)) + rng.nextDouble() * 12
            if (score < bestScore) {
                bestScore = score
                best = Chunks.pack(tx, tz)
                from = c
            }
        }
        val target = best ?: return false
        val tx = Chunks.x(target)
        val tz = Chunks.z(target)
        val blocked = blocker(nation, tx, tz, tuning.streetRange + 16)
        val path = RoadPathfinder.find(
            ground, tx, tz, Chunks.x(from!!), Chunks.z(from!!),
            goal = { network.nationAt(it) == nation.id },
            existing = { network.nationAt(it) == nation.id },
            blocked = blocked, range = tuning.streetRange, maxNodes = tuning.streetNodes
        ) ?: return false
        val fresh = RoadPathfinder.widen(path, tuning.width, ground, blocked).filter { !network.isRoad(it) }
        if (fresh.size < 4) return false
        return order(nation, RoadKind.STREET, fresh)
    }

    /**
     * A free lot of [sizeX]×[sizeZ] beside one of the nation's streets: inside its land, clear of buildings and roads
     * (keeping a one-block yard), not on water, and on loaded land also fairly level and free of unregistered builds.
     */
    /** A lot for a design [width] wide and [depth] deep, with the rotation that turns its front to the street. */
    fun findLot(nation: Nation, width: Int, depth: Int): Pair<Box, Int>? {
        val cells = network.cellsOf(nation.id)
        if (cells.isEmpty()) return null
        val buildings = state.buildings.values.toList()
        // Near a town core, but the less crowded cores first: each building already there counts as 4 blocks of walking.
        val hubs = Districts.hubs(state, nation).map { h -> Triple(h.first, h.second, Districts.crowd(state, nation, h, tuning.districtRadius) * 4.0) }
        val score = { c: Long ->
            hubs.minOf { (hx, hz, penalty) ->
                val dx = (Chunks.x(c) - hx).toDouble()
                val dz = (Chunks.z(c) - hz).toDouble()
                kotlin.math.sqrt(dx * dx + dz * dz) + penalty
            }
        }
        return RoadLots.find(cells, score, width, depth, rt.sim.rng, { box -> lotOk(nation, box, buildings) })
    }

    private fun lotOk(nation: Nation, box: Box, buildings: List<Building>): Boolean {
        for (c in box.chunks()) {
            if (state.owners[c] != nation.id) return false
            if (rt.sim.terrain(c) == Terrain.WATER) return false
        }
        if (buildings.any { work.clashes(it, box) }) return false
        if (network.anyIn(box.minX - RoadLots.YARD, box.minZ - RoadLots.YARD, box.maxX + RoadLots.YARD, box.maxZ + RoadLots.YARD)) return false
        if (!BlockClass.loaded(level, box)) return true
        if (work.waterError(nation, box) != null) return false
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (x in box.minX..box.maxX step 2) for (z in box.minZ..box.maxZ step 2) {
            val y = RoadBlocks.groundY(level, x, z)
            lo = minOf(lo, y)
            hi = maxOf(hi, y)
            val top = level.getBlockState(BlockPos(x, y, z))
            if (BlockClass.constructed(top) && !RoadBlocks.road(top) && !top.`is`(BlockTags.LOGS)) return false
        }
        return hi - lo <= 4
    }

    /** The building rule: some road (ours, or any path block on the ground) within [RoadLots.REACH] blocks of the footprint. */
    fun nearRoad(box: Box): Boolean = RoadLots.near(box, RoadLots.REACH) { x, z ->
        network.isRoad(Chunks.pack(x, z)) || (level.chunkSource.hasChunk(x shr 4, z shr 4) && RoadBlocks.road(level.getBlockState(BlockPos(x, RoadBlocks.groundY(level, x, z), z))))
    }

    /** Road cells planned but not yet paved near a point, so the client can count them as road while drawing a lot. */
    fun plannedNear(x: Int, z: Int, radius: Int, limit: Int = 2000): List<Long> {
        val out = ArrayList<Long>()
        for (road in state.roads.values) {
            if (road.built) continue
            for (c in road.cells) {
                if (kotlin.math.abs(Chunks.x(c) - x) <= radius && kotlin.math.abs(Chunks.z(c) - z) <= radius) {
                    out += c
                    if (out.size >= limit) return out
                }
            }
        }
        return out
    }

    /** The capital's ground column nearest the chunk center that is free to pave. */
    private fun hub(nation: Nation, ground: RoadGround): Long? {
        val cx = Chunks.x(nation.capital) * 16 + 8
        val cz = Chunks.z(nation.capital) * 16 + 8
        if (ground.height(cx, cz) == null) return null
        val blocked = blocker(nation, cx, cz, 12)
        for (r in 0..8) for (dx in -r..r) for (dz in -r..r) {
            if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dz)) != r) continue
            val x = cx + dx
            val z = cz + dz
            if (blocked(x, z) || ground.height(x, z) == null || ground.cost(x, z) >= 6.0) continue
            return Chunks.pack(x, z)
        }
        return null
    }

    private fun dist2(x: Double, z: Double, cell: Long): Double {
        val dx = x - Chunks.x(cell)
        val dz = z - Chunks.z(cell)
        return dx * dx + dz * dz
    }

    /** Buildings, player bans and foreign land are off limits. */
    private fun blocker(nation: Nation, cx: Int, cz: Int, range: Int): (Int, Int) -> Boolean {
        val boxes = state.buildings.values.filter { it.kind != BuildingKind.WALLS }.map { it.box }.filter {
            it.maxX >= cx - range && it.minX <= cx + range && it.maxZ >= cz - range && it.minZ <= cz + range
        }
        return { x, z ->
            val cell = Chunks.pack(x, z)
            cell in state.roadBans ||
                boxes.any { x >= it.minX && x <= it.maxX && z >= it.minZ && z <= it.maxZ } ||
                state.owners[Chunks.pack(x shr 4, z shr 4)].let { it != null && it != nation.id } ||
                !rt.zones.allowed(nation.id, Chunks.pack(x shr 4, z shr 4))
        }
    }

    private fun street(nation: Nation, b: Building, ground: RoadGround, blocked: (Int, Int) -> Boolean): Boolean {
        val key = "b:${b.id}"
        val own = network.cellsOf(nation.id)
        if (own.isEmpty()) return false
        val doors = doors(b.box, own)
        // Already touching a road: nothing to do.
        if (doors.any { d -> network.nationAt(d) == nation.id }) {
            b.linked = true
            return false
        }
        for (door in doors) {
            val x = Chunks.x(door)
            val z = Chunks.z(door)
            if (blocked(x, z) || ground.height(x, z) == null) continue
            val target = own.minBy { dist2(x.toDouble(), z.toDouble(), it) }
            val path = RoadPathfinder.find(
                ground, x, z, Chunks.x(target), Chunks.z(target),
                goal = { network.nationAt(it) == nation.id },
                existing = { network.nationAt(it) == nation.id },
                blocked = blocked, range = tuning.streetRange, maxNodes = tuning.streetNodes
            ) ?: continue
            b.linked = true
            val fresh = RoadPathfinder.widen(path, tuning.width, ground, blocked).filter { !network.isRoad(it) }
            if (fresh.isEmpty()) return false
            return order(nation, RoadKind.STREET, fresh)
        }
        failed++
        val tries = failures.merge(key, 1, Int::plus)!!
        // Some houses sit on a ledge or in a crowded corner; give up after a few tries instead of searching forever.
        if (tries >= 3) b.linked = true else coolDown(key, 2400)
        return false
    }

    /** Door candidates: the middle of each side, one block out, nearest side to the existing streets first. */
    private fun doors(box: Box, toward: List<Long>): List<Long> {
        val mx = (box.minX + box.maxX) / 2
        val mz = (box.minZ + box.maxZ) / 2
        val sides = listOf(
            Chunks.pack(box.maxX + 1, mz), Chunks.pack(box.minX - 1, mz),
            Chunks.pack(mx, box.maxZ + 1), Chunks.pack(mx, box.minZ - 1)
        )
        return sides.sortedBy { s -> toward.minOf { dist2(Chunks.x(s).toDouble(), Chunks.z(s).toDouble(), it) } }
    }

    private fun highway(nation: Nation, center: Long, key: String, ground: RoadGround, blocked: (Int, Int) -> Boolean): Boolean {
        val x = Chunks.x(center) * 16 + 8
        val z = Chunks.z(center) * 16 + 8
        if (network.nationAt(Chunks.pack(x, z)) == nation.id) return false
        val own = network.cellsOf(nation.id)
        if (own.isEmpty()) return false
        val target = own.minBy { dist2(x.toDouble(), z.toDouble(), it) }
        if (dist2(x.toDouble(), z.toDouble(), target) > tuning.highwayRange.toDouble() * tuning.highwayRange) return false
        var start: Long? = null
        for (r in 0..6) {
            for (dx in -r..r) for (dz in -r..r) {
                val sx = x + dx
                val sz = z + dz
                if (!blocked(sx, sz) && ground.height(sx, sz) != null && ground.cost(sx, sz) < 6.0) {
                    start = Chunks.pack(sx, sz)
                    break
                }
            }
            if (start != null) break
        }
        if (start == null) {
            coolDown(key, 6000)
            return false
        }
        val path = RoadPathfinder.find(
            ground, Chunks.x(start), Chunks.z(start), Chunks.x(target), Chunks.z(target),
            goal = { network.nationAt(it) == nation.id },
            existing = { network.nationAt(it) == nation.id },
            blocked = blocked, range = tuning.highwayRange, maxNodes = tuning.highwayNodes, greed = 1.3
        )
        if (path == null) {
            failed++
            coolDown(key, 6000)
            return false
        }
        val wide = RoadPathfinder.widen(path, tuning.width, ground, blocked).filter { !network.isRoad(it) }
        coolDown(key, 1200)
        if (wide.isEmpty()) return false
        return order(nation, RoadKind.HIGHWAY, wide)
    }

    private fun order(nation: Nation, kind: RoadKind, cells: List<Long>, by: String? = null, conscript: Boolean = false): Boolean {
        val road = network.add(nation.id, kind, cells) ?: return false
        road.wide = true
        work.orderRoad(nation, road, by, conscript)
        planned++
        com.grandstrategy.GrandStrategy.LOGGER.info("Road planned for {}: {} with {} cells", nation.name, kind, road.cells.size)
        return true
    }

    /** Player tool: pave every free column in the rectangle. */
    fun requestManual(nation: Nation, area: Box, by: String?, conscript: Boolean): Outcome {
        work.territoryError(nation, area)?.let { return Outcome.fail(it) }
        if (!BlockClass.loaded(level, area)) return Outcome.fail("그 땅이 아직 불러와지지 않았다.")
        val boxes = state.buildings.values.filter { it.kind != BuildingKind.WALLS }.map { it.box }
        val cells = ArrayList<Long>()
        for (x in area.minX..area.maxX) for (z in area.minZ..area.maxZ) {
            if (boxes.any { x >= it.minX && x <= it.maxX && z >= it.minZ && z <= it.maxZ }) continue
            val cell = Chunks.pack(x, z)
            state.roadBans -= cell
            cells += cell
        }
        if (cells.isEmpty()) return Outcome.fail("건물 안이라 길을 낼 칸이 없다.")
        val road = network.add(nation.id, RoadKind.MANUAL, cells) ?: return Outcome.fail("이미 전부 길이다.")
        work.orderRoad(nation, road, by, conscript)
        return Outcome.ok("길을 내도록 지시했다 (${road.cells.size}칸).")
    }

    /** Player tool: take road (ours or a vanilla village path) out of the rectangle and keep the planner away from it. */
    fun requestClear(nation: Nation, area: Box, by: String?, conscript: Boolean): Outcome {
        work.territoryError(nation, area)?.let { return Outcome.fail(it) }
        val affected = HashSet<Int>()
        for (x in area.minX..area.maxX) for (z in area.minZ..area.maxZ) network.roadAt(Chunks.pack(x, z))?.let { affected += it.id }
        val removed = network.clear(area.minX, area.minZ, area.maxX, area.maxZ, ban = true)
        // Orders still paving the affected roads are restarted on whatever is left of them.
        for (o in state.orders.values.toList()) {
            val id = o.road ?: continue
            if (o.type != WorkType.ROAD || id !in affected) continue
            work.cancel(o)
            state.roads[id]?.let { work.orderRoad(nation, it, o.by, o.conscript) }
        }
        val box = Box(area.minX, area.minY - 2, area.minZ, area.maxX, area.maxY + 3, area.maxZ)
        work.orderUnroad(nation, box, removed, by, conscript)
        return Outcome.ok("길을 걷어내도록 지시했다 (등록된 길 ${removed.size}칸 + 흙길). 이 자리엔 자동으로 길을 내지 않는다.")
    }

    /** Called when something is built over road: those columns stop being road without being banned. */
    fun builtOver(box: Box) {
        network.clear(box.minX, box.minZ, box.maxX, box.maxZ, ban = false)
    }

    fun forget(road: Road) = network.drop(road)

    fun status(): String {
        val byNation = state.roads.values.groupBy { it.nation }
        val lines = state.alive().map { n ->
            val roads = byNation[n.id].orEmpty()
            val built = roads.count { it.built }
            val why = lastReason[n.id]?.let { " · 마지막 판단: $it" } ?: ""
            val hubs = Districts.hubs(state, n)
            val crowds = hubs.joinToString("/") { Districts.crowd(state, n, it, tuning.districtRadius).toString() }
            val districts = Districts.ofNation(state, n)
            val narrow = roads.count { !it.wide && it.kind != RoadKind.MANUAL && it.kind != RoadKind.PLAZA }
            "${n.name}: 길 ${roads.size}개 (${roads.sumOf { it.cells.size }}칸, 완공 $built, 안 넓힌 길 $narrow) · 주문 ${state.orders.values.count { it.nation == n.id && it.type == WorkType.ROAD }}" +
                " · 행정구역 ${districts.size}개 (구역당 ${Districts.limit(n)}청크, 중심 광장 ${hubs.size}, 광장별 건물 $crowds)$why"
        }
        val head = "길 ${state.roads.size}개 · ${network.cellCount}칸 · 계획 $planned · 실패 $failed · 금지 칸 ${state.roadBans.size}"
        return (listOf(head) + lines).joinToString("\n")
    }

    /** Paves every road again (with the nation's current era of paving). */
    fun repave(): Int {
        var n = 0
        for (road in state.roads.values.toList()) {
            if (state.orders.values.any { it.road == road.id }) continue
            val nation = state.nation(road.nation) ?: continue
            road.built = false
            work.orderRoad(nation, road, null, false)
            n++
        }
        return n
    }

    /** Lets the planner revisit every building (after a balance change, for example). */
    fun replan() {
        for (b in state.buildings.values) b.linked = false
        retryAt.clear()
        failures.clear()
    }

    fun orderView(order: WorkOrder): Road? = order.road?.let { state.roads[it] }
}
