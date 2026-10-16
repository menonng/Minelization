package com.grandstrategy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentsTest {
    private val plains = TerrainProvider { Terrain.PLAINS }

    private fun world(nations: Int): Simulation {
        val sim = Simulation(WorldState(seed = 9), plains)
        repeat(nations) { i -> sim.createNation("n$i", Chunks.pack(i * 20, 0)) }
        return sim
    }

    private fun counts(pop: AgentPopulation): Map<Int, Int> {
        val m = HashMap<Int, Int>()
        pop.agents.forEachAlive { m.merge(pop.agents.nation[it], 1, Int::plus) }
        return m
    }

    @Test
    fun agentCountFollowsPopulation() {
        val sim = world(3)
        sim.state.alive().forEachIndexed { i, n -> n.population = 100.0 * (i + 1) }
        val pop = AgentPopulation(sim)
        pop.sync()
        assertEquals(mapOf(1 to 100, 2 to 200, 3 to 300), counts(pop))
        sim.state.nations[2]!!.population = 50.0
        pop.sync()
        assertEquals(50, counts(pop)[2])
        pop.agents.forEachAlive { i -> assertEquals(pop.agents.nation[i], sim.ownerOf(pop.agents.homeChunk(i))?.id) }
    }

    @Test
    fun conqueredHomesChangeAllegiance() {
        val sim = world(2)
        sim.state.alive().forEach { it.population = 200.0 }
        val a = sim.state.nations[1]!!
        val b = sim.state.nations[2]!!
        val pop = AgentPopulation(sim)
        pop.sync()
        for (c in a.claims.toList()) if (c != a.capital) sim.assign(c, b)
        pop.sync()
        pop.agents.forEachAlive { i ->
            val owner = sim.ownerOf(pop.agents.homeChunk(i))
            assertEquals(owner?.id, pop.agents.nation[i])
        }
        sim.destroy(a, "test")
        pop.sync()
        pop.agents.forEachAlive { i -> assertTrue(pop.agents.nation[i] != a.id) }
    }

    @Test
    fun agentsWalkBetweenHomeAndWork() {
        val sim = world(1)
        sim.state.nations[1]!!.population = 50.0
        val pop = AgentPopulation(sim)
        pop.sync()
        val start = (0 until pop.agents.high).map { pop.agents.x[it] to pop.agents.z[it] }
        repeat(20 * 120) { pop.tick(emptyList()) }
        val movedCount = (0 until pop.agents.high).count { (pop.agents.x[it] to pop.agents.z[it]) != start[it] }
        assertTrue(movedCount > 40, "most agents should have walked somewhere, moved=$movedCount")
        val nation = sim.state.nations[1]!!
        pop.agents.forEachAlive { i ->
            assertTrue(sim.state.owners[pop.agents.chunkOf(i)] == nation.id || Chunks.distance(pop.agents.chunkOf(i), nation.capital) <= 4)
        }
    }

    @Test
    fun twentyThousandAgentsBenchmark() {
        val sim = Simulation(WorldState(seed = 3), plains)
        for (i in 0 until 10) {
            val n = sim.createNation("n$i", Chunks.pack(i * 30, 0))
            for (c in Chunks.around(n.capital, 5)) if (!sim.state.owners.containsKey(c)) sim.assign(c, n)
        }
        val pop = AgentPopulation(sim)
        pop.benchmarkTotal = 20_000
        while (pop.agents.count < 19_900) pop.sync()
        val hot = (0 until 1500).toList()
        repeat(400) { pop.tick(hot) }
        val ticks = 2000
        var syncNanos = 0L
        val start = System.nanoTime()
        for (t in 0 until ticks) {
            pop.tick(hot)
            if (t % 200 == 0) {
                val s = System.nanoTime()
                pop.sync()
                syncNanos += System.nanoTime() - s
            }
        }
        val total = System.nanoTime() - start
        val perTick = (total - syncNanos) / 1e6 / ticks
        val perSync = syncNanos / 1e6 / (ticks / 200)
        println("agents=${pop.agents.count} avg tick ${"%.4f".format(perTick)} ms, sync ${"%.3f".format(perSync)} ms (every 200 ticks)")
        assertTrue(perTick < 1.0, "agent tick must stay far below the 50 ms tick budget")
    }
}
