package com.grandstrategy.sim

import kotlin.math.floor

/**
 * Where a nation's people may go: its own chunks plus a one-chunk margin around them. Villagers never step out of it,
 * the walker treats it as a wall, and roads are only planned inside it. Rebuilt once per turn (claims change on turns).
 */
class NationZones(private val state: WorldState) {
    private val cache = HashMap<Int, HashSet<Long>>()
    private var turn = Long.MIN_VALUE

    fun zone(nation: Int): Set<Long>? {
        if (state.turn != turn) {
            cache.clear()
            turn = state.turn
        }
        cache[nation]?.let { return it }
        val claims = state.nations[nation]?.claims ?: return null
        if (claims.isEmpty()) return null
        val out = HashSet<Long>(claims.size * 3)
        for (c in claims) {
            val x = Chunks.x(c)
            val z = Chunks.z(c)
            for (dx in -1..1) for (dz in -1..1) out += Chunks.pack(x + dx, z + dz)
        }
        cache[nation] = out
        return out
    }

    /** True inside the zone, or when the nation has no land to speak of (then nobody is held back). */
    fun allowed(nation: Int, chunk: Long): Boolean = zone(nation)?.contains(chunk) ?: true

    fun allowedAt(nation: Int, x: Float, z: Float): Boolean =
        allowed(nation, Chunks.pack(floor(x).toInt() shr 4, floor(z).toInt() shr 4))
}
