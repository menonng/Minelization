package com.grandstrategy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistrictsTest {
    private val tuning = RoadTuning(districtBuildings = 16, districtRadius = 48, districtSpacing = 5, districtReach = 12, chunksPerDistrict = 50)

    /** A nation owning a square of chunks around chunk (0, 0), with its capital plaza at block (8, 8). */
    private fun world(radius: Int = 15): Pair<WorldState, Nation> {
        val state = WorldState(seed = 7)
        val nation = Nation(1, "A", 0, Chunks.pack(0, 0), personality = Personality(0.5, 0.5, 0.5))
        for (x in -radius..radius) for (z in -radius..radius) nation.claims += Chunks.pack(x, z)
        state.nations[1] = nation
        plaza(state, 8, 8)
        return state to nation
    }

    private fun plaza(state: WorldState, cx: Int, cz: Int) {
        val id = state.nextRoadId++
        state.roads[id] = Road(id, 1, RoadKind.PLAZA, (-2..2).flatMap { dx -> (-2..2).map { dz -> Chunks.pack(cx + dx, cz + dz) } }.toMutableList())
    }

    private fun build(state: WorldState, count: Int, x0: Int = 0, z0: Int = 0, kind: BuildingKind = BuildingKind.HOUSE) {
        repeat(count) { k ->
            val id = state.nextBuildingId++
            val x = x0 + (k % 6) * 12 - 30
            val z = z0 + (k / 6) * 12 - 30
            state.buildings[id] = Building(id, 1, kind, Box(x, 64, z, x + 6, 70, z + 6), complete = true)
        }
    }

    @Test
    fun dueOnceTheCoreIsFull() {
        val (state, nation) = world()
        build(state, 15)
        assertFalse(Districts.due(state, nation, tuning), "15 buildings is not yet full")
        build(state, 1)
        assertTrue(Districts.due(state, nation, tuning))
    }

    @Test
    fun wallsAndFarBuildingsDoNotCount() {
        val (state, nation) = world()
        build(state, 15)
        build(state, 1, kind = BuildingKind.WALLS)
        build(state, 5, x0 = 150, z0 = 150)
        assertEquals(15, Districts.crowd(state, nation, Districts.hubs(state, nation).single(), tuning.districtRadius))
        assertFalse(Districts.due(state, nation, tuning))
    }

    @Test
    fun smallLandSupportsNoDistrict() {
        val (state, nation) = world(radius = 3)
        build(state, 30)
        assertEquals(1, Districts.allowed(nation, tuning), "49 chunks support only the capital")
        assertFalse(Districts.due(state, nation, tuning))
    }

    @Test
    fun nextDistrictWaitsUntilTheNewCoreFillsUp() {
        val (state, nation) = world()
        build(state, 20)
        plaza(state, 8 + 6 * 16, 8)
        assertEquals(2, Districts.hubs(state, nation).size)
        assertFalse(Districts.due(state, nation, tuning), "the new district is still empty")
        build(state, 16, x0 = 6 * 16, z0 = 0)
        assertTrue(Districts.due(state, nation, tuning))
    }

    @Test
    fun candidatesKeepTheirDistanceAndAvoidWaterAndBuildings() {
        val (state, nation) = world()
        build(state, 16)
        build(state, 1, x0 = 8 * 16 + 30, z0 = 30)
        val terrain = { c: Long -> if (Chunks.x(c) == -7) Terrain.WATER else Terrain.PLAINS }
        val picks = Districts.candidates(state, nation, tuning, terrain)
        assertTrue(picks.isNotEmpty())
        for (c in picks) {
            val d = Chunks.distance(c, Chunks.pack(0, 0))
            assertTrue(d in 5..12, "chunk ${Chunks.format(c)} is $d chunks from the capital")
            assertTrue(Chunks.x(c) != -7, "no district on water")
            assertFalse(kotlin.math.abs(Chunks.x(c) - 8) <= 1 && kotlin.math.abs(Chunks.z(c)) <= 1, "no district beside a building")
        }
        assertEquals(5, Chunks.distance(picks.first(), Chunks.pack(0, 0)), "on even land the nearest ring comes first")
    }

    @Test
    fun ownedTownLandComesFirst() {
        val (state, nation) = world()
        state.settlementLand[Chunks.pack(10, -9)] = 1
        assertEquals(Chunks.pack(10, -9), Districts.candidates(state, nation, tuning, { Terrain.PLAINS }).first())
    }
}
