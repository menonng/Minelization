package com.grandstrategy.sim

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DistrictsTest {
    @AfterTest
    fun reset() {
        Balance.config = BalanceConfig()
    }

    private fun sim() = Simulation(WorldState(seed = 7), TerrainProvider { Terrain.PLAINS })

    /** A nation at [at]; its start land is the 3×3 around the capital. */
    private fun nation(sim: Simulation, name: String = "A", at: Long = Chunks.pack(0, 0)) = sim.createNation(name, at)

    /** Claims a straight strip eastward from x = [from] (inclusive) to [to] (exclusive) on row z. */
    private fun strip(sim: Simulation, n: Nation, from: Int, to: Int, z: Int = 0) {
        for (x in from until to) sim.assign(Chunks.pack(x, z), n)
    }

    @Test
    fun theFirstDistrictIsRunFromTheCapital() {
        val sim = sim()
        val n = nation(sim)
        val districts = Districts.ofNation(sim.state, n)
        assertEquals(1, districts.size)
        assertEquals(n.capital, districts.single().seat)
        assertEquals(9, districts.single().size)
        assertTrue(n.claims.all { sim.state.districtOf[it] == districts.single().id })
    }

    @Test
    fun landPastTheLimitStartsANewDistrict() {
        val sim = sim()
        val n = nation(sim)
        val limit = Districts.limit(n)
        assertEquals(Balance.config.districts.baseChunks, limit)
        // 9 start chunks, then a strip east: the district fills to the limit, the next chunk starts a new one.
        strip(sim, n, 2, 2 + (limit - 9) + 3)
        val districts = Districts.ofNation(sim.state, n)
        assertEquals(2, districts.size)
        val (first, second) = districts.sortedBy { it.id }
        assertEquals(limit, first.size)
        assertEquals(3, second.size)
        assertEquals(Chunks.pack(2 + (limit - 9), 0), second.seat, "a new district is run from the chunk that started it")
        assertTrue(sim.state.events.any { "새 행정구역" in it.text })
    }

    @Test
    fun researchRaisesTheLimit() {
        val sim = sim()
        val n = nation(sim)
        val t = Balance.config.districts
        n.tech[TechBranch.ADMINISTRATION] = 5
        n.tech[TechBranch.LOGISTICS] = 3
        assertEquals(t.baseChunks + (5 * t.perAdministration + 3 * t.perLogistics).toInt(), Districts.limit(n))
        n.tech[TechBranch.ADMINISTRATION] = 1000
        assertEquals(t.maxChunks, Districts.limit(n))
        // With room, a long strip stays in one district.
        n.tech[TechBranch.ADMINISTRATION] = 5
        strip(sim, n, 2, 20)
        assertEquals(1, Districts.ofNation(sim.state, n).size)
    }

    @Test
    fun lostLandLeavesItsDistrict() {
        val sim = sim()
        val a = nation(sim, "A")
        strip(sim, a, 2, 2 + (Districts.limit(a) - 9) + 4)
        val second = Districts.ofNation(sim.state, a).maxBy { it.id }
        val seat = second.seat
        val b = nation(sim, "B", Chunks.pack(40, 40))
        // Taking the second district's seat moves its seat to the nearest chunk still held.
        sim.assign(seat, b)
        assertEquals(3, second.size)
        assertTrue(second.seat != seat && sim.state.districtOf[second.seat] == second.id)
        assertEquals(b.id, Districts.of(sim.state, seat)?.nation, "the taken chunk joins the conqueror's districts")
        // Losing the rest of it removes the district.
        for (c in a.claims.filter { sim.state.districtOf[it] == second.id }) sim.release(c)
        assertFalse(second.id in sim.state.districts)
        // A destroyed nation takes its districts with it.
        sim.destroy(a, "test")
        assertTrue(Districts.ofNation(sim.state, a).isEmpty())
        assertTrue(sim.state.districtOf.values.none { id -> sim.state.districts[id] == null })
    }

    @Test
    fun oldSavesGetCompactDistricts() {
        val sim = sim()
        val n = nation(sim)
        strip(sim, n, 2, 30)
        // As if loaded from a save made before districts existed.
        sim.state.districts.clear()
        sim.state.districtOf.clear()
        Districts.ensure(sim)
        val districts = Districts.ofNation(sim.state, n)
        assertTrue(n.claims.all { sim.state.districtOf[it] != null })
        assertTrue(districts.all { it.size <= Districts.limit(n) })
        assertEquals(n.claims.size, districts.sumOf { it.size })
        assertEquals(n.capital, Districts.capital(sim.state, n)?.seat)
    }

    @Test
    fun bigDistrictsWithoutAPlazaNeedATownCore() {
        val sim = sim()
        val n = nation(sim)
        strip(sim, n, 2, 2 + (Districts.limit(n) - 9) + 6)
        val second = Districts.ofNation(sim.state, n).maxBy { it.id }
        // The capital district has no plaza yet either; both qualify, the bigger first.
        assertEquals(2, Districts.needingCore(sim.state, n).size)
        val seatX = Chunks.x(second.seat) * 16 + 8
        val id = sim.state.nextRoadId++
        sim.state.roads[id] = Road(id, n.id, RoadKind.PLAZA, mutableListOf(Chunks.pack(seatX, 8)))
        assertFalse(second in Districts.needingCore(sim.state, n))
        // The capital district of a new nation qualifies straight away (9 chunks).
        val other = nation(sim, "S", Chunks.pack(60, 60))
        assertTrue(Districts.needingCore(sim.state, other).single().size >= Balance.config.districts.coreAtChunks)
    }

    @Test
    fun coreSpotsStayInTheDistrictAwayFromWaterAndBuildings() {
        val sim = sim()
        val n = nation(sim)
        strip(sim, n, 2, 2 + (Districts.limit(n) - 9) + 6)
        val second = Districts.ofNation(sim.state, n).maxBy { it.id }
        val seatX = Chunks.x(second.seat)
        val terrain = { c: Long -> if (Chunks.x(c) == seatX + 1) Terrain.WATER else Terrain.PLAINS }
        val spots = Districts.coreSpots(sim.state, second, terrain)
        assertEquals(second.seat, spots.first(), "the seat comes first")
        assertTrue(spots.all { sim.state.districtOf[it] == second.id && Chunks.x(it) != seatX + 1 })
        // A building on the seat rules it (and its neighbours) out.
        val bx = seatX * 16 + 4
        sim.state.buildings[1] = Building(1, n.id, BuildingKind.HOUSE, Box(bx, 64, 4, bx + 6, 70, 10), complete = true)
        assertNotNull(Districts.coreSpots(sim.state, second, terrain).firstOrNull())
        assertTrue(Districts.coreSpots(sim.state, second, terrain).none { Chunks.distance(it, second.seat) <= 1 })
    }

    @Test
    fun crowdCountsBuildingsNearACoreButNotWalls() {
        val sim = sim()
        val n = nation(sim)
        val hub = Districts.hubs(sim.state, n).single()
        repeat(3) { k ->
            sim.state.buildings[k + 1] = Building(k + 1, n.id, BuildingKind.HOUSE, Box(hub.first + k * 8, 64, hub.second, hub.first + k * 8 + 5, 70, hub.second + 5), complete = true)
        }
        sim.state.buildings[9] = Building(9, n.id, BuildingKind.WALLS, Box(hub.first, 64, hub.second + 20, hub.first + 5, 68, hub.second + 21), complete = true)
        sim.state.buildings[10] = Building(10, n.id, BuildingKind.HOUSE, Box(hub.first + 300, 64, hub.second, hub.first + 305, 70, hub.second + 5), complete = true)
        assertEquals(3, Districts.crowd(sim.state, n, hub, 48))
    }
}
