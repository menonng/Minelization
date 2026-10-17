package com.grandstrategy.sim

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** One pattern layer of a banner: a vanilla banner pattern id (without namespace) in a dye color (0-15). */
@Serializable
data class FlagLayer(val pattern: String, val color: Int)

/** A nation's flag as a Minecraft banner: base dye color plus up to six pattern layers. */
@Serializable
data class Flag(val base: Int, val layers: List<FlagLayer> = emptyList())

/**
 * Random flags that look like flags: built from a few heraldic layouts (tricolours, crosses, an emblem on a field,
 * diagonals, bordered emblems) in colours that contrast with the nation's own colour, which stays the field.
 */
object Flags {
    const val WHITE = 0
    const val ORANGE = 1
    const val YELLOW = 4
    const val LIGHT_BLUE = 3
    const val GRAY = 7
    const val LIGHT_GRAY = 8
    const val CYAN = 9
    const val PURPLE = 10
    const val BLUE = 11
    const val GREEN = 13
    const val RED = 14
    const val BLACK = 15

    private val EMBLEMS = listOf("circle", "rhombus", "flower", "globe", "triangle_top", "skull", "creeper", "piglin")
    private val MAJOR = listOf(WHITE, YELLOW, RED, BLUE, BLACK, GREEN, LIGHT_BLUE, ORANGE, PURPLE, CYAN)

    /** Rough lightness of a dye, to keep layers readable against the field. */
    private val light = intArrayOf(10, 6, 5, 7, 9, 7, 7, 3, 7, 4, 3, 2, 3, 3, 3, 0)

    fun random(seed: Long, base: Int): Flag {
        val rng = Random(seed)
        val field = base.coerceIn(0, 15)
        val contrast = MAJOR.filter { kotlin.math.abs(light[it] - light[field]) >= 3 && it != field }.ifEmpty { listOf(WHITE, BLACK) }
        fun pick(except: Int = -1): Int = contrast.filter { it != except }.ifEmpty { contrast }.let { it[rng.nextInt(it.size)] }
        val a = pick()
        val b = pick(a)
        val emblem = EMBLEMS.take(5)[rng.nextInt(5)]
        val layers = when (rng.nextInt(6)) {
            0 -> listOf(FlagLayer("stripe_left", a), FlagLayer("stripe_right", b))
            1 -> listOf(FlagLayer("stripe_top", a), FlagLayer("stripe_bottom", b))
            2 -> listOf(FlagLayer(if (rng.nextBoolean()) "straight_cross" else "cross", a)) +
                (if (rng.nextBoolean()) listOf(FlagLayer("border", b)) else emptyList())
            3 -> listOf(FlagLayer(if (rng.nextBoolean()) "half_horizontal" else "half_vertical", a), FlagLayer(emblem, b))
            4 -> listOf(FlagLayer(if (rng.nextBoolean()) "diagonal_left" else "diagonal_right", a), FlagLayer("stripe_center", b))
            else -> listOf(FlagLayer(if (rng.nextBoolean()) "border" else "curly_border", a), FlagLayer(emblem, b)) +
                (if (rng.nextInt(3) == 0) listOf(FlagLayer("stripe_middle", a)) else emptyList())
        }
        return Flag(field, layers)
    }

    const val MAX_LAYERS = 6
}
