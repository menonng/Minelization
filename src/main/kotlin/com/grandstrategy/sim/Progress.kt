package com.grandstrategy.sim

/**
 * Per-turn bookkeeping of the content layer: announces new eras, gives older saves a flag, and lets AI-run nations
 * pick an ideology that suits them (re-evaluated every [IDEOLOGY_INTERVAL] turns or when they have none).
 */
object Progress {
    const val IDEOLOGY_INTERVAL = 40L

    fun run(sim: Simulation) {
        val content = Content.config
        for (nation in sim.state.alive()) {
            if (nation.flag == null) nation.flag = Flags.random(sim.state.seed xor nation.id.toLong() * 7919, nation.color)
            val era = content.era(nation)
            if (nation.era != era.id) {
                val first = nation.era == null
                nation.era = era.id
                if (!first) sim.log(EventKind.MAJOR, "${nation.name}이(가) ${era.name} 시대에 들어섰다", nation.id)
            }
            val governed = nation.ruler != null && !nation.autoGovern
            val current = content.ideologyOf(nation)
            val due = current == null || sim.state.turn - nation.ideologyTurn >= IDEOLOGY_INTERVAL
            if (!governed && due) {
                val best = Content.preferredIdeology(nation) ?: continue
                nation.ideologyTurn = sim.state.turn
                if (best.id != nation.ideology) {
                    val had = current != null
                    nation.ideology = best.id
                    if (had) sim.log(EventKind.DIPLOMACY, "${nation.name}이(가) ${best.name}을(를) 받아들였다", nation.id)
                }
            }
        }
    }

    /** A ruler's choice. Fails if the ideology is unknown or not unlocked yet. */
    fun choose(sim: Simulation, nation: Nation, id: String): Outcome {
        val d = Content.config.ideologies.firstOrNull { it.id == id } ?: return Outcome.fail("그런 사상이 없다.")
        if (!Content.config.met(d.requires, nation)) return Outcome.fail("${d.name}은(는) 아직 받아들일 수 없다.")
        nation.ideology = d.id
        nation.ideologyTurn = sim.state.turn
        sim.log(EventKind.DIPLOMACY, "${nation.name}이(가) ${d.name}을(를) 받아들였다", nation.id)
        return Outcome.ok("${d.name}을(를) 국가 사상으로 삼았다.")
    }
}
