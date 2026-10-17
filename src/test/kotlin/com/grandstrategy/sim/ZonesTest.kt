package com.grandstrategy.sim

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ZonesTest {
    private fun stateWith(claims: List<Long>): WorldState {
        val state = WorldState()
        val n = Nation(1, "N", 0, claims.first(), personality = Personality(0.5, 0.5, 0.5))
        n.claims.addAll(claims)
        state.nations[1] = n
        return state
    }

    @Test
    fun zoneIsLandPlusOneChunk() {
        val zones = NationZones(stateWith(listOf(Chunks.pack(0, 0), Chunks.pack(1, 0))))
        assertTrue(zones.allowed(1, Chunks.pack(0, 0)))
        assertTrue(zones.allowed(1, Chunks.pack(2, 1)), "diagonal neighbour of owned land")
        assertTrue(zones.allowed(1, Chunks.pack(-1, -1)))
        assertFalse(zones.allowed(1, Chunks.pack(3, 0)))
        assertFalse(zones.allowedAt(1, 48f + 1f, 8f))
        assertTrue(zones.allowedAt(1, 47f, 8f))
        assertTrue(zones.allowed(99, Chunks.pack(50, 50)), "unknown nations are not held back")
    }

    @Test
    fun walkingAgentsStopAtTheBorder() {
        val zones = NationZones(stateWith(listOf(Chunks.pack(0, 0))))
        val agents = Agents(4)
        val planner = object : AgentPlanner {
            override fun allowed(nation: Int, x: Float, z: Float) = zones.allowedAt(nation, x, z)
        }
        val i = agents.add(1, Home(8f, 8f, false), 8f, 8f, Job.FARMER, Random(1), 0)
        agents.go(i, AgentState.TO_WORK, 200f, 8f)
        var now = 0L
        repeat(400) {
            now += 20
            agents.step(i, now, DayPhase.WORK, planner, Random(2))
            assertTrue(agents.x[i] < 32f, "agent left the zone: x=${agents.x[i]}")
        }
    }
}
