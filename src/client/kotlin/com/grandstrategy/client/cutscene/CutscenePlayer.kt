package com.grandstrategy.client.cutscene

import com.grandstrategy.net.CutsceneDonePayload
import com.grandstrategy.net.CutsceneSpec
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.slf4j.LoggerFactory

object CutscenePlayer {
    private val logger = LoggerFactory.getLogger("Grand Strategy Cutscene")
    private val capture = System.getProperty("grandstrategy.capture") == "true"
    private const val CAPTURE_EVERY = 0.75

    private var scene: Scene? = null
    private var pending: Scene? = null
    private var pendingSince = 0L
    private var readySince = 0L
    private var startedAt = 0L
    private var lastTick = 0.0
    private var nextCapture = 0.0
    private var captured = 0

    val active: Boolean get() = scene != null || pending != null

    fun elapsed(): Double = (System.nanoTime() - startedAt) / 1e9

    fun start(spec: CutsceneSpec) {
        val mc = Minecraft.getInstance()
        scene = null
        pending = Scene.create(spec)
        pendingSince = System.nanoTime()
        readySince = 0L
        mc.gui.setScreen(CutsceneScreen())
        logger.info("Preparing {} cutscene for {}", spec.kind, spec.nation)
    }

    private fun begin(s: Scene) {
        pending = null
        scene = s
        startedAt = System.nanoTime()
        lift = 0.0
        liftAt = 0L
        lastTick = 0.0
        nextCapture = 0.4
        captured = 0
        logger.info("Playing {} cutscene for {}", s.spec.kind, s.spec.nation)
    }

    private fun chunksReady(s: Scene): Boolean {
        if (!s.needsWorld) return true
        val level = Minecraft.getInstance().level ?: return false
        val cx = kotlin.math.floor(s.spec.x / 16).toInt()
        val cz = kotlin.math.floor(s.spec.z / 16).toInt()
        for (dx in -3..3) for (dz in -3..3) {
            val chunk = level.chunkSource.getChunk(cx + dx, cz + dz, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false)
            if (chunk == null || chunk is net.minecraft.world.level.chunk.EmptyLevelChunk) return false
        }
        return true
    }

    @JvmStatic
    fun cameraPose(): DoubleArray? {
        val s = scene ?: return null
        val (raw, look) = s.camera(elapsed().coerceAtMost(s.duration)) ?: return null
        val eye = lift(raw)
        val (yaw, pitch) = CameraPath.angles(eye, look)
        return doubleArrayOf(eye.x, eye.y, eye.z, yaw.toDouble(), pitch.toDouble())
    }

    private var lift = 0.0
    private var liftAt = 0L

    private fun lift(eye: net.minecraft.world.phys.Vec3): net.minecraft.world.phys.Vec3 {
        val level = Minecraft.getInstance().level ?: return eye
        if (elapsed() < 0.15) return eye
        var floor = Double.NEGATIVE_INFINITY
        for (dx in -1..1) for (dz in -1..1) {
            val h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                kotlin.math.floor(eye.x).toInt() + dx, kotlin.math.floor(eye.z).toInt() + dz)
            floor = maxOf(floor, h + 1.8)
        }
        val now = System.nanoTime()
        val dt = if (liftAt == 0L) 0.0 else ((now - liftAt) / 1e9).coerceIn(0.0, 0.1)
        liftAt = now
        val target = (floor - eye.y).coerceAtLeast(0.0)
        lift += (target - lift) * (1 - kotlin.math.exp(-dt * 6))
        if (lift < target - 1.5) lift = target - 1.5
        return net.minecraft.world.phys.Vec3(eye.x, eye.y + lift, eye.z)
    }

    fun tick() {
        pending?.let { p ->
            val now = System.nanoTime()
            if (readySince == 0L && chunksReady(p)) readySince = now
            val waited = (now - pendingSince) / 1e9
            if ((readySince != 0L && (now - readySince) / 1e9 > 0.8) || waited > 6.0) begin(p)
            return
        }
        val s = scene ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level == null || mc.player == null) {
            stop(false)
            return
        }
        val t = elapsed()
        s.tick(t, lastTick, level)
        lastTick = t
        if (capture && t >= nextCapture && t < s.duration) {
            nextCapture += CAPTURE_EVERY
            val name = "cutscene_${s.spec.kind.name.lowercase()}_%02d.png".format(captured++)
            Screenshot.grab(mc.gameDirectory, name, mc.gameRenderer.mainRenderTarget(), 1) { }
        }
        if (t >= s.duration) stop(true)
    }

    fun overlay(g: GuiGraphicsExtractor) {
        if (pending != null) {
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), 0xFF000000.toInt())
            return
        }
        val s = scene ?: return
        s.overlay(g, Minecraft.getInstance().font, elapsed())
    }

    fun skip() {
        if (scene != null && elapsed() > 1.0) stop(true)
    }

    fun stop(notify: Boolean) {
        val s = scene ?: pending ?: return
        scene = null
        pending = null
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() is CutsceneScreen) mc.gui.setScreen(null)
        if (notify && mc.player != null) ClientPlayNetworking.send(CutsceneDonePayload(s.spec.id))
    }
}

class CutsceneScreen : Screen(Component.empty()) {
    override fun isPauseScreen(): Boolean = false

    override fun shouldCloseOnEsc(): Boolean = false

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {}

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        CutscenePlayer.overlay(graphics)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == 256) CutscenePlayer.skip()
        return true
    }

    override fun onClose() {
        if (CutscenePlayer.active) return
        super.onClose()
    }
}
