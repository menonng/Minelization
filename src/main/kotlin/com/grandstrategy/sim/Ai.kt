package com.grandstrategy.sim

import kotlin.math.max
import kotlin.math.min

object Ai {

    class Assessment(
        val net: Map<Resource, Double>,
        val pressure: Map<Resource, Double>,
        val threat: Double,
        val threats: Map<Int, Double>,
        val rival: Nation?,
        val rivalRatio: Double,
        val frontier: Long?,
        val frontierValue: Double,
        val settlementsInRange: List<Settlement>,
        val atWar: Boolean
    )

    data class Candidate(val action: Action, val utility: Double, val reason: String)

    fun run(sim: Simulation) {
        for (nation in sim.state.alive()) {
            if (!nation.aiControlled || !nation.alive) continue
            val view = perceive(sim, nation)
            nation.posture = posture(nation, view)
            freeChoices(sim, nation, view)
            val ranked = candidates(sim, nation, view).sortedByDescending { it.utility }
            val tuning = Balance.ai
            execute(sim, nation, ranked.filter { isStrategic(it.action) }, tuning.strategicActionsPerTurn, tuning.strategicThreshold)
            execute(sim, nation, ranked.filter { it.action is Action.Claim }, 1, tuning.economicThreshold)
            execute(sim, nation, ranked.filter { !isStrategic(it.action) && it.action !is Action.Claim }, tuning.economicActionsPerTurn, tuning.economicThreshold)
        }
    }

    private fun isStrategic(action: Action): Boolean = action is Action.ProposeTreaty || action is Action.DeclareWar ||
        action is Action.ProposePeace || action is Action.Envoy || action is Action.Conquer

    private fun execute(sim: Simulation, nation: Nation, ranked: List<Candidate>, limit: Int, threshold: Double) {
        var done = 0
        for (c in ranked) {
            if (done >= limit || c.utility < threshold) break
            val outcome = Actions.execute(sim, nation, c.action)
            remember(sim, nation, c.action)
            if (outcome.ok) {
                done++
                if (c.action !is Action.Claim && c.action !is Action.Recruit) {
                    sim.log(EventKind.INFO, "${nation.name}: ${outcome.message} — ${c.reason}", nation.id)
                }
            }
        }
    }

    fun perceive(sim: Simulation, nation: Nation): Assessment {
        val produced = Economy.production(sim, nation)
        val upkeep = Economy.upkeep(nation)
        val net = Resource.entries.associateWith { (produced[it] ?: 0.0) - (upkeep[it] ?: 0.0) }
        val pressure = Resource.entries.associateWith { r ->
            val n = net[r] ?: 0.0
            val stock = nation.has(r)
            if (n < 0) 0.3 + (1.0 - min(1.0, stock / (-n * 15.0))) else 0.2 * (1.0 - min(1.0, stock / 200.0))
        }
        val threats = HashMap<Int, Double>()
        for (other in sim.state.alive()) {
            if (other.id == nation.id) continue
            val t = Diplomacy.threatFrom(sim, nation, other)
            if (t > 0.05) threats[other.id] = t
        }
        val mine = sim.strength(nation).coerceAtLeast(1.0)
        val rival = sim.neighbors(nation.id)
            .filter { Warfare.warError(sim, nation, it) == null }
            .minByOrNull { sim.strength(it) + Diplomacy.opinion(sim, nation.id, it.id) }
        val rivalRatio = rival?.let { mine / sim.strength(it).coerceAtLeast(1.0) } ?: 0.0

        var bestChunk: Long? = null
        var bestValue = Double.NEGATIVE_INFINITY
        val seen = HashSet<Long>()
        for (chunk in nation.claims) {
            for (n in Chunks.neighbors(chunk)) {
                if (!seen.add(n)) continue
                if (Actions.claimError(sim, nation, n) != null) continue
                val t = sim.terrain(n)
                var value = Resource.entries.sumOf { Balance.yield(t, it) * (1.0 + 2.0 * (pressure[it] ?: 0.0)) }
                value += Chunks.neighbors(n).count { sim.state.owners[it] == nation.id } * 0.4
                value -= Chunks.distance(n, nation.capital) * 0.05
                if (value > bestValue) {
                    bestValue = value
                    bestChunk = n
                }
            }
        }
        val settlements = sim.state.settlements.values.filter { it.owner == null && Settlements.inRange(nation, it) }
        val peak = threats.values.maxOrNull() ?: 0.0
        val overall = peak + 0.25 * (threats.values.sum() - peak)
        return Assessment(
            net, pressure, overall, threats, rival, rivalRatio,
            bestChunk, if (bestChunk == null) 0.0 else bestValue, settlements,
            sim.state.warsOf(nation.id).isNotEmpty()
        )
    }

    fun posture(nation: Nation, view: Assessment): Posture {
        val previous = nation.posture
        return when {
            view.atWar -> Posture.WAR
            nation.stability < 35 || (view.pressure[Resource.FOOD] ?: 0.0) > 0.9 -> Posture.CONSOLIDATE
            view.threat > 1.2 || (previous == Posture.ARM && view.threat > 0.9) -> Posture.ARM
            nation.personality.expansionism > 0.45 && view.frontier != null &&
                nation.claims.size < Balance.overextensionLimit(nation) -> Posture.EXPAND
            else -> Posture.GROW
        }
    }

    private fun freeChoices(sim: Simulation, nation: Nation, view: Assessment) {
        val branch = when (nation.posture) {
            Posture.WAR, Posture.ARM -> if (nation.level(TechBranch.MILITARY) <= nation.level(TechBranch.CONSTRUCTION)) TechBranch.MILITARY else TechBranch.CONSTRUCTION
            Posture.CONSOLIDATE -> if ((view.pressure[Resource.FOOD] ?: 0.0) > 0.5) TechBranch.AGRICULTURE else TechBranch.ADMINISTRATION
            Posture.EXPAND -> if (nation.level(TechBranch.LOGISTICS) <= nation.level(TechBranch.ADMINISTRATION)) TechBranch.LOGISTICS else TechBranch.ADMINISTRATION
            // Peacetime: bring up the least developed branch, so a nation's weapons, buildings and roads keep pace with
            // its era. Aggressive rulers lean on the army; underwater building comes slowest.
            Posture.GROW -> TechBranch.entries.minBy { b ->
                val weight = when (b) {
                    TechBranch.MILITARY -> 1.3 - 0.6 * nation.personality.aggression
                    TechBranch.MARINE -> 2.0
                    TechBranch.ADMINISTRATION, TechBranch.LOGISTICS -> 1.1
                    else -> 1.0
                }
                (nation.level(b) + 1) * weight
            }
        }
        if (nation.researchFocus != branch && nation.researchProgress < Balance.techCost(nation.level(nation.researchFocus)) * 0.5) {
            nation.researchFocus = branch
        }
        val policyKey = WorldState.pairKey(nation.id, 0)
        val lastChange = sim.aiMemory[policyKey] ?: -100L
        if (sim.state.turn - lastChange < Balance.ai.policyCooldownTurns) return
        val policy = when (nation.posture) {
            Posture.WAR, Posture.ARM -> Policy.MILITARY
            Posture.CONSOLIDATE -> Policy.BALANCED
            Posture.EXPAND -> Policy.EXPANSION
            Posture.GROW -> if ((view.pressure[Resource.GOLD] ?: 0.0) > 0.3) Policy.ECONOMY else if (nation.personality.diplomacy > 0.5) Policy.SCIENCE else Policy.ECONOMY
        }
        if (policy != nation.policy) {
            nation.policy = policy
            sim.aiMemory[policyKey] = sim.state.turn
        }
    }

    private fun weight(nation: Nation, vararg pairs: Pair<Posture, Double>): Double =
        pairs.firstOrNull { it.first == nation.posture }?.second ?: 0.0

    fun candidates(sim: Simulation, nation: Nation, view: Assessment): List<Candidate> {
        val state = sim.state
        val p = nation.personality
        val out = ArrayList<Candidate>()

        if (nation.totalFacilities() < nation.claims.size) {
            for (r in Resource.entries) {
                val facility = Economy.facilityFor(r)
                val pr = view.pressure[r] ?: 0.0
                val u = 0.1 + pr + weight(nation, Posture.GROW to 0.15, Posture.CONSOLIDATE to 0.2) - nation.count(facility) * 0.04
                out += Candidate(Action.Build(facility), u, "${r.label} 압박 ${"%.2f".format(pr)}")
            }
            out += Candidate(Action.Build(Facility.LIBRARY), 0.2 + weight(nation, Posture.GROW to 0.2) + p.diplomacy * 0.1 - nation.count(Facility.LIBRARY) * 0.05, "학문 투자")
            out += Candidate(Action.Build(Facility.BARRACKS), view.threat * 0.3 + weight(nation, Posture.ARM to 0.35, Posture.WAR to 0.25) - nation.count(Facility.BARRACKS) * 0.08, "위협 ${"%.2f".format(view.threat)}")
            out += Candidate(Action.Build(Facility.WALLS), view.threat * 0.35 + weight(nation, Posture.ARM to 0.3, Posture.WAR to 0.3) - nation.count(Facility.WALLS) * 0.08, "방어 강화")
        }

        view.frontier?.let { chunk ->
            val over = nation.claims.size - Balance.overextensionLimit(nation)
            val u = 0.25 + 0.5 * p.expansionism + view.frontierValue / 15.0 +
                weight(nation, Posture.EXPAND to 0.5, Posture.GROW to 0.15, Posture.CONSOLIDATE to -0.6, Posture.WAR to -0.2) -
                max(0, over) * 0.012 * (1.5 - p.expansionism) - (70.0 - nation.stability).coerceAtLeast(0.0) * 0.015 +
                Resource.entries.count { nation.has(it) >= Balance.storageCap(nation) * 0.9 } * 0.12
            out += Candidate(Action.Claim(chunk), u, "확장 가치 ${"%.1f".format(view.frontierValue)}")
        }

        val room = (Balance.recruitCap(nation) - nation.military).toInt()
        if (room > 0) {
            val desired = view.threat * 0.8 + weight(nation, Posture.ARM to 0.5, Posture.WAR to 0.7, Posture.EXPAND to 0.1) + p.aggression * 0.15 -
                (nation.military / Balance.recruitCap(nation).coerceAtLeast(1.0)) * 0.5 +
                min(0.4, nation.has(Resource.GOLD) / 600.0) + min(0.2, nation.has(Resource.IRON) / 300.0)
            val amount = min(room, 10)
            out += Candidate(Action.Recruit(amount), desired, "병력 ${nation.military.toInt()}/${Balance.recruitCap(nation).toInt()}")
        }

        for (s in view.settlementsInRange) {
            val influence = s.influence[nation.id] ?: 0.0
            if (nation.has(Resource.GOLD) > Balance.ENVOY_COST + 20) {
                out += Candidate(Action.Envoy(s.id), 0.2 + p.diplomacy * 0.4 + influence / 250.0 + weight(nation, Posture.GROW to 0.1), "${s.name} 영향력 ${influence.toInt()}")
            }
            if (sim.strength(nation) > s.defense * 2.5 && Settlements.distanceTo(nation, s) <= 2) {
                out += Candidate(Action.Conquer(s.id), p.aggression * 0.7 + weight(nation, Posture.EXPAND to 0.3) - p.diplomacy * 0.4, "${s.name} 방어력 ${s.defense.toInt()}")
            }
        }

        diplomaticCandidates(sim, nation, view, out)

        if (nation.posture == Posture.WAR) {
            for (war in state.warsOf(nation.id)) {
                if (Warfare.wantsPeace(sim, nation, war)) {
                    val enemy = if (nation.id in war.attackers) war.defender else war.attacker
                    out += Candidate(Action.ProposePeace(enemy), 2.0, "전쟁 피로 ${war.exhaustionOf(nation.id).toInt()}, 점수 ${war.scoreFor(nation.id).toInt()}")
                }
            }
        } else {
            view.rival?.let { rival ->
                val opinion = Diplomacy.opinion(sim, nation.id, rival.id)
                val fed = (view.pressure[Resource.FOOD] ?: 0.0) < 0.5
                val hated = opinion < Balance.ai.warHatredOpinion
                val greedy = p.aggression > 0.55 && view.rivalRatio > Balance.ai.warPowerRatioGreedy
                val lastWar = sim.aiMemory[WorldState.pairKey(nation.id, -1)] ?: -100L
                if ((hated || greedy) && fed && view.rivalRatio > (if (hated) Balance.ai.warPowerRatioHated else Balance.ai.warPowerRatioGreedy) && state.turn - lastWar > Balance.ai.warCooldownTurns) {
                    val u = p.aggression * 0.8 + (view.rivalRatio - 1.2) * 0.3 - opinion / 100.0 - p.diplomacy * 0.3
                    out += Candidate(Action.DeclareWar(rival.id), u, "전력비 ${"%.1f".format(view.rivalRatio)}, 호감도 ${opinion.toInt()}")
                }
            }
        }
        return out
    }

    private fun diplomaticCandidates(sim: Simulation, nation: Nation, view: Assessment, out: MutableList<Candidate>) {
        val state = sim.state
        val p = nation.personality
        for (other in state.alive()) {
            if (other.id == nation.id || state.warBetween(nation.id, other.id) != null) continue
            val key = WorldState.pairKey(nation.id, other.id) xor 0x5bd1e995L
            val last = sim.aiMemory[key] ?: -100L
            if (state.turn - last < Balance.ai.proposalCooldownTurns) continue
            val r = state.relation(nation.id, other.id)
            val opinion = r.opinion
            val threat = view.threats[other.id] ?: 0.0
            val bordering = sim.borderLength(nation.id, other.id) > 0
            fun add(treaty: Treaty, u: Double, reason: String) {
                if (!r.has(treaty, state.turn)) out += Candidate(Action.ProposeTreaty(other.id, treaty), u, reason)
            }
            if (bordering && threat > 0.7 && opinion > -20) add(Treaty.NON_AGGRESSION, 0.3 + threat * 0.3 + p.diplomacy * 0.2, "${other.name}의 위협 ${"%.2f".format(threat)}")
            if (opinion > 5) add(Treaty.TRADE, 0.25 + p.diplomacy * 0.3 + opinion / 200.0, "호감도 ${opinion.toInt()}")
            if (opinion > 35 && view.threat > 0.6) add(Treaty.ALLIANCE, 0.3 + p.diplomacy * 0.3 + view.threat * 0.2, "공동 방위")
        }
    }

    fun remember(sim: Simulation, nation: Nation, action: Action) {
        when (action) {
            is Action.ProposeTreaty -> sim.aiMemory[WorldState.pairKey(nation.id, action.target) xor 0x5bd1e995L] = sim.state.turn
            is Action.DeclareWar -> sim.aiMemory[WorldState.pairKey(nation.id, -1)] = sim.state.turn
            else -> {}
        }
    }
}
