package com.grandstrategy.sim

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.sqrt

@Serializable
data class Yields(
    val food: Double = 0.0,
    val wood: Double = 0.0,
    val stone: Double = 0.0,
    val iron: Double = 0.0,
    val gold: Double = 0.0
) {
    fun of(resource: Resource): Double = when (resource) {
        Resource.FOOD -> food
        Resource.WOOD -> wood
        Resource.STONE -> stone
        Resource.IRON -> iron
        Resource.GOLD -> gold
    }
}

@Serializable
data class AiTuning(
    val economicActionsPerTurn: Int = 1,
    val economicThreshold: Double = 0.25,
    val strategicActionsPerTurn: Int = 1,
    val strategicThreshold: Double = 0.45,
    val warPowerRatioGreedy: Double = 1.3,
    val warPowerRatioHated: Double = 1.0,
    val warHatredOpinion: Double = -25.0,
    val warCooldownTurns: Long = 20,
    val proposalCooldownTurns: Long = 15,
    val policyCooldownTurns: Long = 10
)

@Serializable
data class AgentTuning(
    val perPopulation: Double = 1.0,
    val max: Int = 30000,
    val spawnPerTurn: Int = 800,
    /** Radius in which villagers are walked by A* (near tier). */
    val visibleRadius: Int = 48,
    /** Most A*-walked villagers per player. */
    val maxVisiblePerPlayer: Int = 150,
    /** Villagers are shown in every chunk the player sees, up to this many chunks out (also capped by view distance). */
    val displayChunks: Int = 16,
    val maxShownPerPlayer: Int = 4000,
    /** Ticks between moves sent for far villagers, and for ones past 96 blocks. */
    val farUpdateTicks: Int = 4,
    val distantUpdateTicks: Int = 12,
    /** New villagers spawned on a player's screen per view update (every 10 ticks), nearest first. */
    val spawnsPerUpdate: Int = 600
)

@Serializable
data class RoadTuning(
    /** Ticks between road planning attempts; each attempt serves one nation, round robin. */
    val planIntervalTicks: Int = 60,
    val maxPendingPerNation: Int = 2,
    val streetRange: Int = 72,
    val highwayRange: Int = 200,
    val streetNodes: Int = 12000,
    val highwayNodes: Int = 40000,
    /** Construction tech levels where paving moves from dirt path to cobble, stone brick and concrete. */
    val tierLevels: List<Int> = listOf(2, 4, 7),
    val workers: Int = 4
)

@Serializable
data class BalanceConfig(
    val startPopulation: Double = 30.0,
    val startMilitary: Double = 10.0,
    val startRadius: Int = 1,
    val startStock: Map<Resource, Double> = mapOf(
        Resource.FOOD to 100.0, Resource.WOOD to 50.0, Resource.STONE to 30.0, Resource.IRON to 10.0, Resource.GOLD to 20.0
    ),

    val workersPerChunk: Double = 3.0,
    val housingPerChunk: Double = 8.0,
    val housingPerFacility: Double = 2.0,
    val housingPerHouse: Double = 10.0,
    val houseCost: Map<Resource, Double> = mapOf(Resource.WOOD to 24.0, Resource.STONE to 8.0, Resource.GOLD to 2.0),
    val workRatePerWorker: Double = 0.6,
    val workBlocksPerTick: Int = 160,
    val taxPerPop: Double = 0.02,
    val foodPerPop: Double = 0.1,
    val foodPerSoldier: Double = 0.1,
    val goldPerSoldier: Double = 0.05,
    val goldPerChunk: Double = 0.02,
    val growthRate: Double = 0.03,
    val starvationLoss: Double = 0.05,
    val storageBase: Double = 300.0,
    val storagePerChunk: Double = 40.0,

    val facilityBonus: Double = 0.2,
    val sciencePerPop: Double = 0.05,
    val sciencePerLibrary: Double = 1.0,
    val techCostBase: Double = 40.0,
    val techCostExponent: Double = 1.6,

    val baseStability: Double = 70.0,
    val overextensionBase: Int = 30,
    val overextensionPerAdmin: Int = 5,

    val recruitCapRatio: Double = 0.3,
    val truceTurns: Long = 30,
    val proposalTtl: Long = 20,
    val tradeRange: Int = 6,
    val envoyCost: Double = 15.0,
    val envoyInfluence: Double = 15.0,
    val maxEvents: Int = 300,

    val claimCost: Map<Resource, Double> = mapOf(Resource.GOLD to 3.0, Resource.WOOD to 6.0, Resource.STONE to 3.0),
    val recruitCostPerSoldier: Map<Resource, Double> = mapOf(Resource.FOOD to 2.0, Resource.IRON to 1.0, Resource.GOLD to 1.0),
    val facilityCost: Map<Facility, Map<Resource, Double>> = mapOf(
        Facility.FARM to mapOf(Resource.WOOD to 20.0, Resource.STONE to 5.0, Resource.GOLD to 5.0),
        Facility.LUMBER_CAMP to mapOf(Resource.WOOD to 10.0, Resource.STONE to 10.0, Resource.IRON to 2.0, Resource.GOLD to 5.0),
        Facility.QUARRY to mapOf(Resource.WOOD to 20.0, Resource.IRON to 3.0, Resource.GOLD to 5.0),
        Facility.MINE to mapOf(Resource.WOOD to 25.0, Resource.STONE to 15.0, Resource.GOLD to 8.0),
        Facility.MARKET to mapOf(Resource.WOOD to 20.0, Resource.STONE to 20.0, Resource.GOLD to 10.0),
        Facility.LIBRARY to mapOf(Resource.WOOD to 30.0, Resource.STONE to 10.0, Resource.GOLD to 15.0),
        Facility.BARRACKS to mapOf(Resource.WOOD to 20.0, Resource.STONE to 20.0, Resource.IRON to 10.0, Resource.GOLD to 10.0),
        Facility.WALLS to mapOf(Resource.STONE to 40.0, Resource.IRON to 5.0, Resource.GOLD to 5.0)
    ),
    val terrain: Map<Terrain, Yields> = Terrain.entries.associateWith { Yields(it.food, it.wood, it.stone, it.iron, it.gold) },
    val ai: AiTuning = AiTuning(),
    val agents: AgentTuning = AgentTuning(),
    val roads: RoadTuning = RoadTuning()
) {
    fun problems(): List<String> = buildList {
        if (workersPerChunk <= 0) add("workersPerChunk must be > 0")
        if (techCostBase <= 0) add("techCostBase must be > 0")
        if (maxEvents < 10) add("maxEvents must be >= 10")
        if (tradeRange < 0) add("tradeRange must be >= 0")
        if (recruitCapRatio < 0) add("recruitCapRatio must be >= 0")
        if (agents.perPopulation < 0 || agents.max < 0 || agents.spawnPerTurn < 0) add("agents values must be >= 0")
        if (agents.visibleRadius !in 8..128 || agents.maxVisiblePerPlayer !in 0..1000) add("agents.visibleRadius 8..128, agents.maxVisiblePerPlayer 0..1000")
        if (ai.economicActionsPerTurn < 0 || ai.strategicActionsPerTurn < 0) add("ai action counts must be >= 0")
        val numbers = listOf(startPopulation, startMilitary, housingPerChunk, foodPerPop, growthRate, starvationLoss, storageBase)
        if (numbers.any { it.isNaN() || it < 0 }) add("start and economy values must be non-negative numbers")
        if (facilityCost.values.any { cost -> cost.values.any { it < 0 } } || claimCost.values.any { it < 0 }) add("costs must be >= 0")
        if (terrain.values.any { y -> Resource.entries.any { y.of(it) < 0 } }) add("terrain yields must be >= 0")
    }
}

object Balance {
    @Volatile
    var config: BalanceConfig = BalanceConfig()

    private val c get() = config

    val START_POPULATION get() = c.startPopulation
    val START_MILITARY get() = c.startMilitary
    val START_RADIUS get() = c.startRadius

    val WORKERS_PER_CHUNK get() = c.workersPerChunk
    val HOUSING_PER_CHUNK get() = c.housingPerChunk
    val HOUSING_PER_FACILITY get() = c.housingPerFacility
    val TAX_PER_POP get() = c.taxPerPop
    val FOOD_PER_POP get() = c.foodPerPop
    val FOOD_PER_SOLDIER get() = c.foodPerSoldier
    val GOLD_PER_SOLDIER get() = c.goldPerSoldier
    val GOLD_PER_CHUNK get() = c.goldPerChunk
    val GROWTH_RATE get() = c.growthRate
    val STARVATION_LOSS get() = c.starvationLoss

    val FACILITY_BONUS get() = c.facilityBonus
    val SCIENCE_PER_POP get() = c.sciencePerPop
    val SCIENCE_PER_LIBRARY get() = c.sciencePerLibrary

    val BASE_STABILITY get() = c.baseStability

    val TRUCE_TURNS get() = c.truceTurns
    val PROPOSAL_TTL get() = c.proposalTtl
    val TRADE_RANGE get() = c.tradeRange
    val ENVOY_COST get() = c.envoyCost
    val ENVOY_INFLUENCE get() = c.envoyInfluence
    val MAX_EVENTS get() = c.maxEvents

    val ai: AiTuning get() = c.ai

    fun startStock(): MutableMap<Resource, Double> = Resource.entries.associateWith { c.startStock[it] ?: 0.0 }.toMutableMap()

    fun yield(terrain: Terrain, resource: Resource): Double =
        (c.terrain[terrain] ?: Yields(terrain.food, terrain.wood, terrain.stone, terrain.iron, terrain.gold)).of(resource)

    fun techCost(level: Int): Double = c.techCostBase * (level + 1.0).pow(c.techCostExponent)

    fun claimCost(nation: Nation): Map<Resource, Double> {
        var factor = sqrt(nation.claims.size.coerceAtLeast(1).toDouble()) / 2.0 + 0.5
        factor *= (1.0 - 0.08 * nation.level(TechBranch.LOGISTICS)).coerceAtLeast(0.4)
        if (nation.policy == Policy.EXPANSION) factor *= 0.75
        return c.claimCost.mapValues { it.value * factor }
    }

    fun facilityCost(nation: Nation, facility: Facility): Map<Resource, Double> {
        var factor = 1.0 + 0.3 * nation.count(facility)
        factor *= (1.0 - 0.08 * nation.level(TechBranch.CONSTRUCTION)).coerceAtLeast(0.4)
        return (c.facilityCost[facility] ?: emptyMap()).mapValues { it.value * factor }
    }

    fun recruitCost(nation: Nation, amount: Int): Map<Resource, Double> {
        var factor = (1.0 - 0.05 * nation.count(Facility.BARRACKS)).coerceAtLeast(0.6)
        factor *= when (nation.policy) {
            Policy.MILITARY -> 0.75
            Policy.ECONOMY -> 1.25
            else -> 1.0
        }
        return c.recruitCostPerSoldier.mapValues { it.value * amount * factor }
    }

    fun storageCap(nation: Nation): Double = c.storageBase + c.storagePerChunk * nation.claims.size

    fun overextensionLimit(nation: Nation): Int =
        c.overextensionBase + c.overextensionPerAdmin * nation.level(TechBranch.ADMINISTRATION)

    fun recruitCap(nation: Nation): Double = nation.population * c.recruitCapRatio
}
