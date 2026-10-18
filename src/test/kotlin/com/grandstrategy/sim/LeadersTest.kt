package com.grandstrategy.sim

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LeadersTest {
    @AfterTest
    fun reset() {
        Balance.config = BalanceConfig()
        Content.config = ContentConfig()
    }

    private fun sim() = Simulation(WorldState(seed = 3), TerrainProvider { Terrain.PLAINS })

    private fun leader(sim: Simulation, house: String, age: Int, traits: Double = 0.5) = Leader(
        name = "아르린", house = house, regnal = 1, born = sim.state.turn - age.toLong() * Balance.config.leaders.turnsPerYear,
        crowned = sim.state.turn, aggression = traits, diplomacy = traits, greed = traits, wisdom = traits
    )

    @Test
    fun everyAiNationGetsARuler() {
        val sim = sim()
        val n = sim.createNation("A", Chunks.pack(0, 0))
        Leaders.run(sim)
        val l = assertNotNull(n.leader)
        assertTrue(Leaders.age(sim, l) in 28..47)
        // No ideology yet: a chieftain, and the title follows the ideology once chosen.
        assertTrue(Leaders.describe(n)!!.startsWith("족장 "))
        n.ideology = "republic"
        assertTrue(Leaders.describe(n)!!.startsWith("집정관 "))
        assertEquals(Succession.ELECTIVE, Leaders.succession(n))
    }

    @Test
    fun anOldRulerDiesAndTheHeirKeepsTheHouse() {
        val sim = sim()
        val n = sim.createNation("A", Chunks.pack(0, 0))
        n.ideology = "monarchy"
        n.leader = leader(sim, "벨라스", age = 120)
        repeat(200) { if (n.leader?.house == "벨라스" && n.leader?.name == "아르린" && n.leader?.regnal == 1) Leaders.run(sim) }
        val heir = assertNotNull(n.leader)
        assertEquals("벨라스", heir.house, "a monarchy passes to the same house")
        assertTrue(n.pastLeaders.single().startsWith("왕 아르린 1세"))
        assertTrue(sim.state.events.any { "뒤를 이었다" in it.text })
    }

    @Test
    fun anElectionUsuallyBringsANewHouse() {
        val sim = sim()
        val n = sim.createNation("A", Chunks.pack(0, 0))
        n.ideology = "republic"
        n.leader = leader(sim, "벨라스", age = 60)
        Leaders.die(sim, n, "test")
        assertNotEquals("벨라스", n.leader?.house)
    }

    @Test
    fun aPlayerReplacesTheCharacterAndAnotherComesBackAfter() {
        val sim = sim()
        val n = sim.createNation("A", Chunks.pack(0, 0))
        Leaders.run(sim)
        n.ruler = "player-uuid"
        Leaders.run(sim)
        assertNull(n.leader)
        assertEquals(1, n.pastLeaders.size)
        n.autoGovern = true
        Leaders.run(sim)
        assertNotNull(n.leader)
    }

    @Test
    fun anUnstableNationCanFallToACoup() {
        val sim = sim()
        Balance.config = BalanceConfig(leaders = LeaderTuning(coupChance = 1.0, deathBase = 0.0))
        val n = sim.createNation("A", Chunks.pack(0, 0))
        n.leader = leader(sim, "벨라스", age = 30)
        n.stability = 5.0
        Leaders.run(sim)
        assertNotEquals("벨라스", n.leader?.house)
        assertTrue(sim.state.events.any { "쿠데타" in it.text })
    }

    @Test
    fun aDisputedSuccessionSplitsTheNation() {
        val sim = sim()
        Balance.config = BalanceConfig(leaders = LeaderTuning(disputeHereditary = 1.0))
        val n = sim.createNation("A", Chunks.pack(0, 0))
        for (x in -6..6) for (z in -6..6) sim.assign(Chunks.pack(x, z), n)
        n.ideology = "monarchy"
        n.leader = leader(sim, "벨라스", age = 70)
        val before = sim.state.alive().size
        Leaders.die(sim, n, "test")
        assertEquals(before + 1, sim.state.alive().size)
        val rebel = sim.state.alive().first { it.id != n.id }
        assertEquals("벨라스", rebel.leader?.house, "the pretender is of the same house")
        assertNotNull(sim.state.warBetween(n.id, rebel.id))
    }

    @Test
    fun traitsColourDecisionsAndTheEconomy() {
        val sim = sim()
        val n = sim.createNation("A", Chunks.pack(0, 0))
        n.leader = leader(sim, "벨라스", age = 40, traits = 1.0)
        assertEquals(1.1, Leaders.scienceFactor(n), 1e-9)
        assertEquals(1.15, Leaders.productionFactor(n, Resource.GOLD), 1e-9)
        assertEquals(1.0, Leaders.productionFactor(n, Resource.FOOD), 1e-9)
        val w = Balance.config.leaders.traitWeight
        assertEquals(n.personality.aggression * (1 - w) + w, Leaders.temper(n).aggression, 1e-9)
        n.leader = leader(sim, "벨라스", age = 40, traits = 0.0)
        assertEquals(0.9, Leaders.scienceFactor(n), 1e-9)
    }
}
