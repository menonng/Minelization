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

enum class TechBranch(val label: String, val effect: String) {
    AGRICULTURE("농업", "식량 +10%"),
    MINING("광업", "석재·철·금 +10%"),
    INDUSTRY("산업", "목재 +5%, 시설 효율 +5%"),
    LOGISTICS("물류", "확장 비용 -8%, 보급 +5%"),
    ADMINISTRATION("행정", "안정도 +3, 과확장 한도 +5"),
    EDUCATION("교육", "연구 속도 +15%"),
    MILITARY("군사", "전투력 +10%"),
    CONSTRUCTION("건설", "시설 비용 -8%, 성벽 +5%")
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
    var markerPlaced: Boolean = false
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
    val events: MutableList<GameEvent> = mutableListOf()
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
