package com.grandstrategy.sim

object Politics {
    fun run(sim: Simulation) {
        for (nation in sim.state.alive()) {
            if (nation.stability < 12 && nation.claims.size >= 12 && sim.rng.nextDouble() < 0.1) secede(sim, nation)
        }
        if (sim.state.seeded && sim.state.alive().size < sim.minNations && sim.rng.nextDouble() < 0.05) {
            sim.seedNations(1, sim.spawnCenter, sim.spawnRadius, attempts = 60)
        }
    }

    fun secede(sim: Simulation, parent: Nation): Nation? {
        val far = parent.claims.maxByOrNull { Chunks.distance(it, parent.capital) } ?: return null
        val limit = parent.claims.size / 3
        val region = LinkedHashSet<Long>()
        val queue = ArrayDeque<Long>()
        queue += far
        while (queue.isNotEmpty() && region.size < limit) {
            val chunk = queue.removeFirst()
            if (chunk in region || chunk == parent.capital || sim.state.owners[chunk] != parent.id) continue
            region += chunk
            for (n in Chunks.neighbors(chunk)) if (n !in region) queue += n
        }
        if (region.size < 4) return null

        val rebel = sim.createNation(Names.rebel(sim.rng), far)
        for (chunk in region) sim.assign(chunk, rebel)
        val share = region.size.toDouble() / (parent.claims.size + region.size)
        rebel.population = parent.population * share
        parent.population -= rebel.population
        rebel.military = parent.military * share * 1.2
        parent.military -= parent.military * share
        for (r in Resource.entries) rebel.stock[r] = parent.has(r) * share * 0.5
        rebel.stability = 55.0
        parent.stability = (parent.stability + 15).coerceAtMost(100.0)
        sim.state.relation(parent.id, rebel.id).opinion = -60.0
        sim.log(EventKind.MAJOR, "${parent.name}에서 내전이 일어나 ${rebel.name}이(가) 떨어져 나갔다", parent.id, rebel.id)
        sim.computeBorders()
        if (sim.borderLength(rebel.id, parent.id) > 0) Warfare.declare(sim, rebel, parent)
        return rebel
    }
}
