package com.grandstrategy.sim

object Settlements {
    fun register(sim: Simulation, center: Long, land: Collection<Long>, name: String? = null): Settlement? {
        val state = sim.state
        if (state.settlementLand.containsKey(center)) return null
        if (!sim.terrain(center).claimable) return null
        val owner = sim.ownerOf(center)
        val usable = land.filter { !state.settlementLand.containsKey(it) && (state.owners[it] == null || state.owners[it] == owner?.id) }
        if (center !in usable) return null
        val settlement = Settlement(id = state.nextSettlementId++, name = name ?: Names.settlement(sim.rng), center = center)
        settlement.land += usable
        state.settlements[settlement.id] = settlement
        for (chunk in usable) state.settlementLand[chunk] = settlement.id
        if (owner != null) {
            annex(sim, settlement, owner)
            settlement.loyalty = 80.0
            sim.log(EventKind.INFO, "${owner.name} 영토 안에서 마을 ${settlement.name}이(가) 발견되어 도시가 되었다", owner.id)
        } else {
            sim.log(EventKind.INFO, "자유 마을 ${settlement.name} 발견 ${Chunks.format(center)}")
        }
        return settlement
    }

    fun distanceTo(nation: Nation, settlement: Settlement): Int {
        var best = Int.MAX_VALUE
        for (chunk in nation.claims) {
            val d = Chunks.distance(chunk, settlement.center)
            if (d < best) best = d
        }
        return best
    }

    fun inRange(nation: Nation, settlement: Settlement): Boolean = distanceTo(nation, settlement) <= Balance.TRADE_RANGE

    fun run(sim: Simulation) {
        val state = sim.state
        for (settlement in state.settlements.values) {
            val owner = state.nation(settlement.owner)
            if (settlement.owner != null && owner == null) {
                settlement.owner = null
                settlement.loyalty = 100.0
            }
            if (owner != null) {
                tickOwned(sim, settlement, owner)
            } else {
                tickFree(sim, settlement)
            }
        }
    }

    private fun tickFree(sim: Simulation, settlement: Settlement) {
        val nations = sim.state.alive()
        for (nation in nations) {
            val current = settlement.influence[nation.id] ?: 0.0
            if (inRange(nation, settlement)) {
                nation.add(Resource.GOLD, 0.5 + settlement.population * 0.03)
                settlement.influence[nation.id] = (current + 1.0 + 0.5 * nation.count(Facility.MARKET)).coerceAtMost(100.0)
            } else if (current > 0) {
                settlement.influence[nation.id] = (current - 1.0).coerceAtLeast(0.0)
            }
        }
        val winner = settlement.influence.entries
            .filter { it.value >= 100.0 }
            .mapNotNull { sim.state.nation(it.key) }
            .firstOrNull { inRange(it, settlement) }
        if (winner != null) {
            annex(sim, settlement, winner)
            sim.log(EventKind.MAJOR, "자유 마을 ${settlement.name}이(가) ${winner.name}에 자발적으로 편입되었다", winner.id)
        }
    }

    private fun tickOwned(sim: Simulation, settlement: Settlement, owner: Nation) {
        if (owner.stability < 30) {
            settlement.loyalty -= (30 - owner.stability) * 0.2
        } else {
            settlement.loyalty = (settlement.loyalty + 2.0).coerceAtMost(100.0)
        }
        if (settlement.loyalty <= 0) {
            for (chunk in settlement.land) {
                if (sim.state.owners[chunk] == owner.id) sim.release(chunk)
            }
            settlement.owner = null
            settlement.loyalty = 100.0
            settlement.influence.clear()
            sim.log(EventKind.MAJOR, "${settlement.name}이(가) ${owner.name}의 지배에서 벗어나 독립했다", owner.id)
        }
    }

    fun annex(sim: Simulation, settlement: Settlement, nation: Nation) {
        settlement.owner = nation.id
        settlement.loyalty = 60.0
        settlement.influence.clear()
        for (chunk in settlement.land) {
            if (!sim.state.owners.containsKey(chunk)) sim.assign(chunk, nation)
        }
    }
}
