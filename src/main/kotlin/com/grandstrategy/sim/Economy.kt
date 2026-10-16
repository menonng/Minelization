package com.grandstrategy.sim

import kotlin.math.max
import kotlin.math.min

object Economy {
    fun run(sim: Simulation) {
        for (nation in sim.state.alive()) step(sim, nation)
    }

    fun facilityFor(resource: Resource): Facility = Facility.entries.first { it.boosts == resource }

    fun production(sim: Simulation, nation: Nation): Map<Resource, Double> {
        val base = HashMap<Resource, Double>()
        for (chunk in nation.claims) {
            val terrain = sim.terrain(chunk)
            for (r in Resource.entries) base.merge(r, Balance.yield(terrain, r), Double::plus)
        }
        val labor = min(1.0, nation.population / max(1.0, nation.claims.size * Balance.WORKERS_PER_CHUNK))
        val industry = 1.0 + 0.05 * nation.level(TechBranch.INDUSTRY)
        val policy = when (nation.policy) {
            Policy.ECONOMY -> 1.15
            Policy.MILITARY -> 0.9
            else -> 1.0
        }
        val unrest = if (nation.stability < 25) 0.75 else 1.0
        val result = HashMap<Resource, Double>()
        for (r in Resource.entries) {
            val facility = 1.0 + Balance.FACILITY_BONUS * nation.count(facilityFor(r)) * industry
            val tech = when (r) {
                Resource.FOOD -> 1.0 + 0.1 * nation.level(TechBranch.AGRICULTURE)
                Resource.WOOD -> 1.0 + 0.05 * nation.level(TechBranch.INDUSTRY)
                else -> 1.0 + 0.1 * nation.level(TechBranch.MINING)
            }
            result[r] = (base[r] ?: 0.0) * labor * facility * tech * policy * unrest
        }
        var tax = nation.population * Balance.TAX_PER_POP
        if (nation.policy == Policy.SCIENCE) tax *= 0.9
        result.merge(Resource.GOLD, tax, Double::plus)
        val partners = sim.state.alive().count { it.id != nation.id && sim.state.relation(nation.id, it.id).has(Treaty.TRADE, sim.state.turn) }
        result.merge(Resource.GOLD, partners * (1.5 + 0.5 * nation.count(Facility.MARKET)), Double::plus)
        return result
    }

    fun upkeep(nation: Nation): Map<Resource, Double> = mapOf(
        Resource.FOOD to nation.population * Balance.FOOD_PER_POP + nation.military * Balance.FOOD_PER_SOLDIER,
        Resource.GOLD to nation.military * Balance.GOLD_PER_SOLDIER + nation.claims.size * Balance.GOLD_PER_CHUNK
    )

    fun housing(sim: Simulation, nation: Nation): Double {
        val towns = sim.state.settlements.values.filter { it.owner == nation.id }.sumOf { it.population }
        return nation.claims.size * Balance.HOUSING_PER_CHUNK + nation.totalFacilities() * Balance.HOUSING_PER_FACILITY + towns
    }

    fun science(nation: Nation): Double {
        var s = nation.population * Balance.SCIENCE_PER_POP * (1.0 + 0.15 * nation.level(TechBranch.EDUCATION))
        if (nation.policy == Policy.SCIENCE) s *= 1.3
        return s + nation.count(Facility.LIBRARY) * Balance.SCIENCE_PER_LIBRARY
    }

    private fun step(sim: Simulation, nation: Nation) {
        val produced = production(sim, nation)
        val consumed = upkeep(nation)
        val net = HashMap<Resource, Double>()
        for (r in Resource.entries) net[r] = (produced[r] ?: 0.0) - (consumed[r] ?: 0.0)
        val starving = nation.has(Resource.FOOD) + (net[Resource.FOOD] ?: 0.0) < 0
        val bankrupt = nation.has(Resource.GOLD) + (net[Resource.GOLD] ?: 0.0) < 0
        val storage = Balance.storageCap(nation)
        for (r in Resource.entries) {
            nation.add(r, net[r] ?: 0.0)
            if (nation.has(r) > storage) nation.stock[r] = storage
        }
        nation.lastIncome = net

        val cap = housing(sim, nation)
        if (starving) {
            nation.population *= 1.0 - Balance.STARVATION_LOSS
        } else {
            val room = 1.0 - nation.population / max(1.0, cap)
            nation.population += nation.population * Balance.GROWTH_RATE * room + if (room > 0) 0.5 else 0.0
        }
        nation.population = nation.population.coerceAtLeast(1.0)
        if (bankrupt) nation.military *= 0.97

        val science = science(nation)
        nation.lastScience = science
        nation.researchProgress += science
        while (nation.researchProgress >= Balance.techCost(nation.level(nation.researchFocus))) {
            nation.researchProgress -= Balance.techCost(nation.level(nation.researchFocus))
            val level = nation.level(nation.researchFocus) + 1
            nation.tech[nation.researchFocus] = level
            sim.log(EventKind.ECONOMY, "${nation.name}: ${nation.researchFocus.label} 기술 ${level}단계 달성", nation.id)
        }

        var target = Balance.BASE_STABILITY + 3.0 * nation.level(TechBranch.ADMINISTRATION)
        val over = nation.claims.size - Balance.overextensionLimit(nation)
        if (over > 0) target -= over * 0.5
        val exhaustion = sim.state.warsOf(nation.id).maxOfOrNull { it.exhaustionOf(nation.id) } ?: 0.0
        target -= exhaustion * 0.3
        if (starving) target -= 20
        if (bankrupt) target -= 10
        if (nation.policy == Policy.EXPANSION) target -= 5
        nation.stability += (target.coerceIn(0.0, 100.0) - nation.stability) * 0.1
        nation.stability = nation.stability.coerceIn(0.0, 100.0)
    }
}
