package com.grandstrategy.sim

import kotlin.math.max
import kotlin.math.pow

/**
 * Rulers. Every AI-run nation has a ruling character with four traits (aggression, diplomacy, greed, wisdom) that
 * colour the nation's decisions and economy. Rulers age, die (of old age, or when the capital falls) or are overthrown
 * in a coup when the nation is unstable; the next one is chosen as the nation's ideology says (heir of the same house,
 * an election, or an appointment), and a disputed succession can split the nation in a civil war. A player who takes
 * over a nation becomes its ruler instead.
 */
object Leaders {
    private val tuning get() = Balance.config.leaders

    fun age(sim: Simulation, leader: Leader): Int = ((sim.state.turn - leader.born) / tuning.turnsPerYear.coerceAtLeast(1)).toInt()

    fun reign(sim: Simulation, leader: Leader): Int = ((sim.state.turn - leader.crowned) / tuning.turnsPerYear.coerceAtLeast(1)).toInt()

    private fun ideology(nation: Nation) = Content.config.ideologyOf(nation)

    private fun builtIn(nation: Nation): IdeologyDef? = nation.ideology?.let { id -> Defaults.ideologies.firstOrNull { it.id == id } }

    /** The ruler's title by ideology ("족장" before a nation has one). */
    fun title(nation: Nation): String {
        if (nation.ideology == null) return "족장"
        return ideology(nation)?.title ?: builtIn(nation)?.title ?: "통치자"
    }

    fun succession(nation: Nation): Succession = ideology(nation)?.succession ?: builtIn(nation)?.succession ?: Succession.HEREDITARY

    /** "왕 아르린 3세" for houses that inherit, "대통령 벨라스 아르린" otherwise. */
    fun describe(nation: Nation, leader: Leader? = nation.leader): String? {
        leader ?: return null
        return if (succession(nation) == Succession.HEREDITARY) "${title(nation)} ${leader.name} ${leader.regnal}세"
        else "${title(nation)} ${leader.house} ${leader.name}"
    }

    // ---- Effects ----

    /** The nation's character as its AI acts on it: its own, leaning toward its ruler's traits. */
    fun temper(nation: Nation): Personality {
        val p = nation.personality
        val l = nation.leader ?: return p
        val w = tuning.traitWeight
        return Personality(
            aggression = p.aggression * (1 - w) + l.aggression * w,
            expansionism = p.expansionism * (1 - w) + (l.aggression * 0.5 + l.greed * 0.5) * w,
            diplomacy = p.diplomacy * (1 - w) + l.diplomacy * w
        )
    }

    /** A wise ruler speeds research (up to ±10%). */
    fun scienceFactor(nation: Nation): Double = 1.0 + 0.2 * ((nation.leader?.wisdom ?: 0.5) - 0.5)

    /** Wisdom steadies the nation, greed unsettles it. */
    fun stabilityBonus(nation: Nation): Double {
        val l = nation.leader ?: return 0.0
        return 8.0 * (l.wisdom - 0.5) - 6.0 * (l.greed - 0.5)
    }

    /** A greedy ruler squeezes more gold (up to ±15%). */
    fun productionFactor(nation: Nation, resource: Resource): Double =
        if (resource == Resource.GOLD) 1.0 + 0.3 * ((nation.leader?.greed ?: 0.5) - 0.5) else 1.0

    /** An aggressive ruler drives the army harder (up to ±5%). */
    fun militaryFactor(nation: Nation): Double = 1.0 + 0.1 * ((nation.leader?.aggression ?: 0.5) - 0.5)

    // ---- Life ----

    /** Once per turn: rulers for nations that lack one, ageing and death, coups. */
    fun run(sim: Simulation) {
        val t = tuning
        for (nation in sim.state.alive().toList()) {
            if (!nation.aiControlled) {
                // A player rules: the character steps aside.
                nation.leader?.let { retire(sim, nation, it, "통치자 자리를 넘겨주고 물러났다") }
                continue
            }
            val leader = nation.leader
            if (leader == null) {
                crown(sim, nation, choose(sim, nation, null), announce = sim.state.turn > nation.foundedTurn)
                continue
            }
            val age = age(sim, leader)
            val yearly = t.deathBase + (max(0.0, age - t.oldAge) * t.oldAgeSteepness).pow(2)
            val perTurn = 1.0 - (1.0 - yearly.coerceIn(0.0, 1.0)).pow(1.0 / t.turnsPerYear.coerceAtLeast(1))
            if (sim.rng.nextDouble() < perTurn) {
                die(sim, nation, "${age}세에 세상을 떠났다")
                continue
            }
            if (nation.stability < t.coupStability && sim.rng.nextDouble() < t.coupChance) coup(sim, nation)
        }
    }

    /** The capital fell: the ruler may fall with it. */
    fun onCapitalFall(sim: Simulation, nation: Nation) {
        if (nation.aiControlled && nation.leader != null && sim.rng.nextDouble() < tuning.capitalFallDeath) {
            die(sim, nation, "수도가 함락될 때 목숨을 잃었다")
        }
    }

    /** The ruler is gone: the next one takes over, and the succession may be disputed. */
    fun die(sim: Simulation, nation: Nation, how: String) {
        val old = nation.leader ?: return
        val name = describe(nation, old)
        retire(sim, nation, old, how, log = false)
        val heir = choose(sim, nation, old)
        crown(sim, nation, heir, announce = false)
        sim.log(EventKind.MAJOR, "${nation.name}: $name(재위 ${reign(sim, old)}년)이(가) $how. ${describe(nation)}이(가) 뒤를 이었다", nation.id)
        dispute(sim, nation, old)
    }

    private fun coup(sim: Simulation, nation: Nation) {
        val old = nation.leader ?: return
        val name = describe(nation, old)
        retire(sim, nation, old, "쿠데타로 쫓겨났다", log = false)
        val usurper = create(sim, nation, house = Names.house(sim.rng), parent = null, age = 30 + sim.rng.nextInt(25), aggressionBias = 0.25)
        crown(sim, nation, usurper, announce = false)
        nation.stability = (nation.stability - 10).coerceAtLeast(0.0)
        sim.log(EventKind.MAJOR, "${nation.name}: 쿠데타가 일어나 $name 이(가) 쫓겨나고 ${describe(nation)}이(가) 권력을 잡았다", nation.id)
    }

    /**
     * A contested succession: a pretender (a sibling of the new ruler for an inheriting house, the losing candidate
     * otherwise) takes the far side of the country and goes to war (see [Politics.secede]).
     */
    private fun dispute(sim: Simulation, nation: Nation, old: Leader) {
        val t = tuning
        if (nation.claims.size < t.disputeMinClaims) return
        val base = when (succession(nation)) {
            Succession.HEREDITARY -> t.disputeHereditary
            Succession.ELECTIVE -> t.disputeElective
            Succession.APPOINTED -> t.disputeAppointed
        }
        val chance = base + max(0.0, 50.0 - nation.stability) / 100.0
        if (sim.rng.nextDouble() >= chance) return
        val rebel = Politics.secede(sim, nation) ?: return
        val pretender = if (succession(nation) == Succession.HEREDITARY) {
            create(sim, rebel, house = old.house, parent = old, age = 18 + sim.rng.nextInt(20))
        } else {
            create(sim, rebel, house = Names.house(sim.rng), parent = null, age = 35 + sim.rng.nextInt(20))
        }
        crown(sim, rebel, pretender, announce = false)
        sim.log(EventKind.MAJOR, "${nation.name}에서 계승 분쟁이 일어났다: ${pretender.house} ${pretender.name}이(가) 자리를 주장하며 ${rebel.name}을(를) 세웠다", nation.id, rebel.id)
    }

    /** The next ruler as the nation's ideology says; [previous] is the one who just left, if any. */
    private fun choose(sim: Simulation, nation: Nation, previous: Leader?): Leader {
        val rng = sim.rng
        return when {
            previous == null -> create(sim, nation, house = Names.house(rng), parent = null, age = 28 + rng.nextInt(20))
            succession(nation) == Succession.HEREDITARY -> {
                val age = (age(sim, previous) - 20 - rng.nextInt(16)).coerceIn(16, 50)
                create(sim, nation, house = previous.house, parent = previous, age = age)
            }
            succession(nation) == Succession.ELECTIVE ->
                create(sim, nation, house = Names.house(rng), parent = null, age = 35 + rng.nextInt(25), wisdomBias = 0.1)
            else -> create(sim, nation, house = Names.house(rng), parent = null, age = 40 + rng.nextInt(25))
        }
    }

    /** A new character. An heir takes after the parent (half inherited, half new), and sometimes a name from the house. */
    fun create(sim: Simulation, nation: Nation, house: String, parent: Leader?, age: Int, aggressionBias: Double = 0.0, wisdomBias: Double = 0.0): Leader {
        val rng = sim.rng
        fun trait(inherited: Double?, bias: Double = 0.0): Double {
            val fresh = rng.nextDouble()
            return ((if (inherited != null) (inherited + fresh) / 2 else fresh) + bias).coerceIn(0.0, 1.0)
        }
        val reuse = parent != null && rng.nextDouble() < 0.4
        val houseNames = nation.regnalCounts.keys.filter { it.startsWith("$house/") }.map { it.substringAfter('/') }
        val name = if (reuse && houseNames.isNotEmpty()) houseNames.random(rng) else Names.person(rng)
        val key = "$house/$name"
        val regnal = (nation.regnalCounts[key] ?: 0) + 1
        nation.regnalCounts[key] = regnal
        return Leader(
            name = name,
            house = house,
            regnal = regnal,
            born = sim.state.turn - age.toLong() * tuning.turnsPerYear,
            crowned = sim.state.turn,
            aggression = trait(parent?.aggression, aggressionBias),
            diplomacy = trait(parent?.diplomacy),
            greed = trait(parent?.greed),
            wisdom = trait(parent?.wisdom, wisdomBias)
        )
    }

    private fun crown(sim: Simulation, nation: Nation, leader: Leader, announce: Boolean) {
        nation.leader = leader
        if (announce) sim.log(EventKind.INFO, "${nation.name}: ${describe(nation)}이(가) 다스리기 시작했다", nation.id)
    }

    private fun retire(sim: Simulation, nation: Nation, leader: Leader, how: String, log: Boolean = true) {
        val name = describe(nation, leader) ?: return
        nation.pastLeaders += "$name (재위 ${reign(sim, leader)}년, $how)"
        while (nation.pastLeaders.size > 12) nation.pastLeaders.removeAt(0)
        nation.leader = null
        if (log) sim.log(EventKind.INFO, "${nation.name}: $name 이(가) $how", nation.id)
    }

    /** Traits as people read them: 0..100. */
    fun traits(leader: Leader): String =
        "호전 ${(leader.aggression * 100).toInt()} · 외교 ${(leader.diplomacy * 100).toInt()} · 탐욕 ${(leader.greed * 100).toInt()} · 현명 ${(leader.wisdom * 100).toInt()}"
}
