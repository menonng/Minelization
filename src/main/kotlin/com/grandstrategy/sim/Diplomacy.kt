package com.grandstrategy.sim

object Diplomacy {
    fun run(sim: Simulation) {
        val state = sim.state
        val nations = state.alive()
        for (i in nations.indices) for (j in i + 1 until nations.size) {
            val a = nations[i]
            val b = nations[j]
            val r = state.relation(a.id, b.id)
            var o = r.opinion * 0.98
            val border = sim.borderLength(a.id, b.id)
            if (border > 0) {
                o -= 0.4 * minOf(border, 10) / 10.0 + 0.2
                if (sim.rng.nextDouble() < 0.03 && state.warBetween(a.id, b.id) == null) {
                    val hit = 10.0 + sim.rng.nextDouble() * 15.0
                    o -= hit
                    sim.log(EventKind.DIPLOMACY, "${a.name}와(과) ${b.name}의 국경에서 분쟁이 일어났다 (호감도 -${hit.toInt()})", a.id, b.id)
                }
            } else if (a.personality.diplomacy + b.personality.diplomacy > 0.8) {
                o += 0.3
            }
            if (r.has(Treaty.TRADE, state.turn)) o += 0.5
            if (r.has(Treaty.ALLIANCE, state.turn)) o += 0.5
            if (r.has(Treaty.NON_AGGRESSION, state.turn)) o += 0.2
            if (state.warBetween(a.id, b.id) != null) o -= 2.0
            if (state.enemiesOf(a.id).intersect(state.enemiesOf(b.id)).isNotEmpty()) o += 1.0
            o += 0.3 * ((a.personality.diplomacy + b.personality.diplomacy) / 2.0 - 0.5)
            r.opinion = o.coerceIn(-100.0, 100.0)
            r.treaties.entries.removeIf { it.value in 0..state.turn }
        }
    }

    fun opinion(sim: Simulation, a: Int, b: Int): Double = sim.state.relation(a, b).opinion

    fun shift(sim: Simulation, a: Int, b: Int, amount: Double) {
        val r = sim.state.relation(a, b)
        r.opinion = (r.opinion + amount).coerceIn(-100.0, 100.0)
    }

    fun threatFrom(sim: Simulation, self: Nation, other: Nation): Double {
        val mine = sim.strength(self).coerceAtLeast(1.0)
        val hostility = (0.5 - opinion(sim, self.id, other.id) / 200.0).coerceIn(0.0, 1.0)
        val proximity = if (sim.borderLength(self.id, other.id) > 0) 1.0 else 0.3
        return sim.strength(other) * hostility * proximity / mine
    }

    fun aiAccepts(sim: Simulation, target: Nation, from: Nation, treaty: Treaty): Boolean {
        val state = sim.state
        if (state.warBetween(target.id, from.id) != null) return false
        val o = opinion(sim, target.id, from.id) + (target.personality.diplomacy - 0.5) * 20
        val threat = threatFrom(sim, target, from)
        return when (treaty) {
            Treaty.NON_AGGRESSION -> o > -10 && (threat > 0.8 || o > 15)
            Treaty.TRADE -> o > 0
            Treaty.ALLIANCE -> {
                val commonThreat = sim.state.alive().any { it.id != target.id && it.id != from.id && threatFrom(sim, target, it) > 0.8 && threatFrom(sim, from, it) > 0.5 }
                o > 40 || (o > 15 && commonThreat)
            }
            Treaty.TRUCE -> true
        }
    }

    fun sign(sim: Simulation, a: Nation, b: Nation, treaty: Treaty, duration: Long = -1) {
        val until = if (duration < 0) -1 else sim.state.turn + duration
        sim.state.relation(a.id, b.id).treaties[treaty] = until
        shift(sim, a.id, b.id, 10.0)
        sim.log(EventKind.DIPLOMACY, "${a.name}와(과) ${b.name}이(가) ${treaty.label} 조약을 맺었다", a.id, b.id)
    }

    fun cancel(sim: Simulation, a: Nation, b: Nation, treaty: Treaty) {
        if (sim.state.relation(a.id, b.id).treaties.remove(treaty) != null) {
            shift(sim, a.id, b.id, -15.0)
            sim.log(EventKind.DIPLOMACY, "${a.name}이(가) ${b.name}와(과)의 ${treaty.label} 조약을 파기했다", a.id, b.id)
        }
    }
}
