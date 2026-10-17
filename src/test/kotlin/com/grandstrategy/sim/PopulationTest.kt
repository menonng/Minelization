package com.grandstrategy.sim

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PopulationTest {
    @AfterTest
    fun reset() {
        Balance.config = BalanceConfig()
        Content.config = ContentConfig()
    }

    private fun sim() = Simulation(WorldState(seed = 7), TerrainProvider { Terrain.PLAINS })

    /** A nation owning exactly [size]×[size] plains chunks starting at (x0, 0). */
    private fun nation(sim: Simulation, name: String, x0: Int, size: Int): Nation {
        val n = sim.createNation(name, Chunks.pack(x0, 0))
        for (c in n.claims.toList()) {
            sim.state.owners.remove(c)
            n.claims.remove(c)
        }
        for (x in x0 until x0 + size) for (z in 0 until size) sim.assign(Chunks.pack(x, z), n)
        return n
    }

    @Test
    fun capacityComesFromLandBuildingsAndFacilities() {
        val sim = sim()
        val n = nation(sim, "A", 0, 4)
        val t = Balance.config.capacity
        val base = 16 * t.perChunk.getValue(Terrain.PLAINS) + t.capital
        assertEquals(base, Population.capacity(sim, n), 1e-9)
        n.facilities[Facility.FARM] = 2
        sim.state.buildings[1] = Building(1, n.id, BuildingKind.HOUSE, Box(0, 64, 0, 6, 70, 6), complete = true)
        assertEquals(base + 2 * t.perFacility.getValue(Facility.FARM) + t.perHouse, Population.capacity(sim, n), 1e-9)
        n.tech[TechBranch.AGRICULTURE] = 100
        val era = 1.0 + t.perEra * Content.config.eraIndex(n)
        assertEquals((base + 2 * t.perFacility.getValue(Facility.FARM) + t.perHouse) * t.techMax * era, Population.capacity(sim, n), 1e-9)
    }

    @Test
    fun overcrowdingStopsGrowthAndShrinksTheNation() {
        val sim = sim()
        val n = nation(sim, "A", 0, 4)
        // A ruled nation: the AI won't claim more land (which would raise capacity) during the test.
        n.ruler = "tester"
        n.stock.keys.forEach { n.stock[it] = 500.0 }
        n.population = Population.capacity(sim, n) * 2.5
        val start = n.population
        repeat(80) { sim.turn() }
        assertTrue(n.population < start * 0.75, "population ${n.population} from $start")
        assertTrue(sim.state.events.any { e -> Content.config.crowdEvents.any { it.text.substringAfter("}").take(4) in e.text } },
            "no overcrowding event was logged")
    }

    @Test
    fun roomyNationsHaveNoOvercrowdingEvents() {
        val sim = sim()
        val n = nation(sim, "A", 0, 6)
        n.population = 10.0
        repeat(40) { sim.turn() }
        assertEquals(0.0, Population.over(n))
        val crowdWords = listOf("전염병", "약탈", "큰불", "도둑", "일손", "떠났다", "들고일어났다")
        assertTrue(sim.state.events.none { e -> crowdWords.any { it in e.text } })
    }

    @Test
    fun emigrantsMoveToTheRoomiestNeighbour() {
        val sim = sim()
        val a = nation(sim, "A", 0, 3)
        val b = nation(sim, "B", 3, 3)
        sim.computeBorders()
        a.capacity = 50.0
        a.population = 200.0
        b.capacity = 400.0
        b.population = 10.0
        Population.apply(sim, a, Content.config.crowdEvents.first { it.id == "emigration" })
        assertEquals(184.0, a.population, 1.0)
        assertEquals(26.0, b.population, 1.0)
    }
}
