package com.grandstrategy.sim

sealed interface Action {
    data class Claim(val chunk: Long) : Action
    data class Build(val facility: Facility) : Action
    data class Recruit(val amount: Int) : Action
    data class Research(val branch: TechBranch) : Action
    data class SetPolicy(val policy: Policy) : Action
    data class Envoy(val settlement: Int) : Action
    data class Conquer(val settlement: Int) : Action
    data class ProposeTreaty(val target: Int, val treaty: Treaty) : Action
    data class CancelTreaty(val target: Int, val treaty: Treaty) : Action
    data class DeclareWar(val target: Int) : Action
    data class ProposePeace(val target: Int) : Action
}

data class Outcome(val ok: Boolean, val message: String) {
    companion object {
        fun ok(message: String) = Outcome(true, message)
        fun fail(message: String) = Outcome(false, message)
    }
}

object Actions {
    fun canAfford(nation: Nation, cost: Map<Resource, Double>): Boolean = cost.all { nation.has(it.key) >= it.value }

    fun pay(nation: Nation, cost: Map<Resource, Double>) {
        for ((r, v) in cost) nation.add(r, -v)
    }

    fun describe(cost: Map<Resource, Double>): String =
        cost.entries.joinToString(", ") { "${it.key.label} ${"%.0f".format(it.value)}" }

    fun claimError(sim: Simulation, nation: Nation, chunk: Long): String? {
        val state = sim.state
        if (!sim.terrain(chunk).claimable) return "바다는 차지할 수 없다."
        if (state.owners.containsKey(chunk)) return "이미 ${sim.ownerOf(chunk)?.name ?: "누군가"}의 영토다."
        if (state.settlementLand.containsKey(chunk)) return "자유 마을의 땅이다. 사절이나 정복으로만 얻을 수 있다."
        if (Chunks.neighbors(chunk).none { state.owners[it] == nation.id }) return "우리 영토와 맞닿아 있지 않다."
        return null
    }

    fun execute(sim: Simulation, nation: Nation, action: Action): Outcome {
        if (!nation.alive) return Outcome.fail("이미 멸망한 나라다.")
        val state = sim.state
        return when (action) {
            is Action.Claim -> {
                claimError(sim, nation, action.chunk)?.let { return Outcome.fail(it) }
                val cost = Balance.claimCost(nation)
                if (!canAfford(nation, cost)) return Outcome.fail("자원이 부족하다: ${describe(cost)}")
                pay(nation, cost)
                sim.assign(action.chunk, nation)
                Outcome.ok("${Chunks.format(action.chunk)} 청크를 영토로 편입했다 (${sim.terrain(action.chunk).label}).")
            }

            is Action.Build -> {
                if (nation.totalFacilities() >= nation.claims.size) return Outcome.fail("시설 수는 영토 수(${nation.claims.size})를 넘을 수 없다.")
                val cost = Balance.facilityCost(nation, action.facility)
                if (!canAfford(nation, cost)) return Outcome.fail("자원이 부족하다: ${describe(cost)}")
                pay(nation, cost)
                nation.facilities.merge(action.facility, 1, Int::plus)
                Outcome.ok("${action.facility.label}을(를) 지었다 (현재 ${nation.count(action.facility)}개).")
            }

            is Action.Recruit -> {
                if (action.amount <= 0) return Outcome.fail("1명 이상이어야 한다.")
                val room = Balance.recruitCap(nation) - nation.military
                if (room < action.amount) return Outcome.fail("인구 대비 병력 한도를 넘는다 (추가 가능 ${room.toInt().coerceAtLeast(0)}).")
                val cost = Balance.recruitCost(nation, action.amount)
                if (!canAfford(nation, cost)) return Outcome.fail("자원이 부족하다: ${describe(cost)}")
                pay(nation, cost)
                nation.military += action.amount
                Outcome.ok("병력 ${action.amount}을(를) 징집했다 (총 ${nation.military.toInt()}).")
            }

            is Action.Research -> {
                nation.researchFocus = action.branch
                Outcome.ok("연구 분야를 ${action.branch.label}(으)로 정했다.")
            }

            is Action.SetPolicy -> {
                nation.policy = action.policy
                Outcome.ok("정책을 ${action.policy.label}(으)로 바꿨다: ${action.policy.description}")
            }

            is Action.Envoy -> {
                val s = state.settlements[action.settlement] ?: return Outcome.fail("그런 마을이 없다.")
                if (s.owner != null) return Outcome.fail("이미 ${state.nation(s.owner)?.name}의 마을이다.")
                if (!Settlements.inRange(nation, s)) return Outcome.fail("너무 멀다 (영토에서 ${Balance.TRADE_RANGE}청크 이내여야 한다).")
                if (nation.has(Resource.GOLD) < Balance.ENVOY_COST) return Outcome.fail("금이 ${Balance.ENVOY_COST.toInt()} 필요하다.")
                nation.add(Resource.GOLD, -Balance.ENVOY_COST)
                val v = ((s.influence[nation.id] ?: 0.0) + Balance.ENVOY_INFLUENCE).coerceAtMost(100.0)
                s.influence[nation.id] = v
                sim.log(EventKind.DIPLOMACY, "${nation.name}이(가) ${s.name}에 사절을 보냈다 (영향력 ${v.toInt()})", nation.id)
                Outcome.ok("${s.name}에 사절을 보냈다. 영향력 ${v.toInt()}/100")
            }

            is Action.Conquer -> {
                val s = state.settlements[action.settlement] ?: return Outcome.fail("그런 마을이 없다.")
                if (s.owner != null) return Outcome.fail("이미 ${state.nation(s.owner)?.name}의 마을이다.")
                if (Settlements.distanceTo(nation, s) > 2) return Outcome.fail("마을이 우리 영토 2청크 안에 있어야 한다.")
                val power = sim.strength(nation)
                if (power < s.defense * 2) return Outcome.fail("전투력이 부족하다 (${power.toInt()} / 필요 ${(s.defense * 2).toInt()}).")
                nation.military *= 0.9
                nation.stability = (nation.stability - 10).coerceAtLeast(0.0)
                for (other in state.alive()) if (other.id != nation.id) Diplomacy.shift(sim, nation.id, other.id, -10.0)
                s.population *= 0.7
                Settlements.annex(sim, s, nation)
                s.loyalty = 30.0
                sim.log(EventKind.MAJOR, "${nation.name}이(가) 자유 마을 ${s.name}을(를) 무력으로 정복했다", nation.id)
                Outcome.ok("${s.name}을(를) 정복했다. 다른 나라들의 시선이 차가워졌다.")
            }

            is Action.ProposeTreaty -> {
                val target = state.nation(action.target) ?: return Outcome.fail("그런 나라가 없다.")
                if (target.id == nation.id) return Outcome.fail("자기 자신과는 조약을 맺을 수 없다.")
                if (action.treaty == Treaty.TRUCE) return Outcome.fail("휴전은 평화 협상으로만 맺는다.")
                if (state.relation(nation.id, target.id).has(action.treaty, state.turn)) return Outcome.fail("이미 ${action.treaty.label} 조약이 있다.")
                if (state.warBetween(nation.id, target.id) != null) return Outcome.fail("전쟁 중에는 조약을 맺을 수 없다.")
                if (target.aiControlled) {
                    if (Diplomacy.aiAccepts(sim, target, nation, action.treaty)) {
                        Diplomacy.sign(sim, nation, target, action.treaty)
                        Outcome.ok("${target.name}이(가) ${action.treaty.label} 조약을 받아들였다.")
                    } else {
                        Diplomacy.shift(sim, nation.id, target.id, -2.0)
                        Outcome.fail("${target.name}이(가) ${action.treaty.label} 조약을 거절했다.")
                    }
                } else {
                    val p = sim.propose(nation, target, "treaty:${action.treaty.name}")
                    sim.log(EventKind.DIPLOMACY, "${nation.name}이(가) ${target.name}에 ${action.treaty.label} 조약을 제안했다 #${p.id}", nation.id, target.id)
                    Outcome.ok("${target.name}에 제안을 보냈다. 상대 통치자의 답을 기다린다.")
                }
            }

            is Action.CancelTreaty -> {
                val target = state.nation(action.target) ?: return Outcome.fail("그런 나라가 없다.")
                if (!state.relation(nation.id, target.id).has(action.treaty, state.turn)) return Outcome.fail("그런 조약이 없다.")
                if (action.treaty == Treaty.TRUCE) return Outcome.fail("휴전은 파기할 수 없다.")
                Diplomacy.cancel(sim, nation, target, action.treaty)
                Outcome.ok("${target.name}와(과)의 ${action.treaty.label} 조약을 파기했다.")
            }

            is Action.DeclareWar -> {
                val target = state.nation(action.target) ?: return Outcome.fail("그런 나라가 없다.")
                Warfare.warError(sim, nation, target)?.let { return Outcome.fail(it) }
                Warfare.declare(sim, nation, target)
                Outcome.ok("${target.name}에 선전포고했다.")
            }

            is Action.ProposePeace -> {
                val target = state.nation(action.target) ?: return Outcome.fail("그런 나라가 없다.")
                val war = state.warBetween(nation.id, target.id) ?: return Outcome.fail("전쟁 중이 아니다.")
                if (target.aiControlled) {
                    if (Warfare.acceptsPeace(sim, target, war)) {
                        Warfare.makePeace(sim, war, nation)
                        Outcome.ok("${target.name}이(가) 평화를 받아들였다.")
                    } else {
                        Outcome.fail("${target.name}이(가) 평화 제안을 거절했다 (상대 전쟁 점수 ${war.scoreFor(target.id).toInt()}).")
                    }
                } else {
                    val p = sim.propose(nation, target, "peace")
                    sim.log(EventKind.DIPLOMACY, "${nation.name}이(가) ${target.name}에 평화를 제안했다 #${p.id}", nation.id, target.id)
                    Outcome.ok("${target.name}에 평화를 제안했다.")
                }
            }
        }
    }

    fun answer(sim: Simulation, proposalId: Int, responder: Nation, accept: Boolean): Outcome {
        val state = sim.state
        val p = state.proposals[proposalId] ?: return Outcome.fail("그런 제안이 없거나 만료됐다.")
        if (p.to != responder.id) return Outcome.fail("우리에게 온 제안이 아니다.")
        state.proposals.remove(proposalId)
        val from = state.nation(p.from) ?: return Outcome.fail("제안한 나라가 사라졌다.")
        if (!accept) {
            Diplomacy.shift(sim, from.id, responder.id, -3.0)
            sim.log(EventKind.DIPLOMACY, "${responder.name}이(가) ${from.name}의 제안을 거절했다", from.id, responder.id)
            return Outcome.ok("제안을 거절했다.")
        }
        return if (p.type == "peace") {
            val war = state.warBetween(from.id, responder.id) ?: return Outcome.fail("이미 전쟁이 끝났다.")
            Warfare.makePeace(sim, war, from)
            Outcome.ok("${from.name}와(과) 평화를 맺었다.")
        } else {
            val treaty = Treaty.valueOf(p.type.removePrefix("treaty:"))
            if (state.warBetween(from.id, responder.id) != null) return Outcome.fail("전쟁 중이다.")
            Diplomacy.sign(sim, from, responder, treaty)
            Outcome.ok("${from.name}와(과) ${treaty.label} 조약을 맺었다.")
        }
    }

    fun deposit(sim: Simulation, nation: Nation, resource: Resource, amount: Double, by: String) {
        nation.add(resource, amount)
        if (amount >= 32) sim.log(EventKind.ECONOMY, "$by 님이 ${nation.name} 국고에 ${resource.label} ${amount.toInt()}을(를) 바쳤다", nation.id)
    }
}
