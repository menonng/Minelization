package com.grandstrategy.sim

import kotlin.math.pow
import kotlin.math.sqrt

object Balance {
    const val START_POPULATION = 30.0
    const val START_MILITARY = 10.0
    const val START_RADIUS = 1

    const val WORKERS_PER_CHUNK = 3.0
    const val HOUSING_PER_CHUNK = 8.0
    const val HOUSING_PER_FACILITY = 2.0
    const val TAX_PER_POP = 0.02
    const val FOOD_PER_POP = 0.1
    const val FOOD_PER_SOLDIER = 0.1
    const val GOLD_PER_SOLDIER = 0.05
    const val GOLD_PER_CHUNK = 0.02
    const val GROWTH_RATE = 0.03
    const val STARVATION_LOSS = 0.05

    const val FACILITY_BONUS = 0.2
    const val SCIENCE_PER_POP = 0.05
    const val SCIENCE_PER_LIBRARY = 1.0

    const val BASE_STABILITY = 70.0
    const val OVEREXTENSION_BASE = 30
    const val OVEREXTENSION_PER_ADMIN = 5

    const val RECRUIT_CAP_RATIO = 0.3
    const val TRUCE_TURNS = 30L
    const val PROPOSAL_TTL = 20L
    const val TRADE_RANGE = 6
    const val ENVOY_COST = 15.0
    const val ENVOY_INFLUENCE = 15.0
    const val MAX_EVENTS = 300

    fun startStock(): MutableMap<Resource, Double> = mutableMapOf(
        Resource.FOOD to 100.0,
        Resource.WOOD to 50.0,
        Resource.STONE to 30.0,
        Resource.IRON to 10.0,
        Resource.GOLD to 20.0
    )

    fun techCost(level: Int): Double = 40.0 * (level + 1.0).pow(1.6)

    fun claimCost(nation: Nation): Map<Resource, Double> {
        var factor = sqrt(nation.claims.size.coerceAtLeast(1).toDouble()) / 2.0 + 0.5
        factor *= (1.0 - 0.08 * nation.level(TechBranch.LOGISTICS)).coerceAtLeast(0.4)
        if (nation.policy == Policy.EXPANSION) factor *= 0.75
        return mapOf(
            Resource.GOLD to 3.0 * factor,
            Resource.WOOD to 6.0 * factor,
            Resource.STONE to 3.0 * factor
        )
    }

    fun facilityCost(nation: Nation, facility: Facility): Map<Resource, Double> {
        var factor = 1.0 + 0.3 * nation.count(facility)
        factor *= (1.0 - 0.08 * nation.level(TechBranch.CONSTRUCTION)).coerceAtLeast(0.4)
        val base = when (facility) {
            Facility.FARM -> mapOf(Resource.WOOD to 20.0, Resource.STONE to 5.0, Resource.GOLD to 5.0)
            Facility.LUMBER_CAMP -> mapOf(Resource.WOOD to 10.0, Resource.STONE to 10.0, Resource.IRON to 2.0, Resource.GOLD to 5.0)
            Facility.QUARRY -> mapOf(Resource.WOOD to 20.0, Resource.IRON to 3.0, Resource.GOLD to 5.0)
            Facility.MINE -> mapOf(Resource.WOOD to 25.0, Resource.STONE to 15.0, Resource.GOLD to 8.0)
            Facility.MARKET -> mapOf(Resource.WOOD to 20.0, Resource.STONE to 20.0, Resource.GOLD to 10.0)
            Facility.LIBRARY -> mapOf(Resource.WOOD to 30.0, Resource.STONE to 10.0, Resource.GOLD to 15.0)
            Facility.BARRACKS -> mapOf(Resource.WOOD to 20.0, Resource.STONE to 20.0, Resource.IRON to 10.0, Resource.GOLD to 10.0)
            Facility.WALLS -> mapOf(Resource.STONE to 40.0, Resource.IRON to 5.0, Resource.GOLD to 5.0)
        }
        return base.mapValues { it.value * factor }
    }

    fun recruitCost(nation: Nation, amount: Int): Map<Resource, Double> {
        var factor = (1.0 - 0.05 * nation.count(Facility.BARRACKS)).coerceAtLeast(0.6)
        factor *= when (nation.policy) {
            Policy.MILITARY -> 0.75
            Policy.ECONOMY -> 1.25
            else -> 1.0
        }
        return mapOf(
            Resource.FOOD to 2.0 * amount * factor,
            Resource.IRON to 1.0 * amount * factor,
            Resource.GOLD to 1.0 * amount * factor
        )
    }

    fun storageCap(nation: Nation): Double = 300.0 + 40.0 * nation.claims.size

    fun overextensionLimit(nation: Nation): Int =
        OVEREXTENSION_BASE + OVEREXTENSION_PER_ADMIN * nation.level(TechBranch.ADMINISTRATION)

    fun recruitCap(nation: Nation): Double = nation.population * RECRUIT_CAP_RATIO
}
