package com.grandstrategy.sim

import kotlin.random.Random

fun interface TerrainProvider {
    fun terrain(chunk: Long): Terrain
}

sealed interface Cinematic {
    data class Founded(val nation: Int, val ruler: String) : Cinematic
    data class CapitalFell(val defender: Int, val attacker: Int, val chunk: Long) : Cinematic
    data class Destroyed(val nation: Int, val by: Int?, val capital: Long) : Cinematic
}

fun interface PresenceProvider {
    fun playersNear(nation: Int, chunk: Long): Int
}

class Simulation(val state: WorldState, private val terrainProvider: TerrainProvider) {
    private val terrainCache = HashMap<Long, Terrain>()
    private val listeners = mutableListOf<(GameEvent) -> Unit>()
    private val cinematicListeners = mutableListOf<(Cinematic) -> Unit>()
    internal val borders = HashMap<Long, Int>()
    internal val aiMemory = HashMap<Long, Long>()

    var presence: PresenceProvider = PresenceProvider { _, _ -> 0 }
    var construction: ((Nation, BuildingKind) -> Outcome)? = null
    var minNations = 4
    var spawnCenter = 0L
    var spawnRadius = 60
    var rng: Random = Random(state.seed)
        private set

    fun onEvent(listener: (GameEvent) -> Unit) {
        listeners += listener
    }

    fun onCinematic(listener: (Cinematic) -> Unit) {
        cinematicListeners += listener
    }

    fun cinematic(event: Cinematic) {
        cinematicListeners.forEach { it(event) }
    }

    fun terrain(chunk: Long): Terrain = terrainCache.getOrPut(chunk) { terrainProvider.terrain(chunk) }

    fun log(kind: EventKind, text: String, vararg nations: Int) {
        val event = GameEvent(state.turn, kind, text, nations.toList())
        state.events += event
        if (state.events.size > Balance.MAX_EVENTS) {
            state.events.subList(0, state.events.size - Balance.MAX_EVENTS).clear()
        }
        listeners.forEach { it(event) }
    }

    fun ownerOf(chunk: Long): Nation? = state.nation(state.owners[chunk])

    fun settlementAt(chunk: Long): Settlement? = state.settlementLand[chunk]?.let { state.settlements[it] }

    fun turn() {
        state.turn++
        rng = Random(state.seed xor (state.turn * 0x9E3779B97F4A7C15uL.toLong()))
        computeBorders()
        Economy.run(this)
        Progress.run(this)
        Population.run(this)
        Leaders.run(this)
        Settlements.run(this)
        Diplomacy.run(this)
        Ai.run(this)
        Warfare.run(this)
        Politics.run(this)
        Districts.ensure(this)
        expireProposals()
    }

    fun computeBorders() {
        borders.clear()
        for (nation in state.alive()) {
            for (chunk in nation.claims) {
                for (n in Chunks.neighbors(chunk)) {
                    val other = state.owners[n] ?: continue
                    if (other != nation.id) borders.merge(WorldState.pairKey(nation.id, other), 1, Int::plus)
                }
            }
        }
    }

    fun borderLength(a: Int, b: Int): Int = (borders[WorldState.pairKey(a, b)] ?: 0) / 2

    fun neighbors(nation: Int): List<Nation> = state.alive().filter { it.id != nation && borderLength(nation, it.id) > 0 }

    fun strength(nation: Nation): Double {
        var s = nation.military
        s *= 1.0 + 0.1 * nation.level(TechBranch.MILITARY)
        s *= 1.0 + 0.05 * nation.count(Facility.BARRACKS)
        s *= 1.0 + 0.05 * nation.level(TechBranch.LOGISTICS)
        s *= 0.7 + 0.3 * nation.stability / 100.0
        if (nation.has(Resource.IRON) <= 0.0) s *= 0.8
        if (nation.has(Resource.FOOD) <= 0.0) s *= 0.6
        if (nation.policy == Policy.MILITARY) s *= 1.1
        s *= Content.militaryFactor(nation) * Leaders.militaryFactor(nation)
        return s
    }

    fun foundingError(chunk: Long): String? {
        if (!terrain(chunk).claimable) return "바다에는 나라를 세울 수 없다."
        if (state.owners.containsKey(chunk)) return "이미 다른 나라의 영토다."
        if (state.settlementLand.containsKey(chunk)) return "자유 마을의 땅이다."
        val tooClose = state.alive().any { n -> n.claims.any { Chunks.distance(it, chunk) < 6 } }
        if (tooClose) return "다른 나라와 너무 가깝다 (6청크 이상 떨어져야 한다)."
        return null
    }

    fun createNation(name: String, capital: Long, ruler: String? = null, color: Int? = null): Nation {
        val used = state.alive().map { it.color }.toSet()
        val chosen = color ?: (0 until 16).shuffled(rng).firstOrNull { it !in used } ?: rng.nextInt(16)
        val nation = Nation(
            id = state.nextNationId++,
            name = name,
            color = chosen,
            capital = capital,
            ruler = ruler,
            personality = Personality(rng.nextDouble(), rng.nextDouble(), rng.nextDouble()),
            foundedTurn = state.turn
        )
        nation.flag = Flags.random(rng.nextLong(), chosen)
        nation.era = Content.config.era(nation).id
        state.nations[nation.id] = nation
        for (other in state.alive()) {
            if (other.id != nation.id) state.relation(nation.id, other.id).opinion = rng.nextDouble(-15.0, 25.0)
        }
        for (chunk in Chunks.around(capital, Balance.START_RADIUS)) {
            if (terrain(chunk).claimable && !state.owners.containsKey(chunk) && !state.settlementLand.containsKey(chunk)) {
                assign(chunk, nation)
            }
        }
        assign(capital, nation)
        ruler?.let { nation.citizens += it }
        log(EventKind.MAJOR, "${nation.name} 건국 ${Chunks.format(capital)}", nation.id)
        ruler?.let { cinematic(Cinematic.Founded(nation.id, it)) }
        return nation
    }

    fun assign(chunk: Long, nation: Nation) {
        val previous = state.owners[chunk]
        if (previous == nation.id) return
        previous?.let { state.nations[it]?.claims?.remove(chunk) }
        Districts.lost(this, chunk)
        state.owners[chunk] = nation.id
        nation.claims += chunk
        Districts.claimed(this, chunk, nation)
    }

    fun release(chunk: Long) {
        val previous = state.owners.remove(chunk) ?: return
        state.nations[previous]?.claims?.remove(chunk)
        Districts.lost(this, chunk)
    }

    fun destroy(nation: Nation, reason: String, by: Int? = null) {
        val capital = nation.capital
        nation.alive = false
        for (chunk in nation.claims.toList()) state.owners.remove(chunk)
        nation.claims.clear()
        Districts.dissolve(state, nation)
        for (settlement in state.settlements.values) {
            if (settlement.owner == nation.id) {
                settlement.owner = null
                settlement.loyalty = 100.0
            }
            settlement.influence.remove(nation.id)
        }
        state.proposals.values.removeIf { it.from == nation.id || it.to == nation.id }
        log(EventKind.MAJOR, "${nation.name} 멸망: $reason", nation.id)
        cinematic(Cinematic.Destroyed(nation.id, by, capital))
    }

    fun seedNations(count: Int, center: Long, radius: Int, attempts: Int = 400): Int {
        var created = 0
        var tries = 0
        while (created < count && tries < attempts) {
            tries++
            val chunk = Chunks.pack(
                Chunks.x(center) + rng.nextInt(-radius, radius + 1),
                Chunks.z(center) + rng.nextInt(-radius, radius + 1)
            )
            if (foundingError(chunk) != null) continue
            val landAround = Chunks.around(chunk, 2).count { terrain(it).claimable }
            if (landAround < 18) continue
            createNation(Names.nation(rng), chunk)
            created++
        }
        return created
    }

    fun propose(from: Nation, to: Nation, type: String): Proposal {
        val proposal = Proposal(state.nextProposalId++, from.id, to.id, type, state.turn)
        state.proposals[proposal.id] = proposal
        return proposal
    }

    private fun expireProposals() {
        state.proposals.values.removeIf { state.turn - it.createdTurn > Balance.PROPOSAL_TTL }
    }
}
