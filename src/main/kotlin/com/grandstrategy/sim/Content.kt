package com.grandstrategy.sim

import kotlinx.serialization.Serializable

/**
 * What a nation must have reached for something to unlock: an era, research levels per branch, named technologies.
 * Everything is optional; an empty requirement is always met.
 */
@Serializable
data class Requirement(
    val era: String? = null,
    val branches: Map<TechBranch, Int> = emptyMap(),
    val techs: List<String> = emptyList()
)

/** An age of history. Reached when the nation's research levels add up to [techTotal]. Roads are paved with its blocks. */
@Serializable
data class EraDef(
    val id: String,
    val name: String,
    val techTotal: Int,
    val description: String = "",
    /** Block ids for road paving: the first is the main surface, the rest are mixed in for wear. */
    val road: List<String> = listOf("minecraft:dirt_path"),
    val plaza: String? = null,
    val bridge: String = "minecraft:oak_planks"
)

/** A named milestone on the research tracks (the wheel, gunpowder, fission...). Other content can require it by id. */
@Serializable
data class TechDef(
    val id: String,
    val name: String,
    val requires: Requirement = Requirement(),
    val description: String = ""
)

/** Game effect of a weapon of mass destruction. Only game mechanics; nothing about real-world manufacture. */
@Serializable
data class WmdDef(
    val kind: String,
    /** Chunks contaminated around the target. */
    val radius: Int = 1,
    /** Share of the target region's population lost. */
    val populationLoss: Double = 0.3,
    /** Opinion change of every other nation toward the user. */
    val opinion: Double = -60.0,
    /** Turns the contamination lasts. */
    val fallout: Int = 50,
    val description: String = ""
)

@Serializable
data class WeaponDef(
    val id: String,
    val name: String,
    val requires: Requirement = Requirement(),
    /** Multiplier on the nation's fighting strength while this is its best conventional weapon. */
    val attack: Double = 1.0,
    val upkeep: Map<Resource, Double> = emptyMap(),
    /** Set for weapons of mass destruction; they never count as the conventional best weapon. */
    val wmd: WmdDef? = null,
    val description: String = ""
)

@Serializable
data class EnergyDef(
    val id: String,
    val name: String,
    val requires: Requirement = Requirement(),
    /** Multiplier on all production while this is the nation's best source. */
    val output: Double = 1.0,
    /** Stability lost to pollution (points). */
    val pollution: Double = 0.0,
    val description: String = ""
)

@Serializable
data class IdeologyDef(
    val id: String,
    val name: String,
    val requires: Requirement = Requirement(),
    /** Flat stability points. */
    val stability: Double = 0.0,
    /** Fractions added to growth, science, military strength and production (0.1 = +10%). */
    val growth: Double = 0.0,
    val science: Double = 0.0,
    val military: Double = 0.0,
    val production: Double = 0.0,
    val description: String = ""
)

/**
 * Something that can happen to an overcrowded nation. Rolled each turn with a chance that grows with overcrowding;
 * [minRatio] is the population/capacity ratio from which it can happen. Text placeholders: {nation} {lost} {moved} {target}.
 */
@Serializable
data class CrowdEventDef(
    val id: String,
    val name: String,
    val weight: Double = 1.0,
    val minRatio: Double = 1.0,
    val populationLoss: Double = 0.0,
    val stability: Double = 0.0,
    val stockLoss: Map<Resource, Double> = emptyMap(),
    val emigration: Double = 0.0,
    val productionPenalty: Double = 0.0,
    val penaltyTurns: Int = 0,
    /** Splits off a rebel nation; only rolls while stability is below 25. */
    val revolt: Boolean = false,
    /** Announced to everyone instead of only the nation. */
    val major: Boolean = false,
    val text: String = "{nation}: 과밀"
)

@Serializable
data class ContentConfig(
    val eras: List<EraDef> = Defaults.eras,
    val techs: List<TechDef> = Defaults.techs,
    val weapons: List<WeaponDef> = Defaults.weapons,
    val energy: List<EnergyDef> = Defaults.energy,
    val ideologies: List<IdeologyDef> = Defaults.ideologies,
    val crowdEvents: List<CrowdEventDef> = Defaults.crowdEvents
) {
    /** Mistakes that would make the content unusable; an empty list means it can be applied. */
    fun problems(): List<String> {
        val out = ArrayList<String>()
        if (eras.isEmpty()) out += "시대가 하나도 없다"
        val blank = (eras.map { it.id to it.name } + techs.map { it.id to it.name } + weapons.map { it.id to it.name } +
            energy.map { it.id to it.name } + ideologies.map { it.id to it.name } + crowdEvents.map { it.id to it.name }).filter { (_, name) -> name.isBlank() || name.any { it.isISOControl() } }
        if (blank.isNotEmpty()) out += "이름이 비었다: ${blank.joinToString { it.first }}"
        if (eras.zipWithNext().any { (a, b) -> b.techTotal < a.techTotal }) out += "시대는 techTotal 오름차순이어야 한다"
        val eraIds = eras.map { it.id }.toSet()
        val techIds = techs.map { it.id }.toSet()
        for ((label, ids) in listOf("시대" to eras.map { it.id }, "기술" to techs.map { it.id }, "무기" to weapons.map { it.id },
            "에너지" to energy.map { it.id }, "사상" to ideologies.map { it.id })) {
            val dup = ids.groupBy { it }.filter { it.value.size > 1 }.keys
            if (dup.isNotEmpty()) out += "$label id 중복: ${dup.joinToString()}"
        }
        val reqs = techs.map { it.id to it.requires } + weapons.map { it.id to it.requires } + energy.map { it.id to it.requires } +
            ideologies.map { it.id to it.requires }
        for ((id, r) in reqs) {
            if (r.era != null && r.era !in eraIds) out += "$id: 없는 시대 ${r.era}"
            for (t in r.techs) if (t !in techIds) out += "$id: 없는 기술 $t"
            if (r.branches.values.any { it < 0 }) out += "$id: 음수 단계"
        }
        if (weapons.none { it.wmd == null && met(it.requires, emptyNation) }) out += "처음부터 쓸 수 있는 재래식 무기가 없다"
        if (energy.none { met(it.requires, emptyNation) }) out += "처음부터 쓸 수 있는 에너지가 없다"
        if (weapons.any { it.attack <= 0 } || energy.any { it.output <= 0 }) out += "attack·output은 0보다 커야 한다"
        if (crowdEvents.any { it.weight < 0 || it.populationLoss !in 0.0..1.0 || it.emigration !in 0.0..1.0 || it.productionPenalty !in 0.0..1.0 }) {
            out += "과밀 사건 값은 weight ≥ 0, 비율은 0~1이어야 한다"
        }
        return out
    }

    private val emptyNation get() = Nation(0, "", 0, 0, personality = Personality(0.5, 0.5, 0.5))

    fun techTotal(nation: Nation): Int = nation.tech.values.sum()

    fun era(nation: Nation): EraDef {
        val total = techTotal(nation)
        return eras.lastOrNull { total >= it.techTotal } ?: eras.first()
    }

    fun eraIndex(nation: Nation): Int = eras.indexOf(era(nation)).coerceAtLeast(0)

    fun hasTech(nation: Nation, id: String): Boolean = techs.firstOrNull { it.id == id }?.let { met(it.requires, nation) } ?: false

    fun met(r: Requirement, nation: Nation): Boolean {
        if (r.era != null) {
            val need = eras.indexOfFirst { it.id == r.era }
            if (need >= 0 && eraIndex(nation) < need) return false
        }
        for ((branch, level) in r.branches) if (nation.level(branch) < level) return false
        for (t in r.techs) if (!hasTech(nation, t)) return false
        return true
    }

    fun weapon(nation: Nation): WeaponDef = weapons.filter { it.wmd == null && met(it.requires, nation) }.maxByOrNull { it.attack }
        ?: weapons.first()

    fun wmds(nation: Nation): List<WeaponDef> = weapons.filter { it.wmd != null && met(it.requires, nation) }

    fun energySource(nation: Nation): EnergyDef = energy.filter { met(it.requires, nation) }.maxByOrNull { it.output } ?: energy.first()

    fun ideologyOf(nation: Nation): IdeologyDef? = nation.ideology?.let { id -> ideologies.firstOrNull { it.id == id } }

    fun unlockedIdeologies(nation: Nation): List<IdeologyDef> = ideologies.filter { met(it.requires, nation) }
}

/** The loaded content, like [Balance.config]: swapped atomically by `/gs admin reload`. */
object Content {
    var config: ContentConfig = ContentConfig()

    fun productionFactor(nation: Nation): Double = config.energySource(nation).output * (1.0 + (config.ideologyOf(nation)?.production ?: 0.0))
    fun scienceFactor(nation: Nation): Double = 1.0 + (config.ideologyOf(nation)?.science ?: 0.0)
    fun growthFactor(nation: Nation): Double = 1.0 + (config.ideologyOf(nation)?.growth ?: 0.0)
    fun militaryFactor(nation: Nation): Double = config.weapon(nation).attack * (1.0 + (config.ideologyOf(nation)?.military ?: 0.0))
    fun stabilityBonus(nation: Nation): Double = (config.ideologyOf(nation)?.stability ?: 0.0) - config.energySource(nation).pollution

    /**
     * AI ideology choice: the unlocked ideology whose bonuses best fit the nation's personality and posture.
     * Returns null when nothing is unlocked.
     */
    fun preferredIdeology(nation: Nation): IdeologyDef? = config.unlockedIdeologies(nation).maxByOrNull { d ->
        val p = nation.personality
        d.stability / 20.0 * (1.0 - p.aggression) + d.military * (0.5 + p.aggression) + d.production * (0.5 + p.expansionism) +
            d.growth * (0.5 + p.expansionism) + d.science * (0.5 + p.diplomacy) +
            // Newer ideas look attractive: break ties toward the most recent era.
            config.eras.indexOfFirst { it.id == d.requires.era }.coerceAtLeast(0) * 0.01
    }
}

/** Built-in content. Written to config/grandstrategy/content.json on first run; edit that file and `/gs admin reload`. */
object Defaults {
    val eras = listOf(
        EraDef("stone", "석기", 0, "돌과 나무, 불", listOf("minecraft:dirt_path"), "minecraft:coarse_dirt", "minecraft:oak_planks"),
        EraDef("bronze", "청동기", 4, "금속을 녹이고 도시가 생긴다", listOf("minecraft:dirt_path", "minecraft:coarse_dirt"), "minecraft:packed_mud", "minecraft:oak_planks"),
        EraDef("iron", "철기", 9, "철 도구와 철기 군대", listOf("minecraft:packed_mud", "minecraft:dirt_path"), "minecraft:mud_bricks", "minecraft:spruce_planks"),
        EraDef("classical", "고전", 16, "법과 철학, 포장도로", listOf("minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:dirt_path"), "minecraft:stone_bricks", "minecraft:spruce_planks"),
        EraDef("medieval", "중세", 25, "성과 길드", listOf("minecraft:cobblestone", "minecraft:stone_bricks", "minecraft:mossy_cobblestone"), "minecraft:stone_bricks", "minecraft:stone_bricks"),
        EraDef("renaissance", "르네상스", 36, "인쇄술과 화약", listOf("minecraft:stone_bricks", "minecraft:cracked_stone_bricks", "minecraft:mossy_stone_bricks"), "minecraft:polished_andesite", "minecraft:stone_bricks"),
        EraDef("industrial", "산업", 49, "증기와 석탄, 공장", listOf("minecraft:polished_andesite", "minecraft:stone_bricks", "minecraft:andesite"), "minecraft:polished_andesite", "minecraft:smooth_stone"),
        EraDef("modern", "근대", 64, "전기와 내연기관", listOf("minecraft:gray_concrete", "minecraft:light_gray_concrete"), "minecraft:light_gray_concrete", "minecraft:smooth_stone"),
        EraDef("atomic", "원자력", 81, "원자의 힘", listOf("minecraft:gray_concrete", "minecraft:black_concrete"), "minecraft:white_concrete", "minecraft:smooth_stone"),
        EraDef("information", "정보", 100, "컴퓨터와 네트워크", listOf("minecraft:black_concrete", "minecraft:gray_concrete"), "minecraft:light_gray_concrete", "minecraft:polished_deepslate"),
        EraDef("near_future", "근미래", 121, "자동화와 우주 진출", listOf("minecraft:polished_deepslate", "minecraft:deepslate_tiles"), "minecraft:smooth_quartz", "minecraft:polished_deepslate"),
        EraDef("far_future", "원미래", 144, "물질을 마음대로 다룬다", listOf("minecraft:smooth_quartz", "minecraft:quartz_bricks"), "minecraft:quartz_bricks", "minecraft:smooth_quartz"),
        EraDef("stellar", "항성", 169, "별의 에너지를 쓰는 문명", listOf("minecraft:purpur_block", "minecraft:smooth_quartz"), "minecraft:purpur_pillar", "minecraft:purpur_block")
    )

    private fun r(era: String? = null, vararg branches: Pair<TechBranch, Int>, techs: List<String> = emptyList()) =
        Requirement(era, branches.toMap(), techs)

    val techs = listOf(
        TechDef("agriculture", "농경", r(null, TechBranch.AGRICULTURE to 1)),
        TechDef("wheel", "바퀴", r(null, TechBranch.LOGISTICS to 1)),
        TechDef("writing", "문자", r(null, TechBranch.EDUCATION to 1)),
        TechDef("bronze_working", "청동 야금", r(null, TechBranch.MINING to 1)),
        TechDef("masonry", "석조 건축", r(null, TechBranch.CONSTRUCTION to 1)),
        TechDef("iron_working", "철 야금", r("bronze", TechBranch.MINING to 2)),
        TechDef("law", "법전", r("bronze", TechBranch.ADMINISTRATION to 2)),
        TechDef("engineering", "공학", r("classical", TechBranch.CONSTRUCTION to 3)),
        TechDef("philosophy", "철학", r("classical", TechBranch.EDUCATION to 3)),
        TechDef("feudal_order", "봉건 질서", r("medieval", TechBranch.ADMINISTRATION to 3, TechBranch.MILITARY to 2)),
        TechDef("printing", "인쇄술", r("renaissance", TechBranch.EDUCATION to 4)),
        TechDef("gunpowder", "화약", r("renaissance", TechBranch.MILITARY to 4, TechBranch.MINING to 3)),
        TechDef("steam", "증기 기관", r("industrial", TechBranch.INDUSTRY to 4)),
        TechDef("railroad", "철도", r("industrial", TechBranch.LOGISTICS to 4, techs = listOf("steam"))),
        TechDef("electricity", "전기", r("modern", TechBranch.INDUSTRY to 6)),
        TechDef("combustion", "내연기관", r("modern", TechBranch.INDUSTRY to 6, TechBranch.LOGISTICS to 5)),
        TechDef("fission", "핵분열", r("atomic", TechBranch.INDUSTRY to 7, TechBranch.EDUCATION to 6)),
        TechDef("computers", "컴퓨터", r("information", TechBranch.EDUCATION to 8)),
        TechDef("robotics", "로봇 공학", r("near_future", TechBranch.INDUSTRY to 9, techs = listOf("computers"))),
        TechDef("fusion", "핵융합", r("near_future", TechBranch.INDUSTRY to 10, TechBranch.EDUCATION to 9, techs = listOf("fission"))),
        TechDef("nanotech", "나노 기술", r("far_future", TechBranch.INDUSTRY to 11, techs = listOf("robotics"))),
        TechDef("stellar_engineering", "항성 공학", r("stellar", TechBranch.CONSTRUCTION to 12, techs = listOf("fusion")))
    )

    val weapons = listOf(
        WeaponDef("club_spear", "곤봉·창", r(), 1.0, description = "돌과 나무로 만든 무기"),
        WeaponDef("bronze_arms", "청동 검·활", r("bronze", TechBranch.MILITARY to 1, techs = listOf("bronze_working")), 1.3),
        WeaponDef("iron_arms", "철검·장궁", r("iron", TechBranch.MILITARY to 2, techs = listOf("iron_working")), 1.6),
        WeaponDef("siege_engines", "공성 병기", r("classical", TechBranch.MILITARY to 3, techs = listOf("engineering")), 2.0),
        WeaponDef("crossbow_plate", "석궁·판금 갑옷", r("medieval", TechBranch.MILITARY to 3), 2.4),
        WeaponDef("cannon", "화약·대포", r("renaissance", techs = listOf("gunpowder")), 3.2),
        WeaponDef("rifles", "소총·기관총", r("industrial", TechBranch.MILITARY to 5), 4.5),
        WeaponDef("armor_air", "전차·항공기", r("modern", TechBranch.MILITARY to 7, techs = listOf("combustion")), 6.5),
        WeaponDef("guided_missiles", "유도 미사일", r("information", TechBranch.MILITARY to 9, techs = listOf("computers")), 8.5),
        WeaponDef("railgun", "레일건·지향성 에너지", r("near_future", TechBranch.MILITARY to 10), 11.0),
        WeaponDef("swarm", "자율 무기 군집", r("far_future", TechBranch.MILITARY to 12, techs = listOf("robotics")), 16.0),
        WeaponDef("antimatter", "반물질 무기", r("stellar", TechBranch.MILITARY to 14), 24.0,
            wmd = WmdDef("antimatter", radius = 4, populationLoss = 0.9, opinion = -100.0, fallout = 10, description = "도시 하나를 지운다")),
        WeaponDef("chemical", "화학 무기", r("industrial", TechBranch.MILITARY to 6, TechBranch.INDUSTRY to 5), 4.8,
            wmd = WmdDef("chemical", radius = 1, populationLoss = 0.35, opinion = -50.0, fallout = 20, description = "청크를 오염시키고 인구를 줄인다")),
        WeaponDef("fission_bomb", "핵분열탄", r("atomic", techs = listOf("fission")), 7.0,
            wmd = WmdDef("nuclear", radius = 2, populationLoss = 0.7, opinion = -80.0, fallout = 80, description = "도시를 파괴하고 방사능 오염을 남긴다")),
        WeaponDef("thermonuclear", "수소폭탄", r("atomic", TechBranch.MILITARY to 9, techs = listOf("fission")), 8.0,
            wmd = WmdDef("thermonuclear", radius = 4, populationLoss = 0.85, opinion = -95.0, fallout = 120, description = "넓은 지역을 파괴하고 핵겨울 위험을 부른다")),
        WeaponDef("biological", "생물 무기", r("modern", TechBranch.EDUCATION to 7, TechBranch.MILITARY to 7), 6.8,
            wmd = WmdDef("biological", radius = 3, populationLoss = 0.5, opinion = -90.0, fallout = 40, description = "전염병처럼 퍼져 이웃에게도 번질 수 있다")),
        WeaponDef("nano_plague", "나노 무기", r("far_future", techs = listOf("nanotech")), 16.0,
            wmd = WmdDef("nano", radius = 3, populationLoss = 0.8, opinion = -100.0, fallout = 60, description = "지형까지 갉아먹는 자기 복제 무기"))
    )

    val energy = listOf(
        EnergyDef("muscle", "근력·가축", r(), 1.0),
        EnergyDef("firewood", "장작", r("bronze"), 1.05),
        EnergyDef("mills", "수차·풍차", r("medieval", TechBranch.INDUSTRY to 2), 1.15),
        EnergyDef("coal", "석탄", r("industrial", techs = listOf("steam")), 1.3, pollution = 3.0),
        EnergyDef("oil", "석유", r("modern", techs = listOf("combustion")), 1.45, pollution = 4.0),
        EnergyDef("hydro", "수력", r("modern", techs = listOf("electricity")), 1.5, pollution = 0.5),
        EnergyDef("fission_power", "핵분열 발전", r("atomic", techs = listOf("fission")), 1.8, pollution = 1.0),
        EnergyDef("solar_wind", "태양광·풍력", r("information", TechBranch.INDUSTRY to 8), 2.0),
        EnergyDef("fusion_power", "핵융합 발전", r("near_future", techs = listOf("fusion")), 2.6),
        EnergyDef("artificial_sun", "인공 태양", r("far_future", techs = listOf("fusion")), 3.2),
        EnergyDef("dyson", "다이슨 구조물", r("stellar", techs = listOf("stellar_engineering")), 4.5)
    )

    val crowdEvents = listOf(
        CrowdEventDef("plague", "전염병", weight = 2.0, minRatio = 1.1, populationLoss = 0.1, stability = -8.0, major = true,
            text = "{nation}의 비좁은 거리에 전염병이 돌아 {lost}명이 죽었다"),
        CrowdEventDef("food_riot", "식량 폭동", weight = 3.0, minRatio = 1.0, stability = -10.0, stockLoss = mapOf(Resource.FOOD to 0.35),
            text = "{nation}: 먹을 것이 모자라 식량 창고가 약탈당했다"),
        CrowdEventDef("slum_fire", "빈민가 화재", weight = 1.5, minRatio = 1.15, populationLoss = 0.03, stability = -5.0,
            stockLoss = mapOf(Resource.WOOD to 0.25), text = "{nation}의 빈민가에 큰불이 나 {lost}명이 죽고 목재가 타 버렸다"),
        CrowdEventDef("crime", "범죄 증가", weight = 3.0, minRatio = 1.0, stability = -6.0, stockLoss = mapOf(Resource.GOLD to 0.2),
            text = "{nation}: 일자리 없는 사람이 늘어 도둑과 강도가 들끓는다"),
        CrowdEventDef("strike", "파업", weight = 2.0, minRatio = 1.05, stability = -4.0, productionPenalty = 0.25, penaltyTurns = 5,
            text = "{nation}의 노동자들이 일손을 놓았다 (5턴 동안 생산 -25%)"),
        CrowdEventDef("emigration", "대규모 이주", weight = 2.0, minRatio = 1.2, stability = -2.0, emigration = 0.08,
            text = "{nation}에서 {moved}명이 살 곳을 찾아 {target}(으)로 떠났다"),
        CrowdEventDef("revolt", "폭동과 분리", weight = 1.0, minRatio = 1.35, stability = -15.0, revolt = true, major = true,
            text = "{nation}의 과밀한 변방이 들고일어났다")
    )

    val ideologies = listOf(
        IdeologyDef("animism", "정령신앙", r(), stability = 5.0, description = "자연의 정령을 섬긴다"),
        IdeologyDef("tribalism", "부족주의", r(), military = 0.05, description = "핏줄과 무리를 앞세운다"),
        IdeologyDef("theocracy", "신권 정치", r("bronze", techs = listOf("writing")), stability = 10.0, science = -0.05),
        IdeologyDef("monarchy", "군주제", r("iron", techs = listOf("law")), stability = 6.0, military = 0.05),
        IdeologyDef("republic", "공화주의", r("classical", techs = listOf("philosophy")), science = 0.1, growth = 0.03),
        IdeologyDef("feudalism", "봉건제", r("medieval", techs = listOf("feudal_order")), military = 0.1, growth = -0.05),
        IdeologyDef("liberalism", "자유주의", r("industrial", techs = listOf("printing")), growth = 0.1, science = 0.1, stability = -5.0),
        IdeologyDef("socialism", "사회주의", r("industrial", techs = listOf("printing")), stability = 5.0, growth = 0.05, production = -0.05),
        IdeologyDef("communism", "공산주의", r("modern"), production = 0.1, science = -0.05, stability = -3.0),
        IdeologyDef("fascism", "파시즘", r("modern"), military = 0.2, stability = -8.0, growth = -0.05),
        IdeologyDef("technocracy", "기술관료제", r("information", techs = listOf("computers")), science = 0.25, stability = -4.0),
        IdeologyDef("transhumanism", "트랜스휴머니즘", r("near_future", techs = listOf("robotics")), growth = 0.15, science = 0.15),
        IdeologyDef("ai_governance", "AI 통치", r("far_future", techs = listOf("nanotech")), production = 0.2, stability = 10.0, growth = -0.1)
    )
}
