package com.grandstrategy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoadsTest {
    private class Flat(val heights: Map<Long, Int> = emptyMap(), val costs: Map<Long, Double> = emptyMap(), val size: Int = 64) : RoadGround {
        override fun height(x: Int, z: Int): Int? =
            if (x < -size || x > size || z < -size || z > size) null else heights[Chunks.pack(x, z)] ?: 64

        override fun cost(x: Int, z: Int): Double = costs[Chunks.pack(x, z)] ?: 0.0
    }

    private fun path(ground: RoadGround, goal: Long, blocked: (Int, Int) -> Boolean = { _, _ -> false }, existing: Set<Long> = emptySet()) =
        RoadPathfinder.find(ground, 0, 0, Chunks.x(goal), Chunks.z(goal), { it == goal || it in existing }, { it in existing }, blocked, 64, 20000)

    @Test
    fun straightLineOnFlatGround() {
        val p = assertNotNull(path(Flat(), Chunks.pack(10, 0)))
        assertEquals(11, p.size)
        assertTrue(p.all { Chunks.z(it) == 0 })
    }

    @Test
    fun movesAreFourWayAndPreferFewTurns() {
        val p = assertNotNull(path(Flat(), Chunks.pack(8, 8)))
        for (k in 1 until p.size) {
            val dx = kotlin.math.abs(Chunks.x(p[k]) - Chunks.x(p[k - 1]))
            val dz = kotlin.math.abs(Chunks.z(p[k]) - Chunks.z(p[k - 1]))
            assertEquals(1, dx + dz)
        }
        var turns = 0
        for (k in 2 until p.size) {
            val a = Chunks.x(p[k - 1]) - Chunks.x(p[k - 2])
            val b = Chunks.x(p[k]) - Chunks.x(p[k - 1])
            if ((a == 0) != (b == 0)) turns++
        }
        assertEquals(1, turns, "an L-shaped street, not a staircase")
    }

    @Test
    fun goesAroundBuildings() {
        val wall: (Int, Int) -> Boolean = { x, z -> x == 5 && z in -6..6 }
        val p = assertNotNull(path(Flat(), Chunks.pack(10, 0), wall))
        assertTrue(p.none { wall(Chunks.x(it), Chunks.z(it)) })
    }

    @Test
    fun refusesCliffsAndUnknownGround() {
        val cliff = HashMap<Long, Int>()
        for (x in 5..64) for (z in -64..64) cliff[Chunks.pack(x, z)] = 70
        assertNull(path(Flat(cliff), Chunks.pack(10, 0)))
        assertNull(RoadPathfinder.find(Flat(size = 4), 0, 0, 10, 0, { it == Chunks.pack(10, 0) }, { false }, { _, _ -> false }, 64, 5000))
    }

    @Test
    fun gentleSlopesAreFine() {
        val ramp = HashMap<Long, Int>()
        for (x in -64..64) for (z in -64..64) ramp[Chunks.pack(x, z)] = 64 + maxOf(0, x) / 2
        assertNotNull(path(Flat(ramp), Chunks.pack(12, 0)))
    }

    @Test
    fun joinsExistingStreetEarly() {
        // An existing street runs along z = 3 from x = 0 to x = 30; a new spur from the origin heading to (30, 3)
        // should reach the street right away instead of running parallel to it.
        val street = (0..30).map { Chunks.pack(it, 3) }.toSet()
        val p = assertNotNull(path(Flat(), Chunks.pack(30, 3), existing = street))
        assertTrue(p.size <= 5, "spur length ${p.size}")
    }

    @Test
    fun avoidsWaterWhenLandIsNear() {
        val costs = HashMap<Long, Double>()
        for (x in 3..7) for (z in -2..2) costs[Chunks.pack(x, z)] = 6.0
        val p = assertNotNull(path(Flat(costs = costs), Chunks.pack(10, 0)))
        assertTrue(p.none { (costs[it] ?: 0.0) > 0 })
    }

    @Test
    fun networkIndexAddClearAndBan() {
        val state = WorldState()
        val net = RoadNetwork(state)
        val a = assertNotNull(net.add(1, RoadKind.STREET, (0..9).map { Chunks.pack(it, 0) }))
        assertNull(net.add(1, RoadKind.STREET, listOf(Chunks.pack(3, 0))), "already road")
        val b = assertNotNull(net.add(1, RoadKind.MANUAL, listOf(Chunks.pack(9, 0), Chunks.pack(10, 0))))
        assertEquals(listOf(Chunks.pack(10, 0)), b.cells)
        val removed = net.clear(0, 0, 4, 0, ban = true)
        assertEquals(5, removed.size)
        assertEquals(5, a.cells.size)
        assertTrue(Chunks.pack(2, 0) in state.roadBans)
        assertFalse(net.isRoad(Chunks.pack(2, 0)))
        net.clear(5, 0, 10, 0, ban = false)
        assertTrue(state.roads.isEmpty())
        assertEquals(0, net.cellCount)
        // The index survives a save/load round trip by rebuilding from the roads.
        net.add(2, RoadKind.PLAZA, listOf(Chunks.pack(1, 1)))
        assertEquals(2, RoadNetwork(state).nationAt(Chunks.pack(1, 1)))
    }

    @Test
    fun widenAndPlazaStayOnLevelGround() {
        val heights = HashMap<Long, Int>()
        for (z in -64..64) heights[Chunks.pack(0, z)] = 64
        for (z in -64..64) heights[Chunks.pack(1, z)] = 67
        val ground = Flat(heights)
        val line = (0..5).map { Chunks.pack(0, it) }
        val wide = RoadPathfinder.widen(line, 3, ground) { _, _ -> false }
        assertTrue(wide.none { Chunks.x(it) == 1 }, "the +x side is a 3-block step, so nothing is added there")
        // The -x side (8 columns, with the ends) and one column past each end of the line.
        assertEquals(line.size + 8 + 2, wide.size)
        val flatWide = RoadPathfinder.widen((0..5).map { Chunks.pack(it, 10) }, 3, Flat()) { _, _ -> false }
        assertEquals(8 * 3, flatWide.size, "three wide, and a column past each end so corners come out full")
        assertEquals(setOf(9, 10, 11), flatWide.map { Chunks.z(it) }.toSet())
        // A wall beside the road narrows it there instead of stopping it.
        val walled = RoadPathfinder.widen((0..5).map { Chunks.pack(it, 10) }, 3, Flat()) { x, z -> z == 11 && x in 2..3 }
        assertEquals(8 * 3 - 2, walled.size)
        val plaza = RoadPathfinder.plaza(10, 10, 2, Flat()) { _, _ -> false }
        assertEquals(21, plaza.size)
    }

    @Test
    fun guessedHeightsSkipSlopeLimits() {
        val cliff = HashMap<Long, Int>()
        for (x in 5..64) for (z in -64..64) cliff[Chunks.pack(x, z)] = 90
        val guessed = object : RoadGround {
            override fun height(x: Int, z: Int): Int = cliff[Chunks.pack(x, z)] ?: 64
            override fun cost(x: Int, z: Int): Double = 0.0
            override fun exact(x: Int, z: Int): Boolean = x < 5
        }
        assertNotNull(path(guessed, Chunks.pack(10, 0)), "unloaded land is planned as passable")
    }

    @Test
    fun lotsSitBesideTheRoadWithAYard() {
        val street = (0..20).map { Chunks.pack(it, 0) }
        val roadSet = street.toSet()
        val (lot, rotation) = assertNotNull(RoadLots.find(street, 0, 0, 7, 5, kotlin.random.Random(1), { box ->
            (box.minX - 1..box.maxX + 1).none { x -> (box.minZ - 1..box.maxZ + 1).any { z -> Chunks.pack(x, z) in roadSet } }
        }))
        // The front (north for rotation 0, then east, south, west) looks across the one-block yard onto road.
        val front = when (rotation) {
            0 -> (lot.minX..lot.maxX).map { Chunks.pack(it, lot.minZ - 2) }
            1 -> (lot.minZ..lot.maxZ).map { Chunks.pack(lot.maxX + 2, it) }
            2 -> (lot.minX..lot.maxX).map { Chunks.pack(it, lot.maxZ + 2) }
            else -> (lot.minZ..lot.maxZ).map { Chunks.pack(lot.minX - 2, it) }
        }
        assertTrue(front.any { it in roadSet }, "lot $lot rotation $rotation does not face the road")
        val gap = street.minOf { c ->
            maxOf(maxOf(lot.minX - Chunks.x(c), Chunks.x(c) - lot.maxX, 0), maxOf(lot.minZ - Chunks.z(c), Chunks.z(c) - lot.maxZ, 0))
        }
        assertEquals(2, gap, "one-block yard between road and wall: $lot")
        assertTrue(RoadLots.near(lot, RoadLots.REACH) { x, z -> Chunks.pack(x, z) in roadSet })
        val far = Box(0, 0, 10, 6, 0, 14)
        assertFalse(RoadLots.near(far, RoadLots.REACH) { x, z -> Chunks.pack(x, z) in roadSet })
        assertNull(RoadLots.find(street, 0, 0, 7, 5, kotlin.random.Random(1), { false }))
    }
}
