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

/** Carrying capacity (see [Population]) and how hard overcrowding bites. */
@Serializable
data class CapacityTuning(
    /** People one chunk of each terrain supports without buildings. */
    val perChunk: Map<Terrain, Double> = mapOf(
        Terrain.PLAINS to 3.0, Terrain.FOREST to 2.0, Terrain.TAIGA to 1.5, Terrain.JUNGLE to 2.0, Terrain.SAVANNA to 2.5,
        Terrain.DESERT to 0.8, Terrain.BADLANDS to 0.5, Terrain.HILLS to 1.5, Terrain.MOUNTAIN to 0.5, Terrain.SNOWY to 1.0,
        Terrain.RIVER to 3.5, Terrain.COAST to 2.5, Terrain.WATER to 0.0
    ),
    val perHouse: Double = 8.0,
    val perFacility: Map<Facility, Double> = mapOf(
        Facility.FARM to 15.0, Facility.LUMBER_CAMP to 4.0, Facility.QUARRY to 4.0, Facility.MINE to 4.0,
        Facility.MARKET to 8.0, Facility.LIBRARY to 4.0, Facility.BARRACKS to 10.0, Facility.WALLS to 2.0
    ),
    val capital: Double = 30.0,
    /** Agriculture and administration levels raise capacity by this much each, up to [techMax] times. */
    val techPerLevel: Double = 0.02,
    val techMax: Double = 1.6,
    /** Extra capacity per era reached. */
    val perEra: Double = 0.05,
    /** Chance per turn of an overcrowding event, per unit of overcrowding (0.4 → 20% when 50% over), capped. */
    val eventRate: Double = 0.4,
    val eventMaxChance: Double = 0.6,
    /** Stability target lost, and production lost (down to the floor), per 100% over capacity. */
    val stabilityPerOver: Double = 25.0,
    val productionPerOver: Double = 0.5,
    val productionFloor: Double = 0.6
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
    val workers: Int = 4,
    /** New houses prefer the town core with the fewest buildings within this many blocks of its plaza. */
    val districtRadius: Int = 48,
    /** Roads are laid this many blocks wide (odd; the planned line runs down the middle). */
    val width: Int = 3
)

/** Rulers of AI-run nations (see [Leaders]). */
@Serializable
data class LeaderTuning(
    /** Turns per year of a ruler's life (a turn is 10 s by default, so 12 makes a year two minutes). */
    val turnsPerYear: Int = 12,
    /** Yearly chance of death: [deathBase] at any age, plus a steep rise past [oldAge]. */
    val deathBase: Double = 0.004,
    val oldAge: Double = 55.0,
    val oldAgeSteepness: Double = 0.03,
    /** Chance the ruler dies when the capital falls. */
    val capitalFallDeath: Double = 0.25,
    /** Chance per turn of a coup while stability is below [coupStability]. */
    val coupChance: Double = 0.004,
    val coupStability: Double = 20.0,
    /** Chance a succession is disputed, by kind, plus the stability shortfall below 50 as a fraction. */
    val disputeHereditary: Double = 0.12,
    val disputeElective: Double = 0.05,
    val disputeAppointed: Double = 0.1,
    /** Nations smaller than this cannot split in a succession war. */
    val disputeMinClaims: Int = 16,
    /** How much the ruler's traits weigh against the nation's own character in AI decisions. */
    val traitWeight: Double = 0.4
)

/** Administrative districts (see [Districts]). */
@Serializable
data class DistrictTuning(
    /** Chunks one district can hold with no research. */
    val baseChunks: Int = 16,
    /** Extra chunks per level of administration and of logistics research. */
    val perAdministration: Double = 4.0,
    val perLogistics: Double = 2.0,
    val maxChunks: Int = 160,
    /** A district gets its own town core (plaza, and a highway to the rest of the roads) once it holds this many chunks. */
    val coreAtChunks: Int = 4
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
    val roads: RoadTuning = RoadTuning(),
    val capacity: CapacityTuning = CapacityTuning(),
    val districts: DistrictTuning = DistrictTuning(),
    val leaders: LeaderTuning = LeaderTuning()
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
        if (roads.districtRadius < 8 || roads.width < 1 || roads.width % 2 == 0) add("roads.districtRadius >= 8, roads.width는 1 이상 홀수")
        if (districts.baseChunks < 1 || districts.maxChunks < districts.baseChunks || districts.coreAtChunks < 1) {
            add("districts.baseChunks >= 1, maxChunks >= baseChunks, coreAtChunks >= 1")
        }
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
