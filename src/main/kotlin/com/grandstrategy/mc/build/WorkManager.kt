package com.grandstrategy.mc.build

import com.grandstrategy.mc.Runtime
import com.grandstrategy.sim.Actions
import com.grandstrategy.sim.Balance
import com.grandstrategy.sim.Box
import com.grandstrategy.sim.Building
import com.grandstrategy.sim.BuildingKind
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.EventKind
import com.grandstrategy.sim.Job
import com.grandstrategy.sim.Nation
import com.grandstrategy.sim.Outcome
import com.grandstrategy.sim.Resource
import com.grandstrategy.sim.Road
import com.grandstrategy.sim.TechBranch
import com.grandstrategy.sim.Terrain
import com.grandstrategy.sim.WorkOrder
import com.grandstrategy.sim.WorkType
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import kotlin.math.max
import kotlin.math.sqrt

class WorkManager(private val rt: Runtime) {
    val templates = Templates(rt.server)
    private val level: ServerLevel get() = rt.server.overworld()
    private val state get() = rt.state
    private val plans = HashMap<Int, Plan>()
    private val crews = HashMap<Int, IntArrayList>()
    private val credit = HashMap<Int, Double>()
    private val virtualCrew = HashMap<Int, Int>()
    private var ticks = 0L

    private class Plan(val pos: LongArray, val op: IntArray) {
        var cursor = 0
    }

    private companion object {
        const val REMOVE_CONSTRUCTED = -1
        const val REMOVE_SOLID = -2
        /** Clears plants standing where a road goes (grass, flowers, snow, a low leaf). */
        const val REMOVE_PLANT = -3
        /** Turns road back into grass; a bridge plank over water becomes water again. */
        const val UNPAVE = -4
        const val FILL_FLAG = 1 shl 30
        /** Paving: only replaces natural ground or existing road, never something a player or builder placed. */
        const val PAVE_FLAG = 1 shl 29
        const val MAX_SPAN = 48
        const val MAX_HEIGHT = 40
        const val SCAN_PER_TICK = 6000
        val DIRT_ID = Block.getId(Blocks.DIRT.defaultBlockState())
        val GRASS_ID = Block.getId(Blocks.GRASS_BLOCK.defaultBlockState())
        val WALL_ID = Block.getId(Blocks.STONE_BRICK_WALL.defaultBlockState())
        val BRICK_ID = Block.getId(Blocks.STONE_BRICKS.defaultBlockState())
    }

    fun cost(nation: Nation, kind: BuildingKind, box: Box? = null): Map<Resource, Double> = when (kind) {
        BuildingKind.HOUSE, BuildingKind.TEMPLE, BuildingKind.OTHER -> Balance.config.houseCost
        BuildingKind.WALLS -> {
            val perimeter = box?.let { 2 * (it.sizeX + it.sizeZ) } ?: 64
            Balance.facilityCost(nation, com.grandstrategy.sim.Facility.WALLS).mapValues { it.value * max(1.0, perimeter / 48.0) }
        }
        else -> Balance.facilityCost(nation, kind.facility!!)
    }

    fun techMultiplier(nation: Nation, type: WorkType): Double = when (type) {
        WorkType.BUILD -> 1 + 0.1 * nation.level(TechBranch.CONSTRUCTION) + 0.05 * nation.level(TechBranch.INDUSTRY)
        else -> 1 + 0.1 * nation.level(TechBranch.MINING) + 0.05 * nation.level(TechBranch.INDUSTRY)
    }

    fun activeOrders(nation: Int) = state.orders.values.count { it.nation == nation && it.type != WorkType.ROAD && it.type != WorkType.UNROAD }

    fun buildingAt(x: Int, y: Int, z: Int): Building? = state.buildings.values.firstOrNull { it.box.contains(x, y, z) }

    fun overlaps(box: Box, ignore: Int? = null): Building? = state.buildings.values.firstOrNull { it.id != ignore && clashes(it, box) }

    /** Whether [box], grown by [margin], runs into [b]. Walls only count along their own line, so the town inside stays usable. */
    fun clashes(b: Building, box: Box, margin: Int = 1): Boolean {
        val o = b.box
        val hit = o.minX <= box.maxX + margin && o.maxX >= box.minX - margin && o.minZ <= box.maxZ + margin && o.maxZ >= box.minZ - margin
        if (!hit || b.kind != BuildingKind.WALLS) return hit
        val inside = box.minX - margin > o.minX && box.maxX + margin < o.maxX && box.minZ - margin > o.minZ && box.maxZ + margin < o.maxZ
        return !inside
    }

    fun territoryError(nation: Nation, box: Box): String? {
        for (c in box.chunks()) {
            if (state.owners[c] != nation.id) return "영토 밖이 포함돼 있다 ${Chunks.format(c)}"
        }
        return null
    }

    fun requestBuild(nation: Nation, template: TemplateInfo, rotation: Int, area: Box, by: String?, conscript: Boolean): Outcome {
        val size = templates.footprint(template, rotation)
        if (area.sizeX < size.x || area.sizeZ < size.z) return Outcome.fail("영역이 작다: ${template.name}는 ${size.x}×${size.z}가 필요하다 (선택 ${area.sizeX}×${area.sizeZ}).")
        val minX = area.minX + (area.sizeX - size.x) / 2
        val minZ = area.minZ + (area.sizeZ - size.z) / 2
        val footprint = Box(minX, 0, minZ, minX + size.x - 1, 0, minZ + size.z - 1)
        territoryError(nation, footprint)?.let { return Outcome.fail(it) }
        if (!BlockClass.loaded(level, footprint)) return Outcome.fail("그 땅이 아직 불러와지지 않았다.")
        if (BlockClass.waterRatio(level, footprint.minX, footprint.minZ, footprint.maxX, footprint.maxZ) > 0.3) return Outcome.fail("물 위에는 지을 수 없다.")
        val baseY = BlockClass.medianGround(level, footprint.minX, footprint.minZ, footprint.maxX, footprint.maxZ)
        val box = Box(footprint.minX, baseY, footprint.minZ, footprint.maxX, baseY + size.y - 1, footprint.maxZ)
        overlaps(box)?.let { return Outcome.fail("다른 건물(${it.kind.label} #${it.id})과 겹친다.") }
        if (!rt.roads.nearRoad(footprint)) return Outcome.fail("건물은 길 옆에만 지을 수 있다 (${com.grandstrategy.sim.RoadLots.REACH}칸 안에 길이 있어야 한다).")
        return place(nation, template.kind, template.id, rotation, box, baseY, by, conscript)
    }

    fun requestWalls(nation: Nation, area: Box, by: String?, conscript: Boolean): Outcome {
        territoryError(nation, area)?.let { return Outcome.fail(it) }
        if (!BlockClass.loaded(level, area)) return Outcome.fail("그 땅이 아직 불러와지지 않았다.")
        val baseY = BlockClass.medianGround(level, area.minX, area.minZ, area.maxX, area.maxZ)
        val box = Box(area.minX, baseY, area.minZ, area.maxX, baseY + 3, area.maxZ)
        return place(nation, BuildingKind.WALLS, null, 0, box, baseY, by, conscript)
    }

    private fun place(nation: Nation, kind: BuildingKind, template: String?, rotation: Int, box: Box, baseY: Int, by: String?, conscript: Boolean): Outcome {
        if (kind.facility != null && nation.totalFacilities() + pendingFacilities(nation.id) >= nation.claims.size) {
            return Outcome.fail("시설 수는 영토 수(${nation.claims.size})를 넘을 수 없다.")
        }
        val price = cost(nation, kind, box)
        if (!Actions.canAfford(nation, price)) return Outcome.fail("자원이 부족하다: ${Actions.describe(price)}")
        Actions.pay(nation, price)
        // Walls leave gates where streets cross them; anything else built on a street takes the street's place.
        if (kind != BuildingKind.WALLS) rt.roads.builtOver(box)
        val building = Building(state.nextBuildingId++, nation.id, kind, box, template)
        state.buildings[building.id] = building
        val order = WorkOrder(
            id = state.nextOrderId++, nation = nation.id, type = WorkType.BUILD, box = box, kind = kind, template = template,
            rotation = rotation, baseY = baseY, conscript = conscript, building = building.id, by = by, createdTurn = state.turn,
            workersWanted = if (conscript) 16 else 8
        )
        state.orders[order.id] = order
        return Outcome.ok("${kind.label} 건설을 지시했다 (#${order.id}, ${box.sizeX}×${box.sizeZ}). 비용 ${Actions.describe(price)}")
    }

    private fun pendingFacilities(nation: Int) = state.orders.values.count { it.nation == nation && it.type == WorkType.BUILD && it.kind?.facility != null }

    fun requestDemolish(nation: Nation, area: Box, by: String?, conscript: Boolean): Outcome {
        val box = clampHeight(area)
        territoryError(nation, box)?.let { return Outcome.fail(it) }
        val order = WorkOrder(id = state.nextOrderId++, nation = nation.id, type = WorkType.DEMOLISH, box = box, by = by, conscript = conscript,
            createdTurn = state.turn, workersWanted = if (conscript) 16 else 8)
        state.orders[order.id] = order
        return Outcome.ok("철거를 지시했다 (#${order.id}, ${box.sizeX}×${box.sizeY}×${box.sizeZ}).")
    }

    fun requestDemolishBuilding(nation: Nation, building: Building, by: String?, conscript: Boolean): Outcome {
        if (building.nation != nation.id && territoryError(nation, building.box) != null) return Outcome.fail("우리 건물이 아니다.")
        if (state.orders.values.any { it.building == building.id }) return Outcome.fail("이미 작업 중인 건물이다.")
        val order = WorkOrder(id = state.nextOrderId++, nation = nation.id, type = WorkType.DEMOLISH, box = building.box, building = building.id,
            by = by, conscript = conscript, createdTurn = state.turn, workersWanted = if (conscript) 16 else 8)
        state.orders[order.id] = order
        return Outcome.ok("${building.kind.label} #${building.id} 철거를 지시했다.")
    }

    fun requestFlatten(nation: Nation, area: Box, by: String?, conscript: Boolean): Outcome {
        territoryError(nation, area)?.let { return Outcome.fail(it) }
        if (!BlockClass.loaded(level, area)) return Outcome.fail("그 땅이 아직 불러와지지 않았다.")
        overlaps(Box(area.minX, -64, area.minZ, area.maxX, 320, area.maxZ))?.let { return Outcome.fail("건물(${it.kind.label} #${it.id})이 있다. 먼저 철거한다.") }
        val target = BlockClass.medianGround(level, area.minX, area.minZ, area.maxX, area.maxZ)
        val box = Box(area.minX, target - 8, area.minZ, area.maxX, minOf(target + 32, level.maxY), area.maxZ)
        val order = WorkOrder(id = state.nextOrderId++, nation = nation.id, type = WorkType.FLATTEN, box = box, baseY = target, by = by,
            conscript = conscript, createdTurn = state.turn, workersWanted = if (conscript) 16 else 8)
        state.orders[order.id] = order
        return Outcome.ok("평탄화를 지시했다 (#${order.id}, 높이 $target 기준).")
    }

    fun cancel(order: WorkOrder, keepRoad: Boolean = false) {
        state.orders.remove(order.id)
        plans.remove(order.id)
        credit.remove(order.id)
        releaseCrew(order.id)
        if (order.type == WorkType.BUILD) {
            order.building?.let { id -> state.buildings[id]?.takeIf { !it.complete }?.let { state.buildings.remove(id) } }
        }
        if (order.type == WorkType.ROAD && !keepRoad) {
            order.road?.let { id -> state.roads[id]?.takeIf { !it.built }?.let { rt.roads.forget(it) } }
        }
    }

    fun orderRoad(nation: Nation, road: Road, by: String?, conscript: Boolean): WorkOrder {
        var minY = Int.MAX_VALUE
        var maxY = Int.MIN_VALUE
        for (c in road.cells) {
            val x = Chunks.x(c)
            val z = Chunks.z(c)
            if (!level.chunkSource.hasChunk(x shr 4, z shr 4)) continue
            val y = RoadBlocks.groundY(level, x, z)
            minY = minOf(minY, y)
            maxY = maxOf(maxY, y)
        }
        if (minY > maxY) {
            minY = level.seaLevel
            maxY = level.seaLevel
        }
        val box = Box(road.cells.minOf { Chunks.x(it) }, minY, road.cells.minOf { Chunks.z(it) }, road.cells.maxOf { Chunks.x(it) }, maxY + 2, road.cells.maxOf { Chunks.z(it) })
        val order = WorkOrder(
            id = state.nextOrderId++, nation = nation.id, type = WorkType.ROAD, box = box, by = by, conscript = conscript,
            createdTurn = state.turn, workersWanted = if (conscript) 8 else Balance.config.roads.workers, road = road.id
        )
        state.orders[order.id] = order
        return order
    }

    fun orderUnroad(nation: Nation, box: Box, cells: List<Long>, by: String?, conscript: Boolean): WorkOrder {
        val order = WorkOrder(
            id = state.nextOrderId++, nation = nation.id, type = WorkType.UNROAD, box = box, by = by, conscript = conscript,
            createdTurn = state.turn, workersWanted = if (conscript) 8 else Balance.config.roads.workers, cells = cells
        )
        state.orders[order.id] = order
        return order
    }

    fun registerDetected(box: Box, nation: Int?): Building {
        state.buildings.values.firstOrNull { it.box == box }?.let { return it }
        val b = Building(state.nextBuildingId++, nation, BuildingKind.OTHER, box, complete = true, detected = true)
        state.buildings[b.id] = b
        return b
    }

    fun assign(building: Building, kind: BuildingKind): Outcome {
        val nation = state.nation(building.nation) ?: return Outcome.fail("주인 없는 건물이다.")
        if (!building.complete) return Outcome.fail("아직 짓는 중이다.")
        if (kind.facility != null && building.kind.facility == null && nation.totalFacilities() >= nation.claims.size) {
            return Outcome.fail("시설 수는 영토 수(${nation.claims.size})를 넘을 수 없다.")
        }
        building.kind.facility?.let { f -> nation.facilities[f] = (nation.count(f) - 1).coerceAtLeast(0) }
        building.kind = kind
        kind.facility?.let { f -> nation.facilities.merge(f, 1, Int::plus) }
        return Outcome.ok("건물 #${building.id}을(를) ${kind.label}(으)로 지정했다.")
    }

    fun autoBuild(nation: Nation, kind: BuildingKind): Outcome {
        if (activeOrders(nation.id) >= 2 + nation.claims.size / 20) return Outcome.fail("진행 중인 공사가 많다.")
        if (kind == BuildingKind.WALLS) {
            val c = nation.capital
            val box = Box(Chunks.x(c) * 16 - 16, 0, Chunks.z(c) * 16 - 16, Chunks.x(c) * 16 + 31, 3, Chunks.z(c) * 16 + 31)
            if (territoryError(nation, box) != null) return Outcome.fail("성벽을 두를 땅이 부족하다.")
            if (state.buildings.values.any { it.nation == nation.id && it.kind == BuildingKind.WALLS }) return Outcome.fail("이미 성벽이 있다.")
            return place(nation, BuildingKind.WALLS, null, 0, box, Int.MIN_VALUE, null, false)
        }
        // Houses go only on lots beside the town's streets; when the streets are full the road planner opens a new one.
        val style = templates.styleAt(level, Chunks.x(nation.capital) * 16 + 8, Chunks.z(nation.capital) * 16 + 8)
        val options = templates.byKind(kind, style).filter { maxOf(it.sizeX, it.sizeZ) <= 15 }
        if (options.isEmpty()) return Outcome.fail("${kind.label} 템플릿이 없다.")
        val picks = listOf(options[rt.sim.rng.nextInt(options.size)], options.minBy { it.sizeX * it.sizeZ }).distinct()
        for (info in picks) {
            val rotation = rt.sim.rng.nextInt(4)
            val size = templates.footprint(info, rotation)
            val lot = rt.roads.findLot(nation, size.x, size.z) ?: continue
            return place(nation, kind, info.id, rotation, Box(lot.minX, 0, lot.minZ, lot.maxX, size.y - 1, lot.maxZ), Int.MIN_VALUE, null, false)
        }
        rt.roads.wantFrontage(nation.id)
        return Outcome.fail("길가에 빈 땅이 없다. 새 길을 낸다.")
    }

    private fun clampHeight(area: Box): Box {
        val minY = area.minY.coerceAtLeast(level.minY)
        return Box(area.minX, minY, area.minZ, area.maxX, minOf(area.maxY, minY + MAX_HEIGHT * 2, level.maxY), area.maxZ)
    }

    fun tick() {
        ticks++
        if (state.orders.isEmpty()) return
        if (ticks % 20 == 0L) for (order in state.orders.values.toList()) staff(order)
        var budget = Balance.config.workBlocksPerTick
        for (order in state.orders.values.toList()) {
            if (budget <= 0) break
            val nation = state.nation(order.nation)
            if (nation == null) {
                cancel(order)
                continue
            }
            val rate = workers(order, nation) * Balance.config.workRatePerWorker * techMultiplier(nation, order.type) / 20.0
            val loaded = BlockClass.loaded(level, order.box)
            if (!loaded) {
                order.abstractWork += rate
                if (order.abstractWork >= estimate(order)) order.pendingPlacement = true
                continue
            }
            val plan = plans.getOrPut(order.id) { plan(order) }
            if (order.total == 0L) order.total = plan.pos.size.toLong()
            plan.cursor = maxOf(plan.cursor, order.progress.toInt()).coerceAtMost(plan.pos.size)
            var allowance = if (order.pendingPlacement) 4000.0 else credit.getOrDefault(order.id, 0.0) + rate
            var scanned = 0
            var changed = 0
            while (plan.cursor < plan.pos.size && allowance >= 1 && scanned < SCAN_PER_TICK && budget > 0) {
                val pos = BlockPos.of(plan.pos[plan.cursor])
                val op = plan.op[plan.cursor]
                plan.cursor++
                scanned++
                if (apply(pos, op)) {
                    allowance -= 1
                    changed++
                    budget--
                }
            }
            credit[order.id] = if (order.pendingPlacement) 0.0 else allowance.coerceAtMost(rate * 40 + 1)
            order.progress = plan.cursor.toLong()
            if (plan.cursor >= plan.pos.size) complete(order, nation)
        }
    }

    private fun apply(pos: BlockPos, op: Int): Boolean {
        val current = level.getBlockState(pos)
        return when (op) {
            REMOVE_CONSTRUCTED -> {
                if (!BlockClass.constructed(current) && !BlockClass.vegetation(current)) false
                else level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2)
            }
            REMOVE_SOLID -> {
                if (current.isAir || !current.fluidState.isEmpty) false
                else level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2)
            }
            REMOVE_PLANT -> {
                if (current.isAir || !BlockClass.vegetation(current) || current.`is`(net.minecraft.tags.BlockTags.LOGS)) false
                else level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2)
            }
            UNPAVE -> {
                when {
                    RoadBlocks.road(current) -> level.setBlock(pos, Blocks.GRASS_BLOCK.defaultBlockState(), 2)
                    RoadBlocks.bridge(current) && !level.getBlockState(pos.below()).fluidState.isEmpty -> level.setBlock(pos, Blocks.WATER.defaultBlockState(), 2)
                    else -> false
                }
            }
            else -> {
                if (op and PAVE_FLAG != 0) {
                    val natural = BlockClass.terrain(current) || RoadBlocks.road(current) ||
                        (BlockClass.vegetation(current) && !current.`is`(net.minecraft.tags.BlockTags.LEAVES))
                    if (!natural) return false
                    val next = Block.stateById(op and PAVE_FLAG.inv())
                    return current != next && level.setBlock(pos, next, 2 or 16)
                }
                if (op and FILL_FLAG != 0) {
                    if (current.isSolid && !BlockClass.vegetation(current)) return false
                    return level.setBlock(pos, Block.stateById(op and FILL_FLAG.inv()), 2 or 16)
                }
                val next = Block.stateById(op)
                if (current == next) false else level.setBlock(pos, next, 2 or 16)
            }
        }
    }

    private fun estimate(order: WorkOrder): Double = when (order.type) {
        WorkType.BUILD -> order.box.volume * 0.5
        WorkType.DEMOLISH -> order.box.volume * 0.2
        WorkType.FLATTEN -> order.box.sizeX * order.box.sizeZ * 3.0
        WorkType.ROAD -> (state.roads[order.road]?.cells?.size ?: 0) * 2.0
        WorkType.UNROAD -> order.box.sizeX * order.box.sizeZ * 1.0
    }

    private fun plan(order: WorkOrder): Plan {
        val pos = ArrayList<Long>()
        val ops = IntArrayList()
        fun add(x: Int, y: Int, z: Int, op: Int) {
            pos += BlockPos.asLong(x, y, z)
            ops.add(op)
        }
        val b = order.box
        when (order.type) {
            WorkType.BUILD -> {
                val baseY = if (order.baseY == Int.MIN_VALUE) BlockClass.medianGround(level, b.minX, b.minZ, b.maxX, b.maxZ) else order.baseY
                for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                    val ground = BlockClass.ground(level, x, z)
                    for (y in ground until baseY) add(x, y, z, (if (y == baseY - 1) GRASS_ID else DIRT_ID) or FILL_FLAG)
                }
                if (order.kind == BuildingKind.WALLS) {
                    for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                        if (x != b.minX && x != b.maxX && z != b.minZ && z != b.maxZ) continue
                        if (rt.roads.network.isRoad(Chunks.pack(x, z))) continue
                        add(x, baseY, z, BRICK_ID)
                        for (y in baseY + 1..baseY + 3) add(x, y, z, if (y == baseY + 3) WALL_ID else BRICK_ID)
                    }
                    state.buildings[order.building]?.box = Box(b.minX, baseY, b.minZ, b.maxX, baseY + 3, b.maxZ)
                } else {
                    val info = templates.all.firstOrNull { it.id == order.template }
                    if (info != null) {
                        val blocks = templates.blocks(level, info, order.rotation, b.minX, baseY, b.minZ)
                        for (p in blocks) add(p.pos.x, p.pos.y, p.pos.z, Block.getId(p.state))
                        if (blocks.isNotEmpty()) {
                            state.buildings[order.building]?.box = Box(
                                blocks.minOf { it.pos.x }, baseY, blocks.minOf { it.pos.z },
                                blocks.maxOf { it.pos.x }, blocks.maxOf { it.pos.y }, blocks.maxOf { it.pos.z }
                            )
                        }
                    }
                }
            }
            WorkType.DEMOLISH -> {
                for (y in b.maxY downTo b.minY) for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) add(x, y, z, REMOVE_CONSTRUCTED)
            }
            WorkType.FLATTEN -> {
                val target = order.baseY
                for (y in b.maxY downTo target) for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) add(x, y, z, REMOVE_SOLID)
                for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                    for (y in b.minY until target) add(x, y, z, (if (y == target - 1) GRASS_ID else DIRT_ID) or FILL_FLAG)
                }
            }
            WorkType.ROAD -> {
                val road = state.roads[order.road]
                if (road != null) {
                    val tier = RoadBlocks.tier(state.nation(order.nation))
                    // Walk the cells in order so the road visibly grows from one end.
                    for (c in road.cells) {
                        val x = Chunks.x(c)
                        val z = Chunks.z(c)
                        val y = RoadBlocks.groundY(level, x, z)
                        add(x, y + 1, z, REMOVE_PLANT)
                        add(x, y + 2, z, REMOVE_PLANT)
                        val surface = level.getBlockState(BlockPos(x, y, z))
                        val block = if (!surface.fluidState.isEmpty) RoadBlocks.bridge(tier) else RoadBlocks.surface(tier, road.kind, x, z)
                        add(x, y, z, Block.getId(block) or PAVE_FLAG)
                    }
                }
            }
            WorkType.UNROAD -> {
                val cells = order.cells.toHashSet()
                for (x in b.minX..b.maxX) for (z in b.minZ..b.maxZ) {
                    val y = RoadBlocks.groundY(level, x, z)
                    val top = level.getBlockState(BlockPos(x, y, z))
                    if (Chunks.pack(x, z) in cells || top.`is`(Blocks.DIRT_PATH)) add(x, y, z, UNPAVE)
                }
            }
        }
        return Plan(pos.toLongArray(), ops.toIntArray())
    }

    private fun complete(order: WorkOrder, nation: Nation) {
        state.orders.remove(order.id)
        plans.remove(order.id)
        credit.remove(order.id)
        releaseCrew(order.id)
        when (order.type) {
            WorkType.BUILD -> {
                val building = state.buildings[order.building] ?: return
                building.complete = true
                building.kind.facility?.let { nation.facilities.merge(it, 1, Int::plus) }
                rt.sim.log(EventKind.ECONOMY, "${nation.name}: ${building.kind.label} 완공 (#${building.id})", nation.id)
            }
            WorkType.DEMOLISH -> {
                val building = order.building?.let { state.buildings.remove(it) }
                if (building != null && building.complete) {
                    val owner = state.nation(building.nation)
                    building.kind.facility?.let { f -> owner?.let { it.facilities[f] = (it.count(f) - 1).coerceAtLeast(0) } }
                }
                rt.sim.log(EventKind.ECONOMY, "${nation.name}: 철거 완료 (#${order.id})", nation.id)
            }
            WorkType.FLATTEN -> rt.sim.log(EventKind.ECONOMY, "${nation.name}: 평탄화 완료 (#${order.id})", nation.id)
            WorkType.ROAD -> state.roads[order.road]?.let { road ->
                road.built = true
                if (order.by != null) rt.sim.log(EventKind.ECONOMY, "${nation.name}: ${road.kind.label} 완공 (${road.cells.size}칸)", nation.id)
            }
            WorkType.UNROAD -> rt.sim.log(EventKind.ECONOMY, "${nation.name}: 길 걷어내기 완료 (#${order.id})", nation.id)
        }
        // Redraw the world map where the work happened so new houses and streets show up without waiting for an unload.
        val chunks = order.box.chunks()
        if (chunks.size <= 64) for (c in chunks) level.chunkSource.getChunkNow(Chunks.x(c), Chunks.z(c))?.let { rt.map.onChunk(level, it) }
    }

    private fun releaseCrew(order: Int) {
        virtualCrew.remove(order)
        val crew = crews.remove(order) ?: return
        val agents = rt.population.agents
        for (k in 0 until crew.size) {
            val i = crew.getInt(k)
            if (i < agents.high && agents.alive[i] && agents.task[i] == order) agents.releaseTask(i)
        }
    }

    private fun staff(order: WorkOrder) {
        val agents = rt.population.agents
        val crew = crews.getOrPut(order.id) { IntArrayList() }
        crew.removeIf { i -> i >= agents.high || !agents.alive[i] || agents.task[i] != order.id }
        val need = order.workersWanted - crew.size
        if (need <= 0) return
        val cx = order.box.centerX
        val cz = order.box.centerZ
        val picks = ArrayList<Pair<Int, Double>>()
        agents.forEachAlive { i ->
            if (agents.nation[i] != order.nation || agents.task[i] != 0) return@forEachAlive
            val job = Job.entries[agents.job[i].toInt()]
            val eligible = job == Job.BUILDER || (order.conscript && job != Job.SOLDIER && job != Job.CHILD)
            if (!eligible) return@forEachAlive
            val dx = agents.x[i] - cx
            val dz = agents.z[i] - cz
            picks += i to (dx * dx + dz * dz)
        }
        if (picks.isEmpty() && crew.isEmpty) {
            virtualCrew[order.id] = minOf(order.workersWanted, 2)
            return
        }
        virtualCrew.remove(order.id)
        picks.sortBy { it.second }
        val rng = rt.sim.rng
        val roadCells = state.roads[order.road]?.cells
        for ((i, _) in picks.take(need)) {
            if (!roadCells.isNullOrEmpty()) {
                // Road crews spread out along the route instead of crowding the bounding box edges.
                val c = roadCells[rng.nextInt(roadCells.size)]
                agents.assignTask(i, order.id, Chunks.x(c) + 0.5f, Chunks.z(c) + 0.5f)
                crew.add(i)
                continue
            }
            val side = rng.nextInt(4)
            val b = order.box
            val sx = when (side) { 0 -> b.minX - 1.5f; 1 -> b.maxX + 1.5f; else -> b.minX + rng.nextFloat() * b.sizeX }
            val sz = when (side) { 2 -> b.minZ - 1.5f; 3 -> b.maxZ + 1.5f; else -> b.minZ + rng.nextFloat() * b.sizeZ }
            agents.assignTask(i, order.id, sx.toFloat(), sz.toFloat())
            crew.add(i)
        }
    }

    fun workers(order: WorkOrder, nation: Nation): Double {
        val agents = rt.population.agents
        val crew = crews[order.id]
        if (crew == null || crew.isEmpty) return virtualCrew[order.id]?.toDouble() ?: 0.0
        val reach = maxOf(order.box.sizeX, order.box.sizeZ) / 2.0 + 5.0
        var present = 0
        for (k in 0 until crew.size) {
            val i = crew.getInt(k)
            if (i >= agents.high || !agents.alive[i]) continue
            val dx = agents.x[i] - order.box.centerX
            val dz = agents.z[i] - order.box.centerZ
            if (sqrt(dx * dx + dz * dz) <= reach) present++
        }
        return present.toDouble()
    }

    fun crewSize(order: Int): Int = crews[order]?.size ?: 0
}
