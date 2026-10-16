package com.grandstrategy.client.cutscene

import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

data class Shot(val time: Double, val eye: Vec3, val look: Vec3)

class CameraPath(private val shots: List<Shot>) {
    fun at(t: Double): Pair<Vec3, Vec3> {
        if (t <= shots.first().time) return shots.first().eye to shots.first().look
        if (t >= shots.last().time) return shots.last().eye to shots.last().look
        val i = shots.indexOfLast { it.time <= t }.coerceIn(0, shots.size - 2)
        val a = shots[i]
        val b = shots[i + 1]
        val u = Ease.inOutSine((t - a.time) / (b.time - a.time))
        val p0 = shots[(i - 1).coerceAtLeast(0)]
        val p3 = shots[(i + 2).coerceAtMost(shots.size - 1)]
        return catmull(p0.eye, a.eye, b.eye, p3.eye, u) to catmull(p0.look, a.look, b.look, p3.look, u)
    }

    private fun catmull(p0: Vec3, p1: Vec3, p2: Vec3, p3: Vec3, t: Double): Vec3 {
        val t2 = t * t
        val t3 = t2 * t
        fun axis(a: Double, b: Double, c: Double, d: Double) =
            0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3)
        return Vec3(axis(p0.x, p1.x, p2.x, p3.x), axis(p0.y, p1.y, p2.y, p3.y), axis(p0.z, p1.z, p2.z, p3.z))
    }

    companion object {
        fun angles(eye: Vec3, look: Vec3): Pair<Float, Float> {
            val dx = look.x - eye.x
            val dy = look.y - eye.y
            val dz = look.z - eye.z
            val yaw = Math.toDegrees(atan2(dz, dx)) - 90.0
            val pitch = -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz)))
            return yaw.toFloat() to pitch.toFloat()
        }
    }
}

object Ease {
    fun clamp(t: Double) = t.coerceIn(0.0, 1.0)
    fun inOutSine(t: Double) = -(kotlin.math.cos(Math.PI * clamp(t)) - 1) / 2
    fun outCubic(t: Double): Double { val u = 1 - clamp(t); return 1 - u * u * u }
    fun inCubic(t: Double): Double { val u = clamp(t); return u * u * u }
    fun window(t: Double, start: Double, fadeIn: Double, end: Double, fadeOut: Double): Double = when {
        t < start || t > end -> 0.0
        t < start + fadeIn -> outCubic((t - start) / fadeIn)
        t > end - fadeOut -> 1 - inCubic((t - (end - fadeOut)) / fadeOut)
        else -> 1.0
    }
}
