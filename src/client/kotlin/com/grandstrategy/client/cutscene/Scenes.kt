package com.grandstrategy.client.cutscene

import com.grandstrategy.net.CutsceneKind
import com.grandstrategy.net.CutsceneSpec
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

abstract class Scene(val spec: CutsceneSpec) {
    val duration: Double get() = spec.kind.seconds
    open val needsWorld: Boolean get() = true
    protected val center = Vec3(spec.x, spec.y, spec.z)
    protected val random = Random(spec.id)

    open fun camera(t: Double): Pair<Vec3, Vec3>? = null
    open fun tick(t: Double, previous: Double, level: ClientLevel) {}
    abstract fun overlay(g: GuiGraphicsExtractor, font: Font, t: Double)

    protected fun cue(at: Double, t: Double, previous: Double) = at > previous && at <= t

    protected fun sound(event: SoundEvent, pitch: Float = 1f, volume: Float = 1f) {
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(event, pitch, volume))
    }

    protected fun particle(level: ClientLevel, options: ParticleOptions, x: Double, y: Double, z: Double, dx: Double = 0.0, dy: Double = 0.0, dz: Double = 0.0) {
        level.addAlwaysVisibleParticle(options, true, x, y, z, dx, dy, dz)
    }

    protected fun ground(level: ClientLevel, x: Double, z: Double): Double =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble()

    protected fun letterbox(t: Double) = Ease.window(t, 0.0, 0.9, duration, 0.9)

    protected fun canopy(level: ClientLevel, x: Double, z: Double): Double =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING, kotlin.math.floor(x).toInt(), kotlin.math.floor(z).toInt()).toDouble()

    protected fun clearOf(level: ClientLevel, shots: List<Shot>, keepFirst: Boolean): CameraPath = CameraPath(shots.mapIndexed { i, shot ->
        if (keepFirst && i == 0) return@mapIndexed shot
        var lowest = shot.eye.y
        for (dx in -1..1) for (dz in -1..1) lowest = maxOf(lowest, canopy(level, shot.eye.x + dx, shot.eye.z + dz) + 2.5)
        shot.copy(eye = Vec3(shot.eye.x, lowest, shot.eye.z))
    })

    protected fun lazyPath(shots: () -> List<Shot>, keepFirst: Boolean): () -> CameraPath? {
        var built: CameraPath? = null
        return {
            built ?: Minecraft.getInstance().level?.let { clearOf(it, shots(), keepFirst) }?.also { built = it }
        }
    }

    companion object {
        fun create(spec: CutsceneSpec): Scene = when (spec.kind) {
            CutsceneKind.FOUNDING -> FoundingScene(spec)
            CutsceneKind.CAPITAL_FALL -> CapitalFallScene(spec)
            CutsceneKind.DESTRUCTION -> DestructionScene(spec)
        }
    }
}

class FoundingScene(spec: CutsceneSpec) : Scene(spec) {
    private val start = Minecraft.getInstance().player?.let { it.getEyePosition(1f) } ?: center.add(0.0, 2.0, 0.0)
    private val focus = center.add(0.0, 1.5, 0.0)
    private val path = lazyPath({
        listOf(
            Shot(0.0, start, focus),
            Shot(2.6, center.add(7.0, 4.0, 7.0), focus),
            Shot(6.0, center.add(-14.0, 12.0, 10.0), focus),
            Shot(9.0, center.add(-16.0, 30.0, -20.0), center),
            Shot(12.0, center.add(4.0, 50.0, -26.0), center)
        )
    }, keepFirst = true)
    private val edges: List<Pair<Vec3, Vec3>> = borderEdges(spec.claims)

    override fun camera(t: Double) = path()?.at(t)

    override fun tick(t: Double, previous: Double, level: ClientLevel) {
        if (cue(0.1, t, previous)) sound(SoundEvents.BELL_RESONATE, 1f, 0.8f)
        if (cue(1.2, t, previous)) SoundEvents.GOAT_HORN_SOUND_VARIANTS.firstOrNull()?.let { sound(it.value(), 1f, 0.7f) }
        if (cue(3.4, t, previous)) sound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.9f)
        if (cue(4.0, t, previous) || cue(4.6, t, previous) || cue(5.3, t, previous)) {
            sound(SoundEvents.FIREWORK_ROCKET_TWINKLE, 0.9f + random.nextFloat() * 0.3f, 0.7f)
            burst(level, center.add(random.nextDouble(-5.0, 5.0), 14.0 + random.nextDouble(4.0), random.nextDouble(-5.0, 5.0)))
        }
        if (t > 1.6) {
            val rise = Ease.outCubic((t - 1.6) / 3.0) * 9.0
            val dust = DustParticleOptions(spec.color, 3.2f)
            for ((a, b) in edges) {
                var s = random.nextDouble() * 1.2
                while (s < 16.0) {
                    val p = a.add(b.subtract(a).scale(s / 16.0))
                    val base = canopy(level, p.x, p.z) + 0.3
                    particle(level, dust, p.x, base + random.nextDouble() * rise, p.z)
                    if (random.nextInt(6) == 0) particle(level, ParticleTypes.END_ROD, p.x, base + rise, p.z, 0.0, 0.02, 0.0)
                    s += 1.2
                }
            }
        }
        if (t in 1.0..9.0 && random.nextInt(2) == 0) {
            val angle = random.nextDouble(Math.PI * 2)
            particle(level, ParticleTypes.END_ROD, center.x + cos(angle) * 1.2, center.y + 0.3, center.z + sin(angle) * 1.2, 0.0, 0.08, 0.0)
        }
    }

    private fun burst(level: ClientLevel, at: Vec3) {
        repeat(60) {
            val v = Vec3(random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0), random.nextDouble(-1.0, 1.0)).normalize().scale(0.35)
            particle(level, DustParticleOptions(spec.color, 2.0f), at.x, at.y, at.z, v.x, v.y, v.z)
            particle(level, ParticleTypes.FIREWORK, at.x, at.y, at.z, v.x, v.y, v.z)
        }
    }

    override fun overlay(g: GuiGraphicsExtractor, font: Font, t: Double) {
        val w = g.guiWidth().toDouble()
        val h = g.guiHeight().toDouble()
        Draw.shade(g, 0x000000, Ease.window(t, -1.0, 0.0, 0.8, 0.8) * 0.6 + Ease.window(t, duration - 0.9, 0.9, duration + 1, 0.0))
        Draw.letterbox(g, letterbox(t))
        val label = Ease.window(t, 3.0, 0.8, 10.6, 1.0)
        val title = Ease.window(t, 3.3, 1.2, 10.6, 1.0)
        val sub = Ease.window(t, 4.6, 1.0, 10.6, 1.0)
        val cy = h * 0.40
        val unroll = Ease.outCubic((t - 3.0) / 1.0) * title
        val plaqueW = maxOf(220.0, font.width(spec.nation) * 3.0 + 80)
        Draw.plaque(g, w / 2, cy + 6, plaqueW * unroll, 92.0, true, title)
        if (unroll > 0.85) {
            Draw.ribbon(g, font, w / 2, cy - 40, 120.0, 0xC89A3A, Draw.spaced("건국"), label)
            val drift = (1 - Ease.outCubic((t - 3.3) / 1.6)) * 6
            Draw.icon(g, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BANNER.pick(net.minecraft.world.item.DyeColor.byId(spec.dye))),
                w / 2 - plaqueW / 2 + 24, cy - 2, 2.0, title)
            Draw.centered(g, font, Component.literal(spec.nation), w / 2 + 12, cy - drift, 3.0, Draw.ink(spec.color), title, shadow = false)
            Draw.rule(g, (w / 2 + 12).roundToInt(), (cy + 18).roundToInt(), (Ease.outCubic((t - 3.8) / 1.4) * (plaqueW / 2 - 50)).roundToInt(), 0xB08A4A, title)
            val line = if (spec.subject.isNotEmpty()) "${spec.turn}턴 · 통치자 ${spec.subject}" else "${spec.turn}턴"
            Draw.centered(g, font, Component.literal(line), w / 2 + 12, cy + 30, 1.0, 0x5A3A16, sub, shadow = false)
            if (spec.claims.isNotEmpty()) {
                Draw.centered(g, font, Component.literal("첫 영토 ${spec.claims.size}청크"), w / 2 + 12, cy + 42, 0.8, 0x7A6A54, Ease.window(t, 6.5, 1.0, 10.6, 1.0), shadow = false)
            }
        }
    }

    private fun borderEdges(claims: List<Long>): List<Pair<Vec3, Vec3>> {
        val set = claims.toHashSet()
        fun key(x: Int, z: Int) = (x.toLong() and 0xffffffffL) or ((z.toLong() and 0xffffffffL) shl 32)
        val out = ArrayList<Pair<Vec3, Vec3>>()
        for (c in claims) {
            val x = (c and 0xffffffffL).toInt()
            val z = (c ushr 32).toInt()
            val x0 = x * 16.0
            val z0 = z * 16.0
            if (key(x, z - 1) !in set) out += Vec3(x0, 0.0, z0) to Vec3(x0 + 16, 0.0, z0)
            if (key(x, z + 1) !in set) out += Vec3(x0, 0.0, z0 + 16) to Vec3(x0 + 16, 0.0, z0 + 16)
            if (key(x - 1, z) !in set) out += Vec3(x0, 0.0, z0) to Vec3(x0, 0.0, z0 + 16)
            if (key(x + 1, z) !in set) out += Vec3(x0 + 16, 0.0, z0) to Vec3(x0 + 16, 0.0, z0 + 16)
        }
        return out
    }
}

class CapitalFallScene(spec: CutsceneSpec) : Scene(spec) {
    private val focus = center.add(0.0, 2.0, 0.0)
    private val path = lazyPath({
        listOf(
            Shot(0.0, center.add(34.0, 26.0, 30.0), focus),
            Shot(3.2, center.add(12.0, 8.0, 9.0), focus),
            Shot(7.0, center.add(-10.0, 6.0, 12.0), focus),
            Shot(11.0, center.add(-3.0, 24.0, -8.0), center)
        )
    }, keepFirst = false)
    private val blasts = listOf(1.6, 2.3, 2.9, 4.2, 5.6, 7.4)

    override fun camera(t: Double): Pair<Vec3, Vec3>? {
        val (eye, look) = path()?.at(t) ?: return center.add(20.0, 20.0, 20.0) to center
        val shake = blasts.sumOf { b -> if (t >= b) kotlin.math.exp(-(t - b) * 5) else 0.0 } * 0.35
        if (shake < 0.001) return eye to look
        val s = Vec3(sin(t * 53) * shake, cos(t * 47) * shake, sin(t * 61 + 1) * shake)
        return eye.add(s) to look
    }

    override fun tick(t: Double, previous: Double, level: ClientLevel) {
        if (cue(0.2, t, previous)) SoundEvents.RAID_HORN.let { sound(it.value(), 0.9f, 1f) }
        for (b in blasts) if (cue(b, t, previous)) {
            sound(SoundEvents.GENERIC_EXPLODE.value(), 0.8f + random.nextFloat() * 0.3f, 0.8f)
            val at = center.add(random.nextDouble(-7.0, 7.0), 0.0, random.nextDouble(-7.0, 7.0))
            val y = ground(level, at.x, at.z) + 1
            particle(level, ParticleTypes.EXPLOSION_EMITTER, at.x, y, at.z)
            repeat(20) { particle(level, ParticleTypes.LAVA, at.x, y, at.z) }
        }
        if (cue(6.3, t, previous)) sound(SoundEvents.BELL_BLOCK, 0.5f, 1f)
        if (cue(8.3, t, previous)) sound(SoundEvents.BELL_BLOCK, 0.45f, 0.9f)
        if (t > 0.8) {
            repeat(6) {
                val a = random.nextDouble(Math.PI * 2)
                val r = random.nextDouble(2.0, 10.0)
                val x = center.x + cos(a) * r
                val z = center.z + sin(a) * r
                val y = ground(level, x, z) + 0.2
                particle(level, ParticleTypes.CAMPFIRE_COSY_SMOKE, x, y, z, 0.0, 0.06, 0.0)
                particle(level, ParticleTypes.FLAME, x, y, z, 0.0, 0.03, 0.0)
            }
            if (t > 4.5) {
                val enemy = DustParticleOptions(spec.otherColor, 1.8f)
                repeat(8) {
                    val a = random.nextDouble(Math.PI * 2)
                    val r = random.nextDouble(0.5, 3.0)
                    particle(level, enemy, center.x + cos(a) * r, center.y + random.nextDouble(0.0, 4.0), center.z + sin(a) * r, 0.0, 0.05, 0.0)
                }
            }
        }
    }

    override fun overlay(g: GuiGraphicsExtractor, font: Font, t: Double) {
        val w = g.guiWidth().toDouble()
        val h = g.guiHeight().toDouble()
        Draw.shade(g, 0x000000, Ease.window(t, -1.0, 0.0, 1.0, 1.0) + Ease.window(t, duration - 0.9, 0.9, duration + 1, 0.0))
        Draw.vignette(g, 0x5A0000, 0.55 * Ease.window(t, 0.5, 1.5, duration, 1.0))
        Draw.letterbox(g, letterbox(t))
        val title = Ease.window(t, 3.6, 0.5, 9.6, 1.0)
        val sub = Ease.window(t, 4.6, 1.0, 9.6, 1.0)
        val cy = h * 0.42
        val enemy = spec.other.ifEmpty { "적" }
        val message = "${spec.nation}의 수도가 ${enemy}에게 넘어갔다"
        val plaqueW = maxOf(240.0, font.width(message) + 60.0)
        Draw.plaque(g, w / 2, cy + 20, plaqueW, 76.0, false, title)
        val punch = 1 + (1 - Ease.outCubic((t - 3.6) / 0.5)) * 0.4
        Draw.ribbon(g, font, w / 2, cy - 24, 150.0 * punch, 0xA81E1E, Draw.spaced("수도 함락"), title)
        Draw.icon(g, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BANNER.pick(net.minecraft.world.item.DyeColor.byId(spec.otherDye))),
            w / 2 - plaqueW / 2 + 22, cy + 8, 1.5, sub)
        Draw.icon(g, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.BANNER.pick(net.minecraft.world.item.DyeColor.byId(spec.dye))),
            w / 2 + plaqueW / 2 - 22, cy + 8, 1.5, sub * 0.5)
        Draw.centered(g, font, Component.literal(message), w / 2, cy + 12, 1.0, 0xF0E0D0, sub)
        Draw.rule(g, (w / 2).roundToInt(), (cy + 24).roundToInt(), (Ease.outCubic((t - 4.0) / 1.0) * (plaqueW / 2 - 60)).roundToInt(), 0xC81E1E, title * 0.8)
        Draw.centered(g, font, Component.literal("${spec.turn}턴"), w / 2, cy + 36, 0.8, 0xA89A8A, sub)
    }
}

class DestructionScene(spec: CutsceneSpec) : Scene(spec) {
    private class Shard(var x: Double, var y: Double, var vx: Double, var vy: Double, val size: Int, val color: Int)

    private val shards = ArrayList<Shard>()
    private var shattered = false
    private var lastFrame = -1.0
    private val banner = ItemStack(Items.BANNER.pick(DyeColor.byId(spec.dye)))

    override val needsWorld: Boolean get() = false

    override fun tick(t: Double, previous: Double, level: ClientLevel) {
        if (cue(0.1, t, previous)) sound(SoundEvents.BELL_BLOCK, 0.5f, 1f)
        if (cue(1.6, t, previous)) sound(SoundEvents.BELL_BLOCK, 0.42f, 0.9f)
        if (cue(3.5, t, previous)) {
            sound(SoundEvents.GLASS_BREAK, 0.6f, 1f)
            sound(SoundEvents.WITHER_DEATH, 0.7f, 0.35f)
        }
    }

    override fun overlay(g: GuiGraphicsExtractor, font: Font, t: Double) {
        val w = g.guiWidth().toDouble()
        val h = g.guiHeight().toDouble()
        val dt = if (lastFrame < 0) 0.0 else (t - lastFrame).coerceIn(0.0, 0.1)
        lastFrame = t
        Draw.shade(g, 0x000000, 0.88 * Ease.window(t, 0.0, 1.0, duration, 1.0))
        Draw.letterbox(g, letterbox(t))
        val cx = w / 2
        val cy = h * 0.36
        val scale = 5.0
        val half = 8 * scale
        if (t < 3.5) {
            val shake = if (t > 2.3) (t - 2.3) * 2.2 else 0.0
            val pose = g.pose()
            pose.pushMatrix()
            pose.translate((cx + sin(t * 70) * shake).toFloat(), (cy + cos(t * 63) * shake).toFloat())
            pose.scale(scale.toFloat(), scale.toFloat())
            g.item(banner, -8, -8)
            pose.popMatrix()
        } else if (!shattered) {
            shattered = true
            val rgb = DyeColor.byId(spec.dye).textureDiffuseColor
            for (i in 0 until 90) {
                val x = cx + random.nextDouble(-half * 0.6, half * 0.6)
                val y = cy + random.nextDouble(-half, half)
                shards += Shard(x, y, random.nextDouble(-60.0, 60.0), random.nextDouble(-90.0, 10.0), random.nextInt(2, 6), if (i % 5 == 0) 0x3A2A1A else rgb)
            }
        }
        val fade = Ease.window(t, 3.5, 0.0, duration, 2.5)
        for (s in shards) {
            s.vy += 240 * dt
            s.x += s.vx * dt
            s.y += s.vy * dt
            g.fill(s.x.roundToInt(), s.y.roundToInt(), s.x.roundToInt() + s.size, s.y.roundToInt() + s.size, Draw.argb(s.color, fade))
        }
        val name = Ease.window(t, 1.0, 1.2, duration, 1.0)
        val ty = h * 0.62
        Draw.plaque(g, cx, ty + 18, maxOf(220.0, font.width(spec.nation) * 2.5 + 70), 96.0, false, name)
        Draw.centered(g, font, Component.literal(spec.nation), cx, ty, 2.5, spec.color, name)
        val strike = Ease.outCubic((t - 4.0) / 0.8)
        if (t > 4.0) {
            val half = font.width(spec.nation) * 2.5 / 2 * strike
            g.fill((cx - half).roundToInt(), ty.roundToInt() - 1, (cx + half).roundToInt(), ty.roundToInt() + 2, Draw.argb(0xB0B0B0, name))
        }
        Draw.centered(g, font, Component.literal(Draw.spaced("멸망")), cx, ty + 26, 1.4, 0xBBBBBB, Ease.window(t, 4.4, 1.0, duration, 1.0))
        val by = if (spec.other.isEmpty()) "${spec.turn}턴" else "${spec.other}에 의해 · ${spec.turn}턴"
        Draw.centered(g, font, Component.literal(by), cx, ty + 42, 0.9, 0x8A8A8A, Ease.window(t, 5.0, 1.0, duration, 1.0))
    }
}
