package com.grandstrategy.mc.agents

import com.grandstrategy.sim.AgentPopulation
import com.grandstrategy.sim.Agents
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BlockTags
import net.minecraft.world.level.levelgen.Heightmap
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

class Walker(private val population: AgentPopulation, private val level: () -> ServerLevel) {
    private val agents: Agents get() = population.agents
    private val paths = HashMap<Int, Path>()
    private val pending = ArrayDeque<Int>()
    private val queued = IntOpenHashSet()
    val yaw = HashMap<Int, Float>()
    var searches = 0L
        private set
    var searchNanos = 0L
        private set

    private class Path(val xs: FloatArray, val ys: FloatArray, val zs: FloatArray, val goalX: Float, val goalZ: Float) {
        var index = 0
    }

    init {
        agents.onTargetChanged { i ->
            if (i < agents.high && agents.guided[i]) request(i)
        }
        agents.onRemoved { i -> forget(i) }
    }

    fun forget(i: Int) {
        paths.remove(i)
        queued.remove(i)
        yaw.remove(i)
        if (i < agents.capacity) agents.guided[i] = false
    }

    private fun request(i: Int) {
        paths.remove(i)
        if (queued.add(i)) pending.addLast(i)
    }

    fun take(i: Int): Boolean {
        val world = level()
        val cell = snap(world, agents.x[i], agents.y[i], agents.z[i]) ?: return false
        agents.x[i] = cell.x + 0.5f
        agents.z[i] = cell.z + 0.5f
        agents.y[i] = cell.y.toFloat()
        agents.guided[i] = true
        if (agents.moving(i)) request(i)
        return true
    }

    fun release(i: Int) {
        forget(i)
    }

    fun tick(hot: IntOpenHashSet, dt: Float) {
        val world = level()
        var budget = SEARCHES_PER_TICK
        while (budget > 0 && pending.isNotEmpty()) {
            val i = pending.removeFirst()
            queued.remove(i)
            if (i >= agents.high || !agents.alive[i] || !agents.guided[i] || !agents.moving(i)) continue
            val start = System.nanoTime()
            search(world, i)?.let { paths[i] = it }
            searchNanos += System.nanoTime() - start
            searches++
            budget--
        }
        val iter = hot.iterator()
        while (iter.hasNext()) {
            val i = iter.nextInt()
            if (!agents.guided[i] || !agents.moving(i)) continue
            val path = paths[i] ?: continue
            advance(i, path, dt)
        }
    }

    private fun advance(i: Int, path: Path, dt: Float) {
        var remaining = agents.speed[i] * dt
        while (remaining > 0f && path.index < path.xs.size) {
            val tx = path.xs[path.index]
            val ty = path.ys[path.index]
            val tz = path.zs[path.index]
            val dx = tx - agents.x[i]
            val dz = tz - agents.z[i]
            val horizontal = sqrt(dx * dx + dz * dz)
            if (horizontal <= remaining) {
                agents.x[i] = tx
                agents.z[i] = tz
                agents.y[i] = ty
                remaining -= horizontal
                path.index++
            } else {
                val f = remaining / horizontal
                agents.x[i] += dx * f
                agents.z[i] += dz * f
                agents.y[i] += (ty - agents.y[i]) * minOf(1f, f * 2f)
                remaining = 0f
            }
            if (horizontal > 0.01f) yaw[i] = (Math.toDegrees(atan2(dz.toDouble(), dx.toDouble())) - 90.0).toFloat()
        }
        if (path.index >= path.xs.size) {
            paths.remove(i)
            agents.arrive(i, population.sim.rng)
        }
    }

    private fun search(world: ServerLevel, i: Int): Path? {
        val startCell = BlockPos(floor(agents.x[i]).toInt(), floor(agents.y[i]).toInt(), floor(agents.z[i]).toInt())
        val tx = agents.targetX[i]
        val tz = agents.targetZ[i]
        val goalTop = groundEstimate(world, floor(tx).toInt(), floor(tz).toInt())
        val gx = floor(tx).toInt()
        val gz = floor(tz).toInt()
        val gy = if (abs(goalTop - startCell.y) > 12) startCell.y else goalTop
        val cache = Long2ByteOpenHashMap()
        fun stand(x: Int, y: Int, z: Int): Boolean {
            val key = BlockPos.asLong(x, y, z)
            val known = cache.getOrDefault(key, 2.toByte())
            if (known != 2.toByte()) return known == 1.toByte()
            val ok = standable(world, x, y, z)
            cache.put(key, if (ok) 1 else 0)
            return ok
        }
        if (!stand(startCell.x, startCell.y, startCell.z)) {
            val cell = snap(world, agents.x[i], agents.y[i], agents.z[i])
            if (cell != null && abs(cell.x + 0.5f - agents.x[i]) + abs(cell.z + 0.5f - agents.z[i]) <= 3f) {
                agents.x[i] = cell.x + 0.5f
                agents.y[i] = cell.y.toFloat()
                agents.z[i] = cell.z + 0.5f
                if (queued.add(i)) pending.addLast(i)
            } else {
                agents.arrive(i, population.sim.rng)
            }
            return null
        }
        val open = PriorityQueue<LongArray>(compareBy { java.lang.Double.longBitsToDouble(it[1]) })
        val cost = Long2DoubleOpenHashMap()
        val parent = Long2LongOpenHashMap()
        val start = startCell.asLong()
        cost.put(start, 0.0)
        fun h(x: Int, y: Int, z: Int) = sqrt(((x - gx) * (x - gx) + (z - gz) * (z - gz)).toDouble()) + abs(y - gy) * 0.5
        open.add(longArrayOf(start, java.lang.Double.doubleToLongBits(h(startCell.x, startCell.y, startCell.z))))
        var best = start
        var bestH = h(startCell.x, startCell.y, startCell.z)
        var expanded = 0
        while (open.isNotEmpty() && expanded < MAX_NODES) {
            val node = open.poll()[0]
            val nx = BlockPos.getX(node)
            val ny = BlockPos.getY(node)
            val nz = BlockPos.getZ(node)
            val hh = h(nx, ny, nz)
            if (hh < bestH) {
                bestH = hh
                best = node
            }
            if (abs(nx - gx) <= 1 && abs(nz - gz) <= 1) {
                best = node
                break
            }
            expanded++
            val base = cost.get(node)
            for (d in DIRS) {
                val x2 = nx + d[0]
                val z2 = nz + d[1]
                if (abs(x2 - startCell.x) > RANGE || abs(z2 - startCell.z) > RANGE) continue
                val diagonal = d[0] != 0 && d[1] != 0
                if (diagonal && (!clear(world, nx + d[0], ny, nz, cache) || !clear(world, nx, ny, nz + d[1], cache))) continue
                for (dy in STEPS) {
                    val y2 = ny + dy
                    if (!stand(x2, y2, z2)) continue
                    if (dy > 0 && !passable(world, nx, ny + 2, nz)) break
                    var step = (if (diagonal) 1.414 else 1.0) + (if (dy > 0) 0.6 else 0.0) + (if (dy < 0) 0.2 * -dy else 0.0) +
                        (if (roof(world, x2, y2, z2)) 5.0 else 0.0)
                    // Villagers keep to streets when one goes their way, as townsfolk do.
                    if (com.grandstrategy.mc.build.RoadBlocks.road(world.getBlockState(BlockPos(x2, y2 - 1, z2)))) step *= ROAD_FACTOR
                    val key = BlockPos.asLong(x2, y2, z2)
                    val c = base + step
                    if (c < cost.getOrDefault(key, Double.MAX_VALUE)) {
                        cost.put(key, c)
                        parent.put(key, node)
                        open.add(longArrayOf(key, java.lang.Double.doubleToLongBits(c + h(x2, y2, z2))))
                    }
                    break
                }
            }
        }
        val cells = ArrayList<Long>()
        var cur = best
        while (cur != start) {
            cells += cur
            cur = parent.getOrDefault(cur, start)
            if (cells.size > MAX_NODES) break
        }
        cells.reverse()
        if (cells.isEmpty()) {
            agents.arrive(i, population.sim.rng)
            return null
        }
        val simplified = simplify(cells)
        val xs = FloatArray(simplified.size)
        val ys = FloatArray(simplified.size)
        val zs = FloatArray(simplified.size)
        simplified.forEachIndexed { k, c ->
            xs[k] = BlockPos.getX(c) + 0.5f
            ys[k] = BlockPos.getY(c).toFloat()
            zs[k] = BlockPos.getZ(c) + 0.5f
        }
        return Path(xs, ys, zs, tx, tz)
    }

    private fun simplify(cells: List<Long>): List<Long> {
        if (cells.size <= 2) return cells
        val out = ArrayList<Long>()
        out += cells[0]
        for (k in 1 until cells.size - 1) {
            val a = cells[k - 1]
            val b = cells[k]
            val c = cells[k + 1]
            val d1x = BlockPos.getX(b) - BlockPos.getX(a)
            val d1z = BlockPos.getZ(b) - BlockPos.getZ(a)
            val d2x = BlockPos.getX(c) - BlockPos.getX(b)
            val d2z = BlockPos.getZ(c) - BlockPos.getZ(b)
            val flat = BlockPos.getY(a) == BlockPos.getY(b) && BlockPos.getY(b) == BlockPos.getY(c)
            if (!(flat && d1x == d2x && d1z == d2z)) out += b
        }
        out += cells.last()
        return out
    }

    private fun clear(world: ServerLevel, x: Int, y: Int, z: Int, cache: Long2ByteOpenHashMap): Boolean =
        passable(world, x, y, z) && passable(world, x, y + 1, z)

    fun groundEstimate(world: ServerLevel, x: Int, z: Int): Int {
        val heights = IntArray(25)
        var k = 0
        for (dx in -8..8 step 4) for (dz in -8..8 step 4) {
            heights[k++] = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x + dx, z + dz)
        }
        heights.sort()
        return heights[5]
    }

    private fun roof(world: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        val below = world.getBlockState(BlockPos(x, y - 1, z))
        if (below.`is`(net.minecraft.world.level.block.Blocks.DIRT_PATH) || below.`is`(net.minecraft.world.level.block.Blocks.FARMLAND)) return false
        if (!com.grandstrategy.mc.build.BlockClass.constructed(below)) return false
        return world.canSeeSky(BlockPos(x, y, z))
    }

    fun snap(world: ServerLevel, x: Float, y: Float, z: Float): BlockPos? {
        val bx = floor(x).toInt()
        val bz = floor(z).toInt()
        var best: BlockPos? = null
        var bestScore = Double.MAX_VALUE
        val reference = if (y.isNaN()) groundEstimate(world, bx, bz).toFloat() else y
        for (dx in -4..4) for (dz in -4..4) {
            val cx = bx + dx
            val cz = bz + dz
            if (!world.chunkSource.hasChunk(cx shr 4, cz shr 4)) continue
            val top = world.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz)
            val distance = sqrt((dx * dx + dz * dz).toDouble())
            for (cy in top downTo maxOf(top - 24, world.minY + 1)) {
                if (!standable(world, cx, cy, cz)) continue
                var score = abs(cy - reference) + distance * 2.0
                if (roof(world, cx, cy, cz)) score += 8.0
                if (cy < reference - 2) score += 10.0
                if (score < bestScore) {
                    bestScore = score
                    best = BlockPos(cx, cy, cz)
                }
            }
        }
        return best
    }

    private fun standable(world: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        if (!world.chunkSource.hasChunk(x shr 4, z shr 4)) return false
        if (!passable(world, x, y, z) || !passable(world, x, y + 1, z)) return false
        val feet = world.getBlockState(BlockPos(x, y, z))
        if (!feet.fluidState.isEmpty) return false
        val belowPos = BlockPos(x, y - 1, z)
        val below = world.getBlockState(belowPos)
        if (below.`is`(BlockTags.FENCES) || below.`is`(BlockTags.WALLS) || below.`is`(BlockTags.FENCE_GATES)) return false
        if (!below.fluidState.isEmpty && below.getCollisionShape(world, belowPos).isEmpty) return false
        val shape = below.getCollisionShape(world, belowPos)
        return !shape.isEmpty && shape.max(net.minecraft.core.Direction.Axis.Y) >= 0.5
    }

    private fun passable(world: ServerLevel, x: Int, y: Int, z: Int): Boolean {
        val pos = BlockPos(x, y, z)
        val state = world.getBlockState(pos)
        if (state.isAir) return true
        if (state.`is`(BlockTags.DOORS) || state.`is`(BlockTags.FENCE_GATES)) return true
        if (!state.fluidState.isEmpty) return state.getCollisionShape(world, pos).isEmpty
        return state.getCollisionShape(world, pos).isEmpty
    }

    private companion object {
        const val MAX_NODES = 1600
        const val RANGE = 72
        const val SEARCHES_PER_TICK = 6
        const val ROAD_FACTOR = 0.6
        val DIRS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1), intArrayOf(1, 1), intArrayOf(1, -1), intArrayOf(-1, 1), intArrayOf(-1, -1))
        val STEPS = intArrayOf(0, 1, -1, -2, -3)
    }
}
