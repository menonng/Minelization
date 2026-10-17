package com.grandstrategy.sim

import kotlin.math.max
import kotlin.math.min

/**
 * Carrying capacity: how many people a nation can house and feed, from its land (by terrain), houses, facilities,
 * owned towns and research. Below it a nation grows; above it growth stops, stability and production sag in
 * proportion to the overcrowding, and each turn there is a chance of an overcrowding event (plague, riots, fires,
 * crime, strikes, emigration, revolt) defined in content.json.
 */
object Population {
    fun capacity(sim: Simulation, nation: Nation): Double {
        val t = Balance.config.capacity
        var land = 0.0
        for (chunk in nation.claims) land += t.perChunk[sim.terrain(chunk)] ?: 1.0
        val houses = sim.state.buildings.values.count { it.nation == nation.id && it.complete && it.kind == BuildingKind.HOUSE }
        var facilities = 0.0
        for ((f, n) in nation.facilities) facilities += (t.perFacility[f] ?: 3.0) * n
        val towns = sim.state.settlements.values.filter { it.owner == nation.id }.sumOf { it.population }
        val tech = min(t.techMax, 1.0 + t.techPerLevel * (nation.level(TechBranch.AGRICULTURE) + nation.level(TechBranch.ADMINISTRATION)))
        val era = 1.0 + t.perEra * Content.config.eraIndex(nation)
        return (land + houses * t.perHouse + facilities + towns + t.capital) * tech * era
    }

    /** Population over capacity, as a fraction (0.3 = 30% too many); zero when there is room. */
    fun over(nation: Nation): Double = if (nation.capacity <= 0.0) 0.0 else max(0.0, nation.population / nation.capacity - 1.0)

    fun productionFactor(nation: Nation): Double {
        val t = Balance.config.capacity
        val crowd = max(t.productionFloor, 1.0 - over(nation) * t.productionPerOver)
        return crowd * (1.0 - if (nation.crowdPenaltyTurns > 0) nation.crowdPenalty else 0.0)
    }

    fun stabilityPenalty(nation: Nation): Double = over(nation) * Balance.config.capacity.stabilityPerOver

    /** Rolls overcrowding events for every nation over capacity. Called once per turn, after the economy. */
    fun run(sim: Simulation) {
        val t = Balance.config.capacity
        val events = Content.config.crowdEvents
        for (nation in sim.state.alive().toList()) {
            if (nation.crowdPenaltyTurns > 0) nation.crowdPenaltyTurns--
            val over = over(nation)
            if (over <= 0.0 || events.isEmpty()) continue
            if (sim.rng.nextDouble() >= min(t.eventMaxChance, over * t.eventRate)) continue
            val ratio = 1.0 + over
            val options = events.filter { ratio >= it.minRatio && (!it.revolt || nation.stability < 25) }
            if (options.isEmpty()) continue
            var pick = sim.rng.nextDouble() * options.sumOf { it.weight }
            val event = options.firstOrNull { pick -= it.weight; pick <= 0 } ?: options.last()
            apply(sim, nation, event)
        }
    }

    fun apply(sim: Simulation, nation: Nation, event: CrowdEventDef) {
        val lost = (nation.population * event.populationLoss).toInt()
        nation.population = (nation.population - lost).coerceAtLeast(1.0)
        nation.stability = (nation.stability + event.stability).coerceIn(0.0, 100.0)
        for ((r, fraction) in event.stockLoss) nation.stock[r] = nation.has(r) * (1.0 - fraction.coerceIn(0.0, 1.0))
        var target = ""
        var moved = 0
        if (event.emigration > 0) {
            moved = (nation.population * event.emigration).toInt()
            nation.population = (nation.population - moved).coerceAtLeast(1.0)
            // Emigrants go to the neighbour with the most room; with nowhere to go they scatter and are lost.
            val dest = sim.neighbors(nation.id).filter { it.capacity > it.population }.maxByOrNull { it.capacity - it.population }
            if (dest != null) {
                dest.population += moved
                target = dest.name
            }
        }
        if (event.productionPenalty > 0) {
            nation.crowdPenalty = max(if (nation.crowdPenaltyTurns > 0) nation.crowdPenalty else 0.0, event.productionPenalty)
            nation.crowdPenaltyTurns = max(nation.crowdPenaltyTurns, event.penaltyTurns)
        }
        val text = event.text.replace("{nation}", nation.name).replace("{lost}", "$lost").replace("{moved}", "$moved")
            .replace("{target}", target.ifEmpty { "사방" })
        sim.log(if (event.major) EventKind.MAJOR else EventKind.ECONOMY, text, nation.id)
        if (event.revolt) Politics.secede(sim, nation)
    }
}
