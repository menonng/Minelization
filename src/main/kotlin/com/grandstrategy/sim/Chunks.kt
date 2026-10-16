package com.grandstrategy.sim

import kotlin.math.abs
import kotlin.math.max

object Chunks {
    fun pack(x: Int, z: Int): Long = (x.toLong() and 0xffffffffL) or ((z.toLong() and 0xffffffffL) shl 32)

    fun x(key: Long): Int = (key and 0xffffffffL).toInt()

    fun z(key: Long): Int = (key ushr 32 and 0xffffffffL).toInt()

    fun neighbors(key: Long): LongArray {
        val x = x(key)
        val z = z(key)
        return longArrayOf(pack(x + 1, z), pack(x - 1, z), pack(x, z + 1), pack(x, z - 1))
    }

    fun distance(a: Long, b: Long): Int = max(abs(x(a) - x(b)), abs(z(a) - z(b)))

    fun around(center: Long, radius: Int): List<Long> {
        val cx = x(center)
        val cz = z(center)
        val result = ArrayList<Long>((2 * radius + 1) * (2 * radius + 1))
        for (dx in -radius..radius) for (dz in -radius..radius) result.add(pack(cx + dx, cz + dz))
        return result
    }

    fun format(key: Long): String = "[${x(key)}, ${z(key)}]"
}
