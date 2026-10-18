package com.grandstrategy.sim

import kotlin.math.abs
import kotlin.math.min

/**
 * Administrative districts. Every chunk a nation owns belongs to one district, run from a seat. A district holds at
 * most [limit] chunks, which grows with administration and logistics research; a chunk the nation gains joins a
 * neighbouring district with room, and when every neighbouring district is full it starts a new one. Each district
 * that has grown to [DistrictTuning.coreAtChunks] chunks gets its own town core (a plaza near its seat, laid by the road
 * planner), so new houses, and the people in them, spread over the country instead of crowding the capital.
 */
object Districts {
    private val tuning get() = Balance.config.districts

    /** Chunks one district of this nation can hold. */
    fun limit(nation: Nation): Int {
        val t = tuning
        val bonus = t.perAdministration * nation.level(TechBranch.ADMINISTRATION) + t.perLogistics * nation.level(TechBranch.LOGISTICS)
        return min(t.maxChunks, t.baseChunks + bonus.toInt())
    }

    fun of(state: WorldState, chunk: Long): District? = state.districtOf[chunk]?.let { state.districts[it] }

    fun ofNation(state: WorldState, nation: Nation): List<District> = state.districts.values.filter { it.nation == nation.id }

    /** The district the capital is in. */
    fun capital(state: WorldState, nation: Nation): District? = of(state, nation.capital)?.takeIf { it.nation == nation.id }

    /** A chunk has just become [nation]'s: it joins the nearest neighbouring district with room, or founds a new one. */
    fun claimed(sim: Simulation, chunk: Long, nation: Nation) {
        val state = sim.state
        if (state.districtOf.containsKey(chunk)) return
        val limit = limit(nation)
        val join = Chunks.neighbors(chunk).asSequence()
            .mapNotNull { of(state, it) }
            .filter { it.nation == nation.id && it.size < limit }
            .distinct()
            .minByOrNull { Chunks.distance(it.seat, chunk) }
        if (join != null) {
            state.districtOf[chunk] = join.id
            join.size++
            return
        }
        val first = state.districts.values.none { it.nation == nation.id }
        // A nation's first district is run from its capital; later ones from the chunk that started them.
        val seat = if (first) nation.capital else chunk
        val district = District(state.nextDistrictId++, nation.id, name(sim, nation, seat, first), seat, size = 1, foundedTurn = state.turn)
        state.districts[district.id] = district
        state.districtOf[chunk] = district.id
        if (!first) sim.log(EventKind.INFO, "${nation.name}: 행정구역이 가득 차 새 행정구역 ${district.name}을(를) 두었다 (구역당 ${limit}청크)", nation.id)
    }

    /** A chunk left its owner: it leaves its district; an emptied district is dropped, one that lost its seat moves it. */
    fun lost(sim: Simulation, chunk: Long) {
        val state = sim.state
        val id = state.districtOf.remove(chunk) ?: return
        val district = state.districts[id] ?: return
        district.size--
        if (district.size <= 0) {
            state.districts.remove(id)
            return
        }
        if (district.seat == chunk) reseat(state, district)
    }

    /** All of a nation's districts go when it does. */
    fun dissolve(state: WorldState, nation: Nation) {
        val ids = state.districts.values.filter { it.nation == nation.id }.map { it.id }.toSet()
        if (ids.isEmpty()) return
        state.districts.keys.removeAll(ids)
        state.districtOf.values.removeAll(ids)
    }

    /** Moves a district's seat to the chunk of it nearest the old seat (or the capital, if the capital is in it). */
    private fun reseat(state: WorldState, district: District) {
        val nation = state.nations[district.nation] ?: return
        if (state.districtOf[nation.capital] == district.id) {
            district.seat = nation.capital
            return
        }
        val old = district.seat
        district.seat = nation.claims.filter { state.districtOf[it] == district.id }.minByOrNull { Chunks.distance(it, old) } ?: return
    }

    /**
     * Keeps the districts in step with the land, once per turn: claims from before districts existed (or missed by an
     * edit) are given one, sizes are recounted now and then, and each district that holds the capital is run from it.
     */
    fun ensure(sim: Simulation) {
        val state = sim.state
        for (nation in state.alive()) {
            if (nation.claims.any { !state.districtOf.containsKey(it) }) {
                // Grow outward from the capital so old saves get compact districts, not a scatter.
                val missing = nation.claims.filter { !state.districtOf.containsKey(it) }.sortedBy { Chunks.distance(it, nation.capital) }
                var pending = missing
                repeat(4) {
                    val next = ArrayList<Long>()
                    for (c in pending) {
                        if (state.districtOf.containsKey(c)) continue
                        val hasNeighbour = Chunks.neighbors(c).any { n -> of(state, n)?.nation == nation.id }
                        if (hasNeighbour || state.districts.values.none { it.nation == nation.id }) claimed(sim, c, nation) else next += c
                    }
                    pending = next
                }
                for (c in pending) claimed(sim, c, nation)
            }
            capital(state, nation)?.let { if (it.seat != nation.capital) it.seat = nation.capital }
        }
        if (state.turn % 50 == 0L) recount(state)
    }

    private fun recount(state: WorldState) {
        val counts = HashMap<Int, Int>()
        for (id in state.districtOf.values) counts.merge(id, 1, Int::plus)
        state.districts.values.removeIf { (counts[it.id] ?: 0) == 0 }
        for (d in state.districts.values) d.size = counts[d.id] ?: 0
    }

    private fun name(sim: Simulation, nation: Nation, seat: Long, first: Boolean): String {
        if (first) return "수도 구역"
        sim.state.settlementLand[seat]?.let { id -> sim.state.settlements[id]?.let { return it.name } }
        val used = sim.state.districts.values.filter { it.nation == nation.id }.map { it.name }.toSet()
        repeat(8) {
            val name = Names.district(sim.rng)
            if (name !in used) return name
        }
        return Names.district(sim.rng)
    }

    // ---- Town cores ----

    /** Centres (block coordinates) of the nation's town cores: its plazas, or the capital's chunk before it has one. */
    fun hubs(state: WorldState, nation: Nation): List<Pair<Int, Int>> {
        val plazas = state.roads.values.filter { it.nation == nation.id && it.kind == RoadKind.PLAZA && it.cells.isNotEmpty() }.map { r ->
            r.cells.sumOf { Chunks.x(it) } / r.cells.size to r.cells.sumOf { Chunks.z(it) } / r.cells.size
        }
        return plazas.ifEmpty { listOf(Chunks.x(nation.capital) * 16 + 8 to Chunks.z(nation.capital) * 16 + 8) }
    }

    /** The nation's buildings (walls left out) within [radius] blocks of a town core. */
    fun crowd(state: WorldState, nation: Nation, hub: Pair<Int, Int>, radius: Int): Int = state.buildings.values.count {
        it.nation == nation.id && it.kind != BuildingKind.WALLS &&
            abs(it.box.centerX - hub.first) < radius && abs(it.box.centerZ - hub.second) < radius
    }

    /** True when a plaza of the nation lies in the district. */
    fun hasCore(state: WorldState, district: District): Boolean = state.roads.values.any { road ->
        road.nation == district.nation && road.kind == RoadKind.PLAZA && road.cells.isNotEmpty() &&
            state.districtOf[Chunks.pack(Chunks.x(road.cells[0]) shr 4, Chunks.z(road.cells[0]) shr 4)] == district.id
    }

    /** Districts big enough for a town core that do not have one yet, largest first. */
    fun needingCore(state: WorldState, nation: Nation): List<District> =
        ofNation(state, nation).filter { it.size >= tuning.coreAtChunks && !hasCore(state, it) }.sortedByDescending { it.size }

    /**
     * Chunks of [district] its town core could go in, best first: not water or mountain, with no building on or beside
     * it; the seat and towns first, then roomy land near the seat. [jitter] adds a little randomness.
     */
    fun coreSpots(state: WorldState, district: District, terrain: (Long) -> Terrain, jitter: () -> Double = { 0.0 }): List<Long> {
        val nation = state.nations[district.nation] ?: return emptyList()
        val boxes = state.buildings.values.map { it.box }
        val capacity = Balance.config.capacity.perChunk
        return nation.claims.filter { c ->
            val t = terrain(c)
            state.districtOf[c] == district.id && t != Terrain.WATER && t != Terrain.MOUNTAIN &&
                boxes.none { b -> (b.minX shr 4) - 1 <= Chunks.x(c) && Chunks.x(c) <= (b.maxX shr 4) + 1 && (b.minZ shr 4) - 1 <= Chunks.z(c) && Chunks.z(c) <= (b.maxZ shr 4) + 1 }
        }.map { c ->
            // Scored once up front: a random term inside the sort's comparator would make the order inconsistent.
            c to (if (c == district.seat) 6.0 else 0.0) + (if (state.settlementLand[c] != null) 3.0 else 0.0) + (capacity[terrain(c)] ?: 1.0) -
                Chunks.distance(c, district.seat) * 0.5 + jitter()
        }.sortedByDescending { it.second }.map { it.first }
    }
}
