package com.grandstrategy.sim

import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimulationTest {
    private val terrain = TerrainProvider { chunk ->
        val x = Chunks.x(chunk)
        val z = Chunks.z(chunk)
        when {
            abs(x) > 70 || abs(z) > 70 -> Terrain.WATER
            (x * 31 + z * 17).mod(11) == 0 -> Terrain.MOUNTAIN
            (x / 6 + z / 6).mod(3) == 0 -> Terrain.FOREST
            (x / 5 - z / 7).mod(5) == 0 -> Terrain.HILLS
            else -> Terrain.PLAINS
        }
    }

    private fun world(seed: Long, nations: Int): Simulation {
        val sim = Simulation(WorldState(seed = seed), terrain)
        sim.spawnCenter = Chunks.pack(0, 0)
        sim.spawnRadius = 50
        sim.seedNations(nations, sim.spawnCenter, 28)
        for (i in 0 until 10) {
            val c = Chunks.pack(sim.rng.nextInt(-40, 40), sim.rng.nextInt(-40, 40))
            Settlements.register(sim, c, Chunks.around(c, 1))
        }
        sim.state.seeded = true
        return sim
    }

    @Test
    fun nationsGrowAndInteract() {
        val sim = world(42, 8)
        assertEquals(8, sim.state.alive().size)
        val startClaims = sim.state.alive().sumOf { it.claims.size }
        var sawWar = false
        var sawTreaty = false
        repeat(400) {
            sim.turn()
            if (sim.state.wars.isNotEmpty()) sawWar = true
            if (sim.state.relations.values.any { r -> r.treaties.keys.any { it != Treaty.TRUCE } }) sawTreaty = true
        }
        val endClaims = sim.state.alive().sumOf { it.claims.size }
        val pairs = sim.borders.keys.size
        println("bordering pairs: $pairs")
        val kinds = sim.state.events.groupingBy { it.kind }.eachCount()
        println("claims $startClaims -> $endClaims, alive ${sim.state.alive().size}, events $kinds, war=$sawWar treaty=$sawTreaty")
        for (n in sim.state.alive()) {
            println("  ${n.name}: claims=${n.claims.size} pop=${n.population.toInt()} mil=${n.military.toInt()} stab=${n.stability.toInt()} posture=${n.posture} tech=${n.tech.values.sum()} fac=${n.totalFacilities()}")
            println("     stock=${n.stock.mapValues { it.value.toInt() }} net=${n.lastIncome.mapValues { "%.1f".format(it.value) }} claimCost=${Balance.claimCost(n).mapValues { it.value.toInt() }} frontier=${Ai.perceive(sim, n).frontier != null}")
        }
        sim.state.events.takeLast(15).forEach { println("  [${it.turn}] ${it.text}") }
        assertTrue(endClaims > startClaims * 2, "territory should expand")
        assertTrue(sawTreaty, "some treaty should be signed")
        assertTrue(sawWar, "some war should happen")
        assertTrue(sim.state.alive().all { it.population >= 1.0 })
    }

    @Test
    fun ownershipStaysConsistent() {
        val sim = world(7, 10)
        repeat(300) {
            sim.turn()
            for ((chunk, owner) in sim.state.owners) {
                val n = sim.state.nations[owner]!!
                assertTrue(n.alive, "chunk owned by dead nation")
                assertTrue(chunk in n.claims, "owner map and claims disagree")
            }
            for (n in sim.state.nations.values) {
                for (c in n.claims) assertEquals(n.id, sim.state.owners[c])
                if (n.alive) assertTrue(n.capital in n.claims, "capital outside territory for ${n.name}")
            }
        }
    }

    @Test
    fun saveAndLoadRoundTrip() {
        val sim = world(3, 5)
        repeat(50) { sim.turn() }
        val json = Json { allowStructuredMapKeys = true }
        val text = json.encodeToString(WorldState.serializer(), sim.state)
        val restored = json.decodeFromString(WorldState.serializer(), text)
        assertEquals(sim.state.turn, restored.turn)
        assertEquals(sim.state.owners, restored.owners)
        assertEquals(sim.state.nations.keys, restored.nations.keys)
        for ((id, n) in sim.state.nations) {
            val r = restored.nations[id]!!
            assertEquals(n.claims, r.claims)
            assertEquals(n.stock, r.stock)
            assertEquals(n.tech, r.tech)
        }
        val a = Simulation(restored, terrain)
        val b = sim
        a.turn(); b.turn()
        assertEquals(b.state.owners, a.state.owners, "simulation should be deterministic after reload")
    }

    @Test
    fun playerActionsAreValidated() {
        val sim = world(11, 2)
        val n = sim.state.alive().first()
        val far = Chunks.pack(Chunks.x(n.capital) + 20, Chunks.z(n.capital))
        assertTrue(!Actions.execute(sim, n, Action.Claim(far)).ok)
        val edge = Chunks.pack(Chunks.x(n.capital) + 2, Chunks.z(n.capital))
        val result = Actions.execute(sim, n, Action.Claim(edge))
        if (Actions.claimError(sim, n, edge) == null) assertTrue(result.ok, result.message)
        assertTrue(!Actions.execute(sim, n, Action.Recruit(10_000)).ok)
        assertNull(sim.foundingError(Chunks.pack(60, 60)).takeIf { sim.state.owners.isEmpty() })
        assertNotNull(sim.foundingError(n.capital))
    }

    @Test
    fun villagesStayIndependentUntilAnnexed() {
        val sim = Simulation(WorldState(seed = 5), terrain)
        val nation = sim.createNation("테스트 왕국", Chunks.pack(0, 0))
        val village = Settlements.register(sim, Chunks.pack(4, 0), Chunks.around(Chunks.pack(4, 0), 1))!!
        assertTrue(Actions.execute(sim, nation, Action.Claim(Chunks.pack(3, 0))).ok.not())
        nation.add(Resource.GOLD, 1000.0)
        repeat(7) { Actions.execute(sim, nation, Action.Envoy(village.id)) }
        sim.turn()
        assertEquals(nation.id, village.owner)
        assertTrue(village.land.all { sim.state.owners[it] == nation.id })
    }

    @Test
    fun turnPerformance() {
        val sim = world(99, 20)
        repeat(100) { sim.turn() }
        val start = System.nanoTime()
        repeat(100) { sim.turn() }
        val ms = (System.nanoTime() - start) / 1e6 / 100
        val claims = sim.state.alive().sumOf { it.claims.size }
        println("avg turn ${"%.3f".format(ms)} ms with ${sim.state.alive().size} nations, $claims claims")
        assertTrue(ms < 50.0, "turn should be well under one server tick budget")
    }
}
