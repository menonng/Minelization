package com.grandstrategy.sim

import kotlinx.serialization.Serializable

enum class Resource(val label: String) {
    FOOD("식량"), WOOD("목재"), STONE("석재"), IRON("철"), GOLD("금")
}

enum class Terrain(val label: String, val food: Double, val wood: Double, val stone: Double, val iron: Double, val gold: Double) {
    PLAINS("평원", 2.0, 0.5, 0.5, 0.1, 0.05),
    FOREST("숲", 0.8, 2.5, 0.3, 0.1, 0.02),
    TAIGA("타이가", 0.6, 2.0, 0.6, 0.2, 0.02),
    JUNGLE("정글", 1.2, 2.2, 0.2, 0.05, 0.05),
    SAVANNA("사바나", 1.6, 0.8, 0.5, 0.1, 0.05),
    DESERT("사막", 0.3, 0.0, 1.2, 0.2, 0.25),
    BADLANDS("악지", 0.2, 0.1, 1.2, 0.4, 0.5),
    HILLS("언덕", 0.8, 0.6, 1.5, 0.6, 0.1),
    MOUNTAIN("산", 0.2, 0.3, 2.0, 1.0, 0.2),
    SNOWY("설원", 0.3, 0.6, 1.0, 0.3, 0.05),
    RIVER("강", 1.8, 0.4, 0.3, 0.05, 0.1),
    COAST("해안", 1.4, 0.2, 0.6, 0.05, 0.15),
    WATER("바다", 0.0, 0.0, 0.0, 0.0, 0.0);

    val claimable: Boolean get() = this != WATER

    fun yield(resource: Resource): Double = Balance.yield(this, resource)
}

enum class Facility(val label: String, val boosts: Resource?) {
    FARM("농장", Resource.FOOD),
    LUMBER_CAMP("벌목장", Resource.WOOD),
    QUARRY("채석장", Resource.STONE),
    MINE("광산", Resource.IRON),
    MARKET("시장", Resource.GOLD),
    LIBRARY("도서관", null),
    BARRACKS("병영", null),
    WALLS("성벽", null)
}

enum class BuildingKind(val label: String, val facility: Facility?) {
    HOUSE("주택", null),
    FARM("농장", Facility.FARM),
    LUMBER_CAMP("벌목장", Facility.LUMBER_CAMP),
    QUARRY("채석장", Facility.QUARRY),
    MINE("광산", Facility.MINE),
    MARKET("시장", Facility.MARKET),
    LIBRARY("도서관", Facility.LIBRARY),
    BARRACKS("병영", Facility.BARRACKS),
    WALLS("성벽", Facility.WALLS),
    TEMPLE("신전", null),
    OTHER("기타 건물", null);

    companion object {
        fun of(facility: Facility): BuildingKind = entries.first { it.facility == facility }
    }
}

enum class WorkType(val label: String) { BUILD("건축"), DEMOLISH("철거"), FLATTEN("평탄화"), ROAD("길 닦기"), UNROAD("길 걷어내기") }

enum class RoadKind(val label: String) { PLAZA("광장"), STREET("골목길"), HIGHWAY("간선 도로"), MANUAL("직접 낸 길") }

/** A stretch of road: the block columns (packed with [Chunks.pack]) it covers. Blocks are laid by a ROAD work order. */
@Serializable
class Road(
    val id: Int,
    val nation: Int,
    val kind: RoadKind,
    val cells: MutableList<Long> = mutableListOf(),
    var built: Boolean = false,
    /** Laid three blocks wide; roads from before that are widened once by the road planner. */
    var wide: Boolean = false
)

/**
 * An administrative district: part of a nation's land run from one seat. Every owned chunk belongs to exactly one;
 * how many a district can hold grows with research, and land past that forms a new district (see [Districts]).
 */
@Serializable
class District(
    val id: Int,
    var nation: Int,
    var name: String,
    /** The chunk the district is run from; its town core (plaza) is laid nearby. */
    var seat: Long,
    var size: Int = 0,
    val foundedTurn: Long = 0
)

@Serializable
data class Box(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    val sizeX get() = maxX - minX + 1
    val sizeY get() = maxY - minY + 1
    val sizeZ get() = maxZ - minZ + 1
    val volume get() = sizeX.toLong() * sizeY * sizeZ
    val centerX get() = (minX + maxX) / 2.0 + 0.5
    val centerZ get() = (minZ + maxZ) / 2.0 + 0.5

    fun chunks(): List<Long> {
        val out = ArrayList<Long>()
        for (cx in (minX shr 4)..(maxX shr 4)) for (cz in (minZ shr 4)..(maxZ shr 4)) out += Chunks.pack(cx, cz)
        return out
    }

    fun intersects(o: Box) = minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ

    fun contains(x: Int, y: Int, z: Int) = x in minX..maxX && y in minY..maxY && z in minZ..maxZ

    companion object {
        fun of(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int) =
            Box(minOf(x1, x2), minOf(y1, y2), minOf(z1, z2), maxOf(x1, x2), maxOf(y1, y2), maxOf(z1, z2))
    }
}

@Serializable
class Building(
    val id: Int,
    var nation: Int?,
    var kind: BuildingKind,
    var box: Box,
    var template: String? = null,
    var complete: Boolean = false,
    val detected: Boolean = false,
    /** The road planner has connected (or given up connecting) this building to its nation's streets. */
    var linked: Boolean = false
)

@Serializable
class WorkOrder(
    val id: Int,
    val nation: Int,
    val type: WorkType,
    val box: Box,
    val kind: BuildingKind? = null,
    val template: String? = null,
    val rotation: Int = 0,
    val baseY: Int = Int.MIN_VALUE,
    val conscript: Boolean = false,
    val workersWanted: Int = 8,
    val building: Int? = null,
    val by: String? = null,
    val createdTurn: Long = 0,
    var progress: Long = 0,
    var total: Long = 0,
    var abstractWork: Double = 0.0,
    var pendingPlacement: Boolean = false,
    val road: Int? = null,
    /** UNROAD: the road cells taken out of the network when the order was given. */
    val cells: List<Long> = emptyList(),
    /** BUILD over an existing building (renovation): tear down what is in [box] first. */
    val clear: Boolean = false
)

enum class TechBranch(val label: String, val effect: String) {
    AGRICULTURE("농업", "식량 +10%"),
    MINING("광업", "석재·철·금 +10%"),
    INDUSTRY("산업", "목재 +5%, 시설 효율 +5%"),
    LOGISTICS("물류", "확장 비용 -8%, 보급 +5%, 행정구역 +2청크"),
    ADMINISTRATION("행정", "안정도 +3, 과확장 한도 +5, 행정구역 +4청크"),
    EDUCATION("교육", "연구 속도 +15%"),
    MILITARY("군사", "전투력 +10%"),
    CONSTRUCTION("건설", "시설 비용 -8%, 성벽 +5%"),
    MARINE("수중 구조물", "1단계부터 물 위·물속 건설 (이후 수중 도시)")
}

enum class Policy(val label: String, val description: String) {
    BALANCED("균형", "보정 없음"),
    ECONOMY("경제", "생산 +15%, 징병 비용 +25%"),
    MILITARY("군사", "징병 비용 -25%, 전투력 +10%, 생산 -10%"),
    SCIENCE("학문", "연구 +30%, 금 -10%"),
    EXPANSION("확장", "확장 비용 -25%, 안정도 -5")
}

enum class Treaty(val label: String) {
    NON_AGGRESSION("불가침"), TRADE("교역"), ALLIANCE("동맹"), TRUCE("휴전")
}

enum class Posture(val label: String) {
    GROW("성장"), CONSOLIDATE("안정화"), ARM("군비"), WAR("전쟁"), EXPAND("팽창")
}

@Serializable
data class Personality(
    val aggression: Double,
    val expansionism: Double,
    val diplomacy: Double
)

/** How a nation's next ruler is chosen (set by its ideology, see content.json). */
@Serializable
enum class Succession(val label: String) {
    HEREDITARY("세습"),
    ELECTIVE("선출"),
    APPOINTED("지명")
}

/**
 * The person who rules an AI-run nation (see [Leaders]). Traits run 0..1: aggression, diplomacy, greed and wisdom.
 * [born] and [crowned] are turns; [regnal] is the ordinal among rulers of the same name and house ("3세").
 */
@Serializable
class Leader(
    val name: String,
    val house: String,
    val regnal: Int,
    val born: Long,
    val crowned: Long,
    val aggression: Double,
    val diplomacy: Double,
    val greed: Double,
    val wisdom: Double
)

@Serializable
class Nation(
    val id: Int,
    var name: String,
    var color: Int,
    var capital: Long,
    val claims: MutableSet<Long> = linkedSetOf(),
    var population: Double = Balance.START_POPULATION,
    val stock: MutableMap<Resource, Double> = Balance.startStock(),
    val facilities: MutableMap<Facility, Int> = mutableMapOf(),
    val tech: MutableMap<TechBranch, Int> = mutableMapOf(),
    var researchFocus: TechBranch = TechBranch.AGRICULTURE,
    var researchProgress: Double = 0.0,
    var military: Double = Balance.START_MILITARY,
    var stability: Double = 70.0,
    var policy: Policy = Policy.BALANCED,
    var ruler: String? = null,
    var rulerName: String? = null,
    val citizens: MutableSet<String> = linkedSetOf(),
    var autoGovern: Boolean = false,
    var posture: Posture = Posture.GROW,
    val personality: Personality,
    var alive: Boolean = true,
    var foundedTurn: Long = 0,
    var lastIncome: MutableMap<Resource, Double> = mutableMapOf(),
    var lastScience: Double = 0.0,
    var markerPlaced: Boolean = false,
    /** Where the capital's flag banner stands, so it can be redrawn when the flag changes. */
    var markerPos: Long? = null,
    var flag: Flag? = null,
    /** Chosen ideology id (see content.json), and the era last announced. */
    var ideology: String? = null,
    var era: String? = null,
    var ideologyTurn: Long = 0,
    /** Carrying capacity as of the last turn, and an active production penalty from an overcrowding event. */
    var capacity: Double = 0.0,
    var crowdPenalty: Double = 0.0,
    var crowdPenaltyTurns: Int = 0,
    /** Turn the capital last fell, so a lost capital is announced with a cutscene only once in a while. */
    var capitalFellTurn: Long = -1000,
    /** The ruling character of an AI-run nation (none while a player rules it), and the names of earlier rulers. */
    var leader: Leader? = null,
    val pastLeaders: MutableList<String> = mutableListOf(),
    /** How many rulers of each house and name there have been, for "3세". */
    val regnalCounts: MutableMap<String, Int> = mutableMapOf()
) {
    val aiControlled: Boolean get() = ruler == null || autoGovern

    fun level(branch: TechBranch): Int = tech[branch] ?: 0
    fun count(facility: Facility): Int = facilities[facility] ?: 0
    fun totalFacilities(): Int = facilities.values.sum()
    fun has(resource: Resource): Double = stock[resource] ?: 0.0
    fun add(resource: Resource, amount: Double) {
        stock[resource] = (has(resource) + amount).coerceAtLeast(0.0)
    }
}

@Serializable
class Settlement(
    val id: Int,
    var name: String,
    val center: Long,
    val land: MutableSet<Long> = linkedSetOf(),
    var population: Double = 12.0,
    var golems: Int = 1,
    var owner: Int? = null,
    var loyalty: Double = 100.0,
    val influence: MutableMap<Int, Double> = mutableMapOf(),
    var observed: Boolean = false
) {
    val defense: Double get() = population + golems * 10.0
}

@Serializable
class Relation(
    var opinion: Double = 0.0,
    val treaties: MutableMap<Treaty, Long> = mutableMapOf()
) {
    fun has(treaty: Treaty, turn: Long): Boolean {
        val until = treaties[treaty] ?: return false
        return until < 0 || until > turn
    }
}

@Serializable
class War(
    val id: Int,
    val attacker: Int,
    val defender: Int,
    val attackers: MutableSet<Int> = linkedSetOf(),
    val defenders: MutableSet<Int> = linkedSetOf(),
    val startTurn: Long,
    var score: Double = 0.0,
    var attackerExhaustion: Double = 0.0,
    var defenderExhaustion: Double = 0.0
) {
    fun sideOf(nation: Int): Int = when (nation) {
        in attackers -> 1
        in defenders -> -1
        else -> 0
    }

    fun exhaustionOf(nation: Int): Double = if (sideOf(nation) == 1) attackerExhaustion else defenderExhaustion

    fun scoreFor(nation: Int): Double = score * sideOf(nation)
}

enum class EventKind { INFO, ECONOMY, DIPLOMACY, WAR, MAJOR }

@Serializable
data class GameEvent(
    val turn: Long,
    val kind: EventKind,
    val text: String,
    val nations: List<Int> = emptyList()
)

@Serializable
data class Proposal(
    val id: Int,
    val from: Int,
    val to: Int,
    val type: String,
    val createdTurn: Long
)

@Serializable
class WorldState(
    var seed: Long = 0,
    var turn: Long = 0,
    var nextNationId: Int = 1,
    var nextSettlementId: Int = 1,
    var nextWarId: Int = 1,
    var nextProposalId: Int = 1,
    var seeded: Boolean = false,
    val nations: MutableMap<Int, Nation> = linkedMapOf(),
    val settlements: MutableMap<Int, Settlement> = linkedMapOf(),
    val owners: MutableMap<Long, Int> = mutableMapOf(),
    val settlementLand: MutableMap<Long, Int> = mutableMapOf(),
    val relations: MutableMap<Long, Relation> = mutableMapOf(),
    val wars: MutableMap<Int, War> = linkedMapOf(),
    val proposals: MutableMap<Int, Proposal> = linkedMapOf(),
    var nextBuildingId: Int = 1,
    var nextOrderId: Int = 1,
    val buildings: MutableMap<Int, Building> = linkedMapOf(),
    val orders: MutableMap<Int, WorkOrder> = linkedMapOf(),
    val events: MutableList<GameEvent> = mutableListOf(),
    var nextRoadId: Int = 1,
    val roads: MutableMap<Int, Road> = linkedMapOf(),
    /** Columns a player took road out of; automatic planning never paves them again. */
    val roadBans: MutableSet<Long> = mutableSetOf(),
    var nextDistrictId: Int = 1,
    val districts: MutableMap<Int, District> = linkedMapOf(),
    /** Which district each owned chunk belongs to. */
    val districtOf: MutableMap<Long, Int> = mutableMapOf()
) {
    fun alive(): List<Nation> = nations.values.filter { it.alive }

    fun nation(id: Int?): Nation? = id?.let { nations[it] }?.takeIf { it.alive }

    fun relation(a: Int, b: Int): Relation {
        val key = pairKey(a, b)
        return relations.getOrPut(key) { Relation() }
    }

    fun warBetween(a: Int, b: Int): War? = wars.values.firstOrNull {
        (a in it.attackers && b in it.defenders) || (a in it.defenders && b in it.attackers)
    }

    fun warsOf(id: Int): List<War> = wars.values.filter { id in it.attackers || id in it.defenders }

    fun enemiesOf(id: Int): Set<Int> = warsOf(id).flatMap { if (id in it.attackers) it.defenders else it.attackers }.toSet()

    fun byName(name: String): Nation? = alive().firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun rulerNation(uuid: String): Nation? = alive().firstOrNull { it.ruler == uuid }

    companion object {
        fun pairKey(a: Int, b: Int): Long {
            val lo = minOf(a, b).toLong()
            val hi = maxOf(a, b).toLong()
            return (lo shl 32) or (hi and 0xffffffffL)
        }
    }
}
