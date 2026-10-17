package com.grandstrategy.sim

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarfareTest {
    private fun sim() = Simulation(WorldState(seed = 11), TerrainProvider { Terrain.PLAINS })

    /** A nation owning the strip x in [x0, x1), z in [0, 3). */
    private fun nation(sim: Simulation, name: String, x0: Int, x1: Int): Nation {
        val n = sim.createNation(name, Chunks.pack(x0, 1))
        for (c in n.claims.toList()) {
            sim.state.owners.remove(c)
            n.claims.remove(c)
        }
        for (x in x0 until x1) for (z in 0 until 3) sim.assign(Chunks.pack(x, z), n)
        return n
    }

    @Test
    fun aFallenCapitalMovesAwayFromTheFront() {
        val sim = sim()
        val defender = nation(sim, "D", 0, 12)
        val attacker = nation(sim, "A", 12, 15)
        sim.computeBorders()
        Warfare.declare(sim, attacker, defender)
        // The capital was on the front line (x = 11) and has just been lost.
        defender.capital = Chunks.pack(11, 1)
        Warfare.relocateCapital(sim, defender)
        // The new one is at the far end of the country, not the next chunk over.
        assertEquals(0, Chunks.x(defender.capital))
        assertTrue(defender.capital in defender.claims)
    }

    @Test
    fun inPeaceTheCapitalStaysNearby() {
        val sim = sim()
        val n = nation(sim, "N", 0, 12)
        val old = Chunks.pack(6, 1)
        n.capital = old
        Warfare.relocateCapital(sim, n)
        assertTrue(Chunks.distance(n.capital, old) <= 1, "moved to ${Chunks.format(n.capital)}")
    }

    @Test
    fun aCapitalThatKeepsFallingGetsOneCutscene() {
        val sim = sim()
        val defender = nation(sim, "D", 0, 4)
        val attacker = nation(sim, "A", 4, 40)
        sim.computeBorders()
        attacker.military = 1e9
        defender.military = 0.0
        var cutscenes = 0
        sim.onCinematic { if (it is Cinematic.CapitalFell) cutscenes++ }
        Warfare.declare(sim, attacker, defender)
        repeat(6) { if (defender.alive) Warfare.run(sim) }
        assertTrue(cutscenes <= 1, "$cutscenes capital cutscenes")
    }
}
