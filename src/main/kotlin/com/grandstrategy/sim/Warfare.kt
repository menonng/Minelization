package com.grandstrategy.sim

object Warfare {
    fun warError(sim: Simulation, attacker: Nation, target: Nation): String? {
        val state = sim.state
        if (attacker.id == target.id) return "자기 자신과는 싸울 수 없다."
        if (state.warBetween(attacker.id, target.id) != null) return "이미 전쟁 중이다."
        val r = state.relation(attacker.id, target.id)
        if (r.has(Treaty.TRUCE, state.turn)) return "휴전 기간이다 (${(r.treaties[Treaty.TRUCE] ?: 0) - state.turn}턴 남음)."
        if (r.has(Treaty.ALLIANCE, state.turn)) return "동맹국이다. 먼저 동맹을 파기해야 한다."
        if (sim.borderLength(attacker.id, target.id) == 0) return "국경이 맞닿아 있지 않다."
        return null
    }

    fun declare(sim: Simulation, attacker: Nation, target: Nation): War {
        val state = sim.state
        val relation = state.relation(attacker.id, target.id)
        if (relation.treaties.remove(Treaty.NON_AGGRESSION) != null) {
            attacker.stability = (attacker.stability - 10).coerceAtLeast(0.0)
            for (other in state.alive()) if (other.id != attacker.id) Diplomacy.shift(sim, attacker.id, other.id, -15.0)
            sim.log(EventKind.DIPLOMACY, "${attacker.name}이(가) 불가침 조약을 깨뜨렸다", attacker.id, target.id)
        }
        relation.treaties.remove(Treaty.TRADE)
        Diplomacy.shift(sim, attacker.id, target.id, -30.0)
        val war = War(id = state.nextWarId++, attacker = attacker.id, defender = target.id, startTurn = state.turn)
        war.attackers += attacker.id
        war.defenders += target.id
        state.wars[war.id] = war
        sim.log(EventKind.MAJOR, "${attacker.name}이(가) ${target.name}에 선전포고했다", attacker.id, target.id)
        for (ally in state.alive()) {
            if (ally.id == attacker.id || ally.id == target.id) continue
            val withTarget = state.relation(ally.id, target.id).has(Treaty.ALLIANCE, state.turn)
            val withAttacker = state.relation(ally.id, attacker.id).has(Treaty.ALLIANCE, state.turn)
            if (withTarget && !withAttacker && state.warBetween(ally.id, attacker.id) == null) {
                war.defenders += ally.id
                Diplomacy.shift(sim, ally.id, attacker.id, -20.0)
                sim.log(EventKind.WAR, "${ally.name}이(가) 동맹 ${target.name}을(를) 도와 참전했다", ally.id, target.id)
            }
        }
        return war
    }

    fun run(sim: Simulation) {
        val state = sim.state
        for (war in state.wars.values.toList()) {
            war.attackers.removeIf { state.nation(it) == null }
            war.defenders.removeIf { state.nation(it) == null }
            if (war.attackers.isEmpty() || war.defenders.isEmpty() ||
                state.nation(war.attacker) == null || state.nation(war.defender) == null
            ) {
                end(sim, war, "한쪽이 사라져")
                continue
            }
            war.attackerExhaustion += 1.0
            war.defenderExhaustion += 1.0
            battle(sim, war, war.attackers, war.defenders, 1)
            if (state.wars.containsKey(war.id)) battle(sim, war, war.defenders, war.attackers, -1)
        }
    }

    private fun battle(sim: Simulation, war: War, side: Set<Int>, enemy: Set<Int>, sign: Int) {
        val state = sim.state
        val target = pickTarget(sim, side, enemy) ?: return
        val defender = sim.ownerOf(target.second) ?: return
        val attacker = sim.ownerOf(target.first) ?: return

        val attackForce = side.mapNotNull { state.nation(it) }.sumOf { sim.strength(it) / state.warsOf(it.id).size.coerceAtLeast(1) } *
            (0.8 + sim.rng.nextDouble() * 0.4) * (1.0 + 0.25 * side.sumOf { sim.presence.playersNear(it, target.second) })
        val walls = 1.0 + 0.1 * defender.count(Facility.WALLS) * (1.0 + 0.05 * defender.level(TechBranch.CONSTRUCTION))
        val defendForce = (sim.strength(defender) / state.warsOf(defender.id).size.coerceAtLeast(1) +
            enemy.filter { it != defender.id }.mapNotNull { state.nation(it) }.sumOf { sim.strength(it) * 0.5 / state.warsOf(it.id).size.coerceAtLeast(1) }) *
            1.15 * walls * (0.8 + sim.rng.nextDouble() * 0.4) * (1.0 + 0.25 * sim.presence.playersNear(defender.id, target.second))

        val total = attackForce + defendForce
        if (total <= 0.0) return
        val won = sim.rng.nextDouble() < attackForce / total
        val sideNations = side.mapNotNull { state.nation(it) }
        if (won) {
            casualties(sideNations, 0.05)
            casualties(listOf(defender), 0.10)
            val wasCapital = target.second == defender.capital
            sim.assign(target.second, attacker)
            war.score = (war.score + sign * if (wasCapital) 25.0 else 5.0).coerceIn(-100.0, 100.0)
            exhaust(war, -sign, 3.0)
            transferSettlement(sim, target.second, defender, attacker)
            if (wasCapital) {
                defender.stability = (defender.stability - 20).coerceAtLeast(0.0)
                sim.log(EventKind.MAJOR, "${attacker.name}이(가) ${defender.name}의 수도를 함락했다", attacker.id, defender.id)
                sim.cinematic(Cinematic.CapitalFell(defender.id, attacker.id, target.second))
                relocateCapital(sim, defender)
            } else {
                sim.log(EventKind.WAR, "${attacker.name}이(가) ${defender.name}의 영토 ${Chunks.format(target.second)}를 점령했다", attacker.id, defender.id)
            }
            if (defender.claims.isEmpty()) {
                sim.destroy(defender, "${attacker.name}에게 모든 영토를 잃음", attacker.id)
            }
        } else {
            casualties(sideNations, 0.10)
            casualties(listOf(defender), 0.05)
            war.score = (war.score - sign * 3.0).coerceIn(-100.0, 100.0)
            exhaust(war, sign, 3.0)
        }
    }

    private fun exhaust(war: War, sign: Int, amount: Double) {
        if (sign == 1) war.attackerExhaustion += amount else war.defenderExhaustion += amount
    }

    private fun casualties(nations: List<Nation>, ratio: Double) {
        for (n in nations) n.military = (n.military * (1.0 - ratio)).coerceAtLeast(0.0)
    }

    private fun pickTarget(sim: Simulation, side: Set<Int>, enemy: Set<Int>): Pair<Long, Long>? {
        val state = sim.state
        val options = ArrayList<Pair<Long, Long>>()
        for (id in side) {
            val nation = state.nation(id) ?: continue
            for (chunk in nation.claims) {
                for (n in Chunks.neighbors(chunk)) {
                    val owner = state.owners[n] ?: continue
                    if (owner in enemy) {
                        val defender = state.nations[owner] ?: continue
                        if (n == defender.capital) return chunk to n
                        options += chunk to n
                    }
                }
            }
        }
        if (options.isEmpty()) return null
        return options[sim.rng.nextInt(options.size)]
    }

    private fun transferSettlement(sim: Simulation, chunk: Long, from: Nation, to: Nation) {
        val settlement = sim.settlementAt(chunk) ?: return
        if (settlement.owner == from.id && chunk == settlement.center) {
            settlement.owner = to.id
            settlement.loyalty = 40.0
            sim.log(EventKind.WAR, "${settlement.name}이(가) ${to.name}의 손에 넘어갔다", to.id, from.id)
        }
    }

    fun relocateCapital(sim: Simulation, nation: Nation) {
        val old = nation.capital
        val next = nation.claims.minByOrNull { Chunks.distance(it, old) } ?: return
        nation.capital = next
        nation.markerPlaced = false
    }

    fun wantsPeace(sim: Simulation, nation: Nation, war: War): Boolean {
        val exhaustion = war.exhaustionOf(nation.id)
        val score = war.scoreFor(nation.id)
        return exhaustion > 50 + nation.personality.aggression * 30 || score < -30 || (exhaustion > 25 && score < -10)
    }

    fun acceptsPeace(sim: Simulation, nation: Nation, war: War): Boolean {
        val score = war.scoreFor(nation.id)
        val exhaustion = war.exhaustionOf(nation.id)
        return wantsPeace(sim, nation, war) || (score < 20 && exhaustion > 20) || exhaustion > 70 + nation.personality.aggression * 40
    }

    fun makePeace(sim: Simulation, war: War, requester: Nation) {
        val primary = requester.id == war.attacker || requester.id == war.defender
        if (primary) {
            for (a in war.attackers) for (d in war.defenders) truce(sim, a, d)
            end(sim, war, "평화 협정으로")
        } else {
            val enemies = if (requester.id in war.attackers) war.defenders else war.attackers
            for (e in enemies) truce(sim, requester.id, e)
            war.attackers.remove(requester.id)
            war.defenders.remove(requester.id)
            sim.log(EventKind.WAR, "${requester.name}이(가) 단독 강화로 전쟁에서 빠졌다", requester.id)
        }
    }

    private fun truce(sim: Simulation, a: Int, b: Int) {
        sim.state.relation(a, b).treaties[Treaty.TRUCE] = sim.state.turn + Balance.TRUCE_TURNS
    }

    private fun end(sim: Simulation, war: War, reason: String) {
        sim.state.wars.remove(war.id)
        val a = sim.state.nations[war.attacker]
        val d = sim.state.nations[war.defender]
        sim.log(EventKind.MAJOR, "${a?.name}와(과) ${d?.name}의 전쟁이 $reason 끝났다 (점수 ${"%.0f".format(war.score)})", war.attacker, war.defender)
    }
}
