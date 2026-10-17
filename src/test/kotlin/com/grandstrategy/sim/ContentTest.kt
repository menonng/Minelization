package com.grandstrategy.sim

import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentTest {
    private val json = Json { encodeDefaults = true; allowStructuredMapKeys = true; ignoreUnknownKeys = true }

    @AfterTest
    fun reset() {
        Content.config = ContentConfig()
    }

    private fun nation(vararg levels: Pair<TechBranch, Int>): Nation =
        Nation(1, "N", 0, 0, personality = Personality(0.5, 0.5, 0.5)).also { n -> levels.forEach { (b, l) -> n.tech[b] = l } }

    @Test
    fun defaultsAreConsistentAndRoundTrip() {
        val c = ContentConfig()
        assertEquals(emptyList(), c.problems())
        assertEquals(13, c.eras.size)
        val text = json.encodeToString(ContentConfig.serializer(), c)
        assertEquals(c, json.decodeFromString(ContentConfig.serializer(), text))
    }

    @Test
    fun erasFollowTotalResearch() {
        val c = ContentConfig()
        assertEquals("stone", c.era(nation()).id)
        assertEquals("stone", c.era(nation(TechBranch.MINING to 2, TechBranch.MILITARY to 1)).id)
        assertEquals("bronze", c.era(nation(TechBranch.MINING to 2, TechBranch.MILITARY to 2)).id)
        assertEquals("classical", c.era(nation(TechBranch.MINING to 6, TechBranch.MILITARY to 5, TechBranch.EDUCATION to 5)).id)
    }

    @Test
    fun bestConventionalWeaponNeverCountsWeaponsOfMassDestruction() {
        val c = ContentConfig()
        assertEquals("club_spear", c.weapon(nation()).id)
        val bronze = nation(TechBranch.MINING to 1, TechBranch.MILITARY to 1, TechBranch.AGRICULTURE to 2)
        assertEquals("bronze_arms", c.weapon(bronze).id)
        // An atomic-age nation with fission has nuclear arms on its list, but they don't set its conventional strength.
        val atomic = nation(TechBranch.MILITARY to 12, TechBranch.INDUSTRY to 12, TechBranch.EDUCATION to 12, TechBranch.MINING to 12,
            TechBranch.LOGISTICS to 12, TechBranch.CONSTRUCTION to 11, TechBranch.ADMINISTRATION to 10)
        assertEquals("atomic", c.era(atomic).id)
        assertTrue(c.wmds(atomic).any { it.id == "fission_bomb" })
        assertTrue(c.wmds(atomic).any { it.id == "thermonuclear" })
        assertNull(c.weapon(atomic).wmd)
        assertTrue(c.weapon(atomic).attack >= 6.5)
    }

    @Test
    fun namedTechsGateUnlocks() {
        val c = ContentConfig()
        // Renaissance era without gunpowder: no cannon yet.
        val n = nation(TechBranch.EDUCATION to 9, TechBranch.ADMINISTRATION to 9, TechBranch.AGRICULTURE to 9, TechBranch.LOGISTICS to 9)
        assertEquals("renaissance", c.era(n).id)
        assertFalse(c.hasTech(n, "gunpowder"))
        assertNotEquals("cannon", c.weapon(n).id)
        n.tech[TechBranch.MILITARY] = 4
        n.tech[TechBranch.MINING] = 3
        assertTrue(c.hasTech(n, "gunpowder"))
        assertEquals("cannon", c.weapon(n).id)
    }

    @Test
    fun effectsAreNeutralAtTheStartAndFollowContent() {
        val n = nation()
        assertEquals(1.0, Content.productionFactor(n))
        assertEquals(1.0, Content.militaryFactor(n))
        assertEquals(0.0, Content.stabilityBonus(n))
        n.ideology = "animism"
        assertEquals(5.0, Content.stabilityBonus(n))
        // Content is data: a stronger first weapon shows up immediately.
        Content.config = ContentConfig(weapons = listOf(WeaponDef("rocks", "돌", attack = 2.0)))
        assertEquals(2.0, Content.militaryFactor(n))
    }

    @Test
    fun ideologyChoiceRespectsUnlocks() {
        val n = nation()
        val picked = Content.preferredIdeology(n)
        assertTrue(picked != null && Content.config.met(picked.requires, n))
        val sim = Simulation(WorldState(), TerrainProvider { Terrain.PLAINS })
        assertFalse(Progress.choose(sim, n, "technocracy").ok)
        assertTrue(Progress.choose(sim, n, "tribalism").ok)
        assertEquals("tribalism", n.ideology)
    }

    @Test
    fun brokenReferencesAreReported() {
        val bad = ContentConfig(weapons = Defaults.weapons + WeaponDef("laser", "레이저", Requirement(era = "space", techs = listOf("lasers"))))
        val problems = bad.problems()
        assertTrue(problems.any { "space" in it } && problems.any { "lasers" in it }, problems.toString())
    }

    @Test
    fun randomFlagsAreStableAndReadable() {
        val a = Flags.random(42, Flags.RED)
        assertEquals(a, Flags.random(42, Flags.RED))
        repeat(200) { k ->
            val f = Flags.random(k.toLong(), k % 16)
            assertEquals(k % 16, f.base)
            assertTrue(f.layers.size in 1..Flags.MAX_LAYERS)
            assertTrue(f.layers.all { it.color != f.base && it.color in 0..15 }, "layer colour equals the field: $f")
        }
    }
}
