package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import com.grandstrategy.net.ServerMessagePayload
import com.grandstrategy.net.SetupOffer
import com.grandstrategy.net.SetupTier
import com.grandstrategy.net.ToClient
import com.grandstrategy.net.WorkJson
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Outcome
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSource
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerBossEvent
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.BossEvent
import net.minecraft.world.level.GameType
import net.minecraft.world.level.chunk.status.ChunkStatus
import java.util.UUID
import java.util.concurrent.CompletableFuture

enum class WorldTier(val size: Int, val nations: Int, val label: String) {
    T500(500, 3, "작은 섬"),
    T1000(1000, 4, "지방"),
    T2000(2000, 6, "왕국"),
    T3500(3500, 8, "대륙"),
    T5000(5000, 10, "세계");

    val chunks: Long get() = (size / 16L + 1) * (size / 16L + 1)

    companion object {
        fun of(size: Int) = entries.firstOrNull { it.size == size }
    }
}

object GodMode {
    @JvmStatic
    var enabled = true

    @JvmStatic
    fun protects(entity: net.minecraft.world.entity.Entity?): Boolean = enabled && entity is ServerPlayer
}

class WorldSetup(private val rt: Runtime) {
    private val settings get() = rt.settings
    private val level get() = rt.server.overworld()
    private val bar = ServerBossEvent(UUID.randomUUID(), Component.literal("미리 생성"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS)
    private val inflight = ArrayList<CompletableFuture<*>>()
    private var ticks = 0L
    private var chunkyProgress = 0.0
    private var chunkyIdle = 0
    private var startedAt = 0L
    private var startedIndex = 0L

    val chunkyAvailable: Boolean get() = FabricLoader.getInstance().isModLoaded("chunky")

    fun needed(): Boolean = !rt.state.seeded && settings.worldSize == 0

    fun start() {
        GodMode.enabled = settings.godMode
        if (settings.worldSize > 0) applyBorder()
        if (settings.pregen == PREGEN_BUILTIN || settings.pregen == PREGEN_CHUNKY) {
            startedAt = System.nanoTime()
            startedIndex = settings.pregenIndex
            if (settings.pregen == PREGEN_CHUNKY) runChunky(resume = true)
        }
        if (!needed()) rt.hooks.startScouting()
        else GrandStrategy.LOGGER.info("New world: waiting for an operator to choose a world size (/gs setup)")
    }

    fun apply(tier: WorldTier, pregenerate: Boolean, god: Boolean, by: String): Outcome {
        val first = !rt.state.seeded
        settings.worldSize = tier.size
        settings.godMode = god
        GodMode.enabled = god
        if (first) {
            settings.initialNations = tier.nations
            settings.seedRadiusChunks = ((tier.size / 2) / 16 * 0.7).toInt().coerceAtLeast(8)
            settings.minNations = (tier.nations * 2 / 3).coerceAtLeast(2)
            rt.sim.spawnRadius = settings.seedRadiusChunks
            rt.sim.minNations = settings.minNations
        }
        applyBorder()
        if (pregenerate) {
            settings.pregen = if (chunkyAvailable) PREGEN_CHUNKY else PREGEN_BUILTIN
            settings.pregenIndex = 0
            startedAt = System.nanoTime()
            startedIndex = 0
            if (settings.pregen == PREGEN_CHUNKY) runChunky(resume = false)
        }
        for (p in rt.server.playerList.players) {
            applyMode(p)
            if (ServerPlayNetworking.canSend(p, ServerMessagePayload.TYPE)) {
                ServerPlayNetworking.send(p, ServerMessagePayload(WorkJson.json.encodeToString(ToClient.serializer(), com.grandstrategy.net.SetupDone(tier.size))))
            }
        }
        if (first) rt.hooks.startScouting()
        rt.save()
        val how = when {
            !pregenerate -> "미리 생성 없음"
            settings.pregen == PREGEN_CHUNKY -> "Chunky로 미리 생성 시작"
            else -> "내장 생성기로 미리 생성 시작"
        }
        rt.sim.log(com.grandstrategy.sim.EventKind.MAJOR, "$by 님이 세계를 ${tier.size}×${tier.size}(${tier.label})로 정했다")
        return Outcome.ok("세계 크기 ${tier.size}×${tier.size}, 나라 ${settings.initialNations}개, $how${if (god) ", 조물주 모드" else ""}")
    }

    private fun applyBorder() {
        val spawn = level.respawnData.pos()
        val border = level.worldBorder
        border.setCenter(spawn.x + 0.5, spawn.z + 0.5)
        border.setSize(settings.worldSize.toDouble())
    }

    fun tick() {
        ticks++
        if (ticks % 100 == 0L && GodMode.enabled) {
            for (p in rt.server.playerList.players) {
                if (!p.isSpectator) {
                    p.foodData.setFoodLevel(20)
                    p.foodData.setSaturation(5f)
                }
            }
        }
        when (settings.pregen) {
            PREGEN_BUILTIN -> builtinTick()
            PREGEN_CHUNKY -> if (ticks % 100 == 0L) pollChunky()
        }
        if (ticks % 20 == 0L) syncBar()
    }

    private fun syncBar() {
        val active = settings.pregen == PREGEN_BUILTIN
        if (!active) {
            if (bar.players.isNotEmpty()) bar.removeAllPlayers()
            return
        }
        val fraction = progress()
        val elapsed = (System.nanoTime() - startedAt) / 1e9
        val done = if (settings.pregen == PREGEN_BUILTIN) settings.pregenIndex - startedIndex else 0L
        val eta = if (settings.pregen == PREGEN_BUILTIN && done > 50 && elapsed > 5) {
            val rate = done / elapsed
            val left = (total() - settings.pregenIndex) / rate
            " · 남은 시간 약 ${(left / 60).toInt()}분 (${"%.0f".format(rate)}청크/초)"
        } else ""
        val engine = if (settings.pregen == PREGEN_CHUNKY) "Chunky" else "내장"
        bar.setName(Component.literal("지형 미리 생성 ($engine) ${"%.1f".format(fraction * 100)}%$eta"))
        bar.setProgress(fraction.toFloat().coerceIn(0f, 1f))
        val players = rt.server.playerList.players
        for (p in players) if (p !in bar.players) bar.addPlayer(p)
    }

    fun progress(): Double = when (settings.pregen) {
        PREGEN_BUILTIN -> settings.pregenIndex.toDouble() / total().coerceAtLeast(1)
        PREGEN_CHUNKY -> chunkyProgress / 100.0
        else -> 1.0
    }

    private fun total(): Long {
        val r = radiusChunks()
        return (2L * r + 1) * (2L * r + 1)
    }

    private fun radiusChunks(): Int = settings.worldSize / 2 / 16 + 1

    private fun builtinTick() {
        inflight.removeIf { it.isDone }
        val total = total()
        if (settings.pregenIndex >= total && inflight.isEmpty()) {
            finish("내장 생성기")
            return
        }
        val spawn = level.respawnData.pos()
        val cx = spawn.x shr 4
        val cz = spawn.z shr 4
        val side = 2 * radiusChunks() + 1
        val deadline = System.nanoTime() + BUILTIN_BUDGET_NANOS
        while (inflight.size < BUILTIN_IN_FLIGHT && settings.pregenIndex < total && System.nanoTime() < deadline) {
            val (dx, dz) = spiral(settings.pregenIndex, side)
            settings.pregenIndex++
            val x = cx + dx
            val z = cz + dz
            if (level.chunkSource.hasChunk(x, z)) continue
            inflight += level.chunkSource.getChunkFuture(x, z, ChunkStatus.FULL, true)
        }
    }

    private fun spiral(index: Long, side: Int): Pair<Int, Int> {
        val half = side / 2
        val row = (index / side).toInt()
        val col = (index % side).toInt()
        val serpentine = if (row % 2 == 0) col else side - 1 - col
        return (serpentine - half) to (row - half)
    }

    private fun runChunky(resume: Boolean) {
        val spawn = level.respawnData.pos()
        val radius = settings.worldSize / 2
        val commands = if (resume) listOf("chunky continue") else listOf(
            "chunky world minecraft:overworld",
            "chunky shape square",
            "chunky center ${spawn.x} ${spawn.z}",
            "chunky radius $radius",
            "chunky start"
        )
        for (c in commands) run(c)
        chunkyIdle = 0
    }

    private fun pollChunky() {
        val lines = run("chunky progress")
        val text = lines.joinToString(" ")
        val pct = Regex("(\\d+(?:\\.\\d+)?)%").findAll(text).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()
        if (pct != null) {
            chunkyProgress = pct
            chunkyIdle = 0
            if (pct >= 100.0) finish("Chunky")
        } else {
            chunkyIdle++
            if (chunkyIdle >= 3 && chunkyProgress > 0) finish("Chunky")
        }
    }

    private fun run(command: String): List<String> {
        val captured = ArrayList<String>()
        val source = object : CommandSource {
            override fun sendSystemMessage(message: Component) {
                captured += message.string
            }
            override fun acceptsSuccess() = true
            override fun acceptsFailure() = true
            override fun shouldInformAdmins() = false
        }
        try {
            rt.server.commands.performPrefixedCommand(rt.server.createCommandSourceStack().withSource(source), command)
        } catch (e: Exception) {
            GrandStrategy.LOGGER.warn("Command failed: {}", command, e)
        }
        return captured
    }

    private fun finish(engine: String) {
        settings.pregen = PREGEN_DONE
        bar.removeAllPlayers()
        rt.server.playerList.broadcastSystemMessage(Texts.prefix().append(Texts.plain("지형 미리 생성 완료 ($engine)", ChatFormatting.GREEN)), false)
        rt.save()
    }

    fun cancel(): Outcome {
        if (settings.pregen == PREGEN_CHUNKY) run("chunky cancel")
        settings.pregen = PREGEN_NONE
        bar.removeAllPlayers()
        return Outcome.ok("미리 생성을 멈췄다.")
    }

    fun applyMode(player: ServerPlayer) {
        if (!GodMode.enabled || CutsceneGuard.isGuarded(player.uuid)) return
        val wanted = if (settings.playerModes[player.uuid.toString()] == "adventure") GameType.ADVENTURE else GameType.SPECTATOR
        if (player.gameMode() != wanted) player.setGameMode(wanted)
    }

    fun setMode(player: ServerPlayer, adventure: Boolean): Outcome {
        if (!GodMode.enabled) return Outcome.fail("조물주 모드가 꺼져 있다.")
        settings.playerModes[player.uuid.toString()] = if (adventure) "adventure" else "spectator"
        applyMode(player)
        return Outcome.ok(if (adventure) "모험 모드: 땅을 걸으며 둘러본다 (피해 없음)" else "관전 모드: 하늘에서 세계를 내려다본다")
    }

    fun offer(player: ServerPlayer) {
        if (!Commands.LEVEL_GAMEMASTERS.check(player.permissions())) return
        if (ServerPlayNetworking.canSend(player, ServerMessagePayload.TYPE)) {
            val offer = SetupOffer(
                tiers = WorldTier.entries.map { SetupTier(it.size, it.nations, it.label, it.chunks) },
                current = settings.worldSize,
                chunky = chunkyAvailable,
                firstTime = !rt.state.seeded,
                god = settings.godMode
            )
            ServerPlayNetworking.send(player, ServerMessagePayload(WorkJson.json.encodeToString(ToClient.serializer(), offer)))
            return
        }
        var line = Texts.prefix().append(Texts.plain("세계 크기를 고른다: ", ChatFormatting.WHITE))
        for (t in WorldTier.entries) {
            line = line.append(Texts.button("${t.size}", "/gs setup ${t.size}", "${t.size}×${t.size} · ${t.label} · 나라 ${t.nations}개", ChatFormatting.GREEN)).append(Component.literal(" "))
        }
        player.sendSystemMessage(line)
    }

    companion object {
        const val PREGEN_NONE = 0
        const val PREGEN_BUILTIN = 1
        const val PREGEN_CHUNKY = 2
        const val PREGEN_DONE = 3
        const val BUILTIN_IN_FLIGHT = 24
        const val BUILTIN_BUDGET_NANOS = 8_000_000L
    }
}
