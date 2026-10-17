package com.grandstrategy.sim

import kotlin.math.abs

/**
 * Town districts. A nation's buildings gather around its town cores (plazas); once every core holds
 * [RoadTuning.districtBuildings] buildings and the land supports another (one per [RoadTuning.chunksPerDistrict]
 * claimed chunks), a new district is founded out in its land so people spread over the country instead of all
 * crowding the capital. The road planner lays the district's plaza and highway; this part decides when and where.
 */
object Districts {
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

    /** Town cores the nation's land supports, the capital's included. */
    fun allowed(nation: Nation, tuning: RoadTuning): Int = 1 + nation.claims.size / tuning.chunksPerDistrict.coerceAtLeast(1)

    /** True when every town core is full and the land has room for one more. */
    fun due(state: WorldState, nation: Nation, tuning: RoadTuning): Boolean {
        val hubs = hubs(state, nation)
        if (hubs.size >= allowed(nation, tuning)) return false
        return hubs.all { crowd(state, nation, it, tuning.districtRadius) >= tuning.districtBuildings }
    }

    /**
     * Own chunks a new district could go in, best first: not water or mountain, at least [RoadTuning.districtSpacing]
     * chunks from every core and at most [RoadTuning.districtReach] from the nearest, with no building on or beside it.
     * Towns the nation owns and roomy land rank first, then nearness; [jitter] adds a little randomness.
     */
    fun candidates(state: WorldState, nation: Nation, tuning: RoadTuning, terrain: (Long) -> Terrain, jitter: () -> Double = { 0.0 }): List<Long> {
        val hubs = hubs(state, nation).map { Chunks.pack(it.first shr 4, it.second shr 4) }
        val boxes = state.buildings.values.map { it.box }
        val capacity = Balance.config.capacity.perChunk
        return nation.claims.filter { c ->
            val t = terrain(c)
            t != Terrain.WATER && t != Terrain.MOUNTAIN &&
                hubs.all { Chunks.distance(c, it) >= tuning.districtSpacing } && hubs.any { Chunks.distance(c, it) <= tuning.districtReach } &&
                boxes.none { b -> (b.minX shr 4) - 1 <= Chunks.x(c) && Chunks.x(c) <= (b.maxX shr 4) + 1 && (b.minZ shr 4) - 1 <= Chunks.z(c) && Chunks.z(c) <= (b.maxZ shr 4) + 1 }
        }.map { c ->
            // Scored once up front: a random term inside the sort's comparator would make the order inconsistent.
            c to (capacity[terrain(c)] ?: 1.0) + (if (state.settlementLand[c] != null) 3.0 else 0.0) - hubs.minOf { Chunks.distance(c, it) } * 0.15 + jitter()
        }.sortedByDescending { it.second }.map { it.first }
    }
}
