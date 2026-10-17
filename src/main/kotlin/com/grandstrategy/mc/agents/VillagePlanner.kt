package com.grandstrategy.mc.agents

import com.grandstrategy.mc.Runtime
import com.grandstrategy.sim.AgentPlanner
import com.grandstrategy.sim.Agents
import com.grandstrategy.sim.Box
import com.grandstrategy.sim.Building
import com.grandstrategy.sim.BuildingKind
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Home
import com.grandstrategy.sim.Job
import kotlin.random.Random

class VillagePlanner(private val rt: Runtime, private val shownCheck: (Int) -> Boolean) : AgentPlanner {
    private val agentCounts = HashMap<Int, Int>()
    private var countedAt = -1L

    private fun buildings(nation: Int, kinds: Set<BuildingKind>): List<Building> =
        rt.state.buildings.values.filter { it.nation == nation && it.complete && it.kind in kinds }

    private fun agentsOf(nation: Int): Int {
        val now = rt.population.now()
        if (now - countedAt > 100 || countedAt < 0) {
            agentCounts.clear()
            rt.population.agents.forEachAlive { i -> agentCounts.merge(rt.population.agents.nation[i], 1, Int::plus) }
            countedAt = now
        }
        return agentCounts[nation] ?: 0
    }

    override fun home(nation: Int, rng: Random): Home? {
        val houses = buildings(nation, setOf(BuildingKind.HOUSE))
        if (houses.isEmpty()) return null
        val capacity = houses.size * HOUSE_CAPACITY
        val chance = capacity.toDouble() / maxOf(1, agentsOf(nation))
        if (rng.nextDouble() > chance) return null
        val house = houses[rng.nextInt(houses.size)]
        val (x, z) = door(house.box, nation)
        return Home(x, z, true)
    }

    private fun door(box: Box, nation: Int): Pair<Float, Float> {
        val capital = rt.state.nation(nation)?.capital
        val cx = capital?.let { Chunks.x(it) * 16f + 8f } ?: box.centerX.toFloat()
        val cz = capital?.let { Chunks.z(it) * 16f + 8f } ?: box.centerZ.toFloat()
        val dx = cx - box.centerX
        val dz = cz - box.centerZ
        return if (kotlin.math.abs(dx) > kotlin.math.abs(dz)) {
            (if (dx > 0) box.maxX + 1.5f else box.minX - 0.5f) to box.centerZ.toFloat()
        } else {
            box.centerX.toFloat() to (if (dz > 0) box.maxZ + 1.5f else box.minZ - 0.5f)
        }
    }

    override fun workSite(agents: Agents, i: Int, rng: Random): Pair<Float, Float>? {
        val kinds = when (Job.entries[agents.job[i].toInt()]) {
            Job.FARMER -> setOf(BuildingKind.FARM)
            Job.LUMBERJACK -> setOf(BuildingKind.LUMBER_CAMP)
            Job.MINER -> setOf(BuildingKind.MINE, BuildingKind.QUARRY)
            Job.MERCHANT -> setOf(BuildingKind.MARKET)
            Job.SCHOLAR -> setOf(BuildingKind.LIBRARY, BuildingKind.TEMPLE)
            Job.SOLDIER -> setOf(BuildingKind.BARRACKS, BuildingKind.WALLS)
            Job.BUILDER -> setOf(BuildingKind.QUARRY, BuildingKind.LUMBER_CAMP)
            Job.CHILD -> emptySet()
        }
        if (kinds.isEmpty()) return null
        val nation = agents.nation[i]
        val hx = agents.homeX[i]
        val hz = agents.homeZ[i]
        val near = buildings(nation, kinds).sortedBy { (it.box.centerX - hx) * (it.box.centerX - hx) + (it.box.centerZ - hz) * (it.box.centerZ - hz) }.take(3)
        if (near.isEmpty()) return null
        val b = near[rng.nextInt(near.size)].box
        return ring(b, rng)
    }

    private fun ring(b: Box, rng: Random): Pair<Float, Float> = when (rng.nextInt(4)) {
        0 -> (b.minX - 1.5f) to (b.minZ + rng.nextFloat() * b.sizeZ)
        1 -> (b.maxX + 2.5f) to (b.minZ + rng.nextFloat() * b.sizeZ)
        2 -> (b.minX + rng.nextFloat() * b.sizeX) to (b.minZ - 1.5f)
        else -> (b.minX + rng.nextFloat() * b.sizeX) to (b.maxZ + 2.5f)
    }

    override fun meeting(nation: Int): Pair<Float, Float>? {
        val temple = buildings(nation, setOf(BuildingKind.TEMPLE, BuildingKind.MARKET)).firstOrNull()
        if (temple != null) return ring(temple.box, Random(nation))
        return rt.state.nation(nation)?.capital?.let { (Chunks.x(it) * 16f + 8f) to (Chunks.z(it) * 16f + 8f) }
    }

    /** Somewhere a player could see a villager appear: anywhere villagers are drawn (the display radius). */
    override fun visibleAt(x: Float, z: Float): Boolean {
        val radius = minOf(rt.population.tuning.displayChunks, rt.server.playerList.viewDistance) * 16.0 + 16.0
        val level = rt.server.overworld()
        return rt.server.playerList.players.any { p ->
            p.level() == level && (p.x - x) * (p.x - x) + (p.z - z) * (p.z - z) <= radius * radius
        }
    }

    override fun shown(i: Int): Boolean = shownCheck(i)

    override fun allowed(nation: Int, x: Float, z: Float): Boolean = rt.zones.allowedAt(nation, x, z)

    private companion object {
        const val HOUSE_CAPACITY = 5
    }
}
