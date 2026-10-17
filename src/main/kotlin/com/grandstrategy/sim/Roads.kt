package com.grandstrategy.sim

import java.util.PriorityQueue
import kotlin.math.abs

/** What road planning needs to know about the ground; kept abstract so planning is testable without a world. */
interface RoadGround {
    /** Surface height of a block column, or null when unknown (chunk not loaded). Unknown columns are never crossed. */
    fun height(x: Int, z: Int): Int?

    /**
     * Extra cost of paving this column: 0 for open ground, more for water (bridge), crops or rough surfaces,
     * [Double.POSITIVE_INFINITY] where a road may not go, and a negative value for an existing path worth following.
     */
    fun cost(x: Int, z: Int): Double

    /** False where the height is only a guess (land nobody has loaded yet); slope limits are not applied there. */
    fun exact(x: Int, z: Int): Boolean = true
}

/** Index over every road cell so planners and the work tools can ask "is this column road, and whose" in O(1). */
class RoadNetwork(private val state: WorldState) {
    private val index = HashMap<Long, Int>()

    init {
        rebuild()
    }

    fun rebuild() {
        index.clear()
        for (road in state.roads.values) for (c in road.cells) index[c] = road.id
    }

    fun roadAt(cell: Long): Road? = index[cell]?.let { state.roads[it] }

    fun isRoad(cell: Long): Boolean = cell in index

    fun nationAt(cell: Long): Int? = roadAt(cell)?.nation

    fun cellsOf(nation: Int): List<Long> = state.roads.values.filter { it.nation == nation }.flatMap { it.cells }

    fun hasNetwork(nation: Int): Boolean = state.roads.values.any { it.nation == nation && it.cells.isNotEmpty() }

    val cellCount: Int get() = index.size

    /** Registers a road over [cells]; columns that already carry road stay with their road. Null when nothing new is left. */
    fun add(nation: Int, kind: RoadKind, cells: Collection<Long>): Road? {
        val fresh = LinkedHashSet<Long>()
        for (c in cells) if (c !in index) fresh += c
        if (fresh.isEmpty()) return null
        val road = Road(state.nextRoadId++, nation, kind, fresh.toMutableList())
        state.roads[road.id] = road
        for (c in fresh) index[c] = road.id
        return road
    }

    /** Takes road out of a rectangle. Returns the removed cells; roads left without cells are dropped. */
    fun clear(minX: Int, minZ: Int, maxX: Int, maxZ: Int, ban: Boolean): List<Long> {
        val removed = ArrayList<Long>()
        val touched = HashSet<Int>()
        for (x in minX..maxX) for (z in minZ..maxZ) {
            val cell = Chunks.pack(x, z)
            val id = index.remove(cell)
            if (id != null) {
                removed += cell
                touched += id
            }
            if (ban) state.roadBans += cell
        }
        for (id in touched) {
            val road = state.roads[id] ?: continue
            road.cells.removeAll { it !in index || index[it] != id }
            if (road.cells.isEmpty()) state.roads.remove(id)
        }
        return removed
    }

    fun anyIn(minX: Int, minZ: Int, maxX: Int, maxZ: Int): Boolean {
        for (x in minX..maxX) for (z in minZ..maxZ) if (Chunks.pack(x, z) in index) return true
        return false
    }

    fun drop(road: Road) {
        state.roads.remove(road.id)
        for (c in road.cells) if (index[c] == road.id) index.remove(c)
    }
}

/**
 * Street-shaped A*: four-way moves over block columns with a turn penalty, so roads come out as straight runs with
 * a few corners (like village streets) instead of diagonal staircases. Existing roads are cheap to walk along, which
 * makes new streets join the network early and grow it into a tree around the plaza, the way WorldBox towns do.
 */
object RoadPathfinder {
    const val STREET_COST = 0.35
    const val TURN_COST = 0.8
    const val STEP_COST = 0.9
    const val MAX_STEP = 1

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DZ = intArrayOf(0, 0, 1, -1)

    fun find(
        ground: RoadGround,
        sx: Int, sz: Int,
        tx: Int, tz: Int,
        goal: (Long) -> Boolean,
        existing: (Long) -> Boolean,
        blocked: (Int, Int) -> Boolean,
        range: Int,
        maxNodes: Int,
        greed: Double = 1.0
    ): List<Long>? {
        val startH = ground.height(sx, sz) ?: return null
        val start = Chunks.pack(sx, sz)
        val cost = HashMap<Long, Double>()
        val parent = HashMap<Long, Long>()
        val dirOf = HashMap<Long, Int>()
        val heightOf = HashMap<Long, Int>()
        val open = PriorityQueue<DoubleArray>(compareBy { it[0] })
        fun h(x: Int, z: Int) = (abs(x - tx) + abs(z - tz)) * greed
        cost[start] = 0.0
        heightOf[start] = startH
        dirOf[start] = -1
        open.add(doubleArrayOf(h(sx, sz), sx.toDouble(), sz.toDouble()))
        var expanded = 0
        val closed = HashSet<Long>()
        while (open.isNotEmpty() && expanded < maxNodes) {
            val top = open.poll()
            val x = top[1].toInt()
            val z = top[2].toInt()
            val key = Chunks.pack(x, z)
            if (!closed.add(key)) continue
            if (key != start && goal(key)) return trace(parent, start, key)
            expanded++
            val base = cost[key] ?: continue
            val y = heightOf[key] ?: continue
            val dir = dirOf[key] ?: -1
            for (d in 0 until 4) {
                val x2 = x + DX[d]
                val z2 = z + DZ[d]
                if (abs(x2 - sx) > range || abs(z2 - sz) > range) continue
                val next = Chunks.pack(x2, z2)
                if (next in closed) continue
                val isGoal = goal(next)
                if (!isGoal && blocked(x2, z2)) continue
                val y2 = ground.height(x2, z2) ?: continue
                val measured = ground.exact(x, z) && ground.exact(x2, z2)
                val dy = if (measured) abs(y2 - y) else 0
                if (dy > MAX_STEP && !isGoal) continue
                val surface = ground.cost(x2, z2)
                if (surface == Double.POSITIVE_INFINITY && !isGoal) continue
                var step = when {
                    existing(next) -> STREET_COST
                    surface < 0 -> 0.6
                    else -> 1.0 + surface
                }
                if (dy > 0) step += STEP_COST * dy
                if (dir >= 0 && dir != d) step += TURN_COST
                val c = base + step
                if (c < (cost[next] ?: Double.MAX_VALUE)) {
                    cost[next] = c
                    parent[next] = key
                    dirOf[next] = d
                    heightOf[next] = y2
                    open.add(doubleArrayOf(c + h(x2, z2), x2.toDouble(), z2.toDouble()))
                }
            }
        }
        return null
    }

    private fun trace(parent: Map<Long, Long>, start: Long, end: Long): List<Long> {
        val out = ArrayList<Long>()
        var cur = end
        while (cur != start) {
            out += cur
            cur = parent[cur] ?: break
        }
        out += start
        out.reverse()
        return out
    }

    /**
     * Widens a path to [width] columns by adding neighbours on one side of the direction of travel.
     * Columns that are blocked, unknown or more than one block off the path's height are skipped.
     */
    fun widen(path: List<Long>, width: Int, ground: RoadGround, blocked: (Int, Int) -> Boolean): List<Long> {
        if (width <= 1 || path.size < 2) return path
        val out = LinkedHashSet<Long>(path)
        for (k in path.indices) {
            val a = path[maxOf(0, k - 1)]
            val b = path[minOf(path.size - 1, k + 1)]
            val alongX = Chunks.x(a) != Chunks.x(b)
            val x = Chunks.x(path[k])
            val z = Chunks.z(path[k])
            val y = ground.height(x, z) ?: continue
            for (w in 1 until width) {
                val nx = if (alongX) x else x + w
                val nz = if (alongX) z + w else z
                if (blocked(nx, nz)) continue
                val ny = ground.height(nx, nz) ?: continue
                if ((ground.exact(nx, nz) && abs(ny - y) > 1) || ground.cost(nx, nz) == Double.POSITIVE_INFINITY) continue
                out += Chunks.pack(nx, nz)
            }
        }
        return out.toList()
    }

    /** A rounded square of radius [r] around the hub, keeping columns within one block of the hub's height. */
    fun plaza(cx: Int, cz: Int, r: Int, ground: RoadGround, blocked: (Int, Int) -> Boolean): List<Long> {
        val y = ground.height(cx, cz) ?: return emptyList()
        val out = ArrayList<Long>()
        for (dx in -r..r) for (dz in -r..r) {
            if (abs(dx) == r && abs(dz) == r) continue
            val x = cx + dx
            val z = cz + dz
            if (blocked(x, z)) continue
            val h = ground.height(x, z) ?: continue
            if ((ground.exact(x, z) && abs(h - y) > 1) || ground.cost(x, z) == Double.POSITIVE_INFINITY) continue
            out += Chunks.pack(x, z)
        }
        return out
    }
}

/**
 * Building lots along streets. A lot sits beside a road cell with a one-block yard between the road and the wall,
 * centred on the cell, on any of the four sides. Cells nearer the plaza are tried first (with a little jitter) so towns
 * fill in from the centre outward instead of sprawling.
 */
object RoadLots {
    const val YARD = 1
    /** How far (in blocks, from the footprint's edge) a road may be for a building to count as on the road. */
    const val REACH = 3

    fun candidates(cell: Long, sizeX: Int, sizeZ: Int): List<Box> {
        val x = Chunks.x(cell)
        val z = Chunks.z(cell)
        val ox = x - sizeX / 2
        val oz = z - sizeZ / 2
        val gap = YARD + 1
        return listOf(
            Box(x + gap, 0, oz, x + gap + sizeX - 1, 0, oz + sizeZ - 1),
            Box(x - gap - sizeX + 1, 0, oz, x - gap, 0, oz + sizeZ - 1),
            Box(ox, 0, z + gap, ox + sizeX - 1, 0, z + gap + sizeZ - 1),
            Box(ox, 0, z - gap - sizeZ + 1, ox + sizeX - 1, 0, z - gap)
        )
    }

    fun find(
        cells: List<Long>,
        hubX: Int, hubZ: Int,
        sizeX: Int, sizeZ: Int,
        rng: kotlin.random.Random,
        valid: (Box) -> Boolean,
        tries: Int = 240
    ): Box? {
        if (cells.isEmpty()) return null
        val ordered = cells.map { c ->
            val dx = (Chunks.x(c) - hubX).toDouble()
            val dz = (Chunks.z(c) - hubZ).toDouble()
            c to kotlin.math.sqrt(dx * dx + dz * dz) + rng.nextDouble() * 10.0
        }.sortedBy { it.second }
        var checked = 0
        for ((cell, _) in ordered) {
            for (box in candidates(cell, sizeX, sizeZ).shuffled(rng)) {
                if (valid(box)) return box
            }
            if (++checked >= tries) break
        }
        return null
    }

    /** True when some cell accepted by [isRoad] lies within [reach] blocks of the box's outline. */
    fun near(box: Box, reach: Int, isRoad: (Int, Int) -> Boolean): Boolean {
        for (x in box.minX - reach..box.maxX + reach) for (z in box.minZ - reach..box.maxZ + reach) {
            if (x in box.minX..box.maxX && z in box.minZ..box.maxZ) continue
            if (isRoad(x, z)) return true
        }
        return false
    }
}
