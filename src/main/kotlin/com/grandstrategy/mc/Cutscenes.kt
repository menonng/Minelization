package com.grandstrategy.mc

import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Cinematic
import com.grandstrategy.sim.Nation
import com.grandstrategy.net.CutsceneKind
import com.grandstrategy.net.CutsceneSpec
import com.grandstrategy.net.PlayCutscenePayload
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import java.util.UUID

class Cutscenes(private val rt: Runtime) {
    private val queues = HashMap<UUID, ArrayDeque<CutsceneSpec>>()
    private var nextId = 1
    private var lastBroadcastTick = -100_000L

    fun onCinematic(event: Cinematic) {
        val state = rt.state
        when (event) {
            is Cinematic.Founded -> {
                val nation = state.nations[event.nation] ?: return
                val player = rt.server.playerList.getPlayer(UUID.fromString(event.ruler)) ?: return
                enqueue(player, spec(CutsceneKind.FOUNDING, nation, null, nation.capital, player.gameProfile.name()))
            }

            is Cinematic.CapitalFell -> {
                val defender = state.nations[event.defender] ?: return
                val attacker = state.nations[event.attacker]
                for (player in rt.server.playerList.players) {
                    if (player.uuid.toString() !in defender.citizens) continue
                    enqueue(player, spec(CutsceneKind.CAPITAL_FALL, defender, attacker, event.chunk, defender.rulerName ?: ""))
                }
            }

            is Cinematic.Destroyed -> {
                val now = rt.server.tickCount.toLong()
                if (now - lastBroadcastTick < BROADCAST_GAP_TICKS) return
                lastBroadcastTick = now
                val nation = state.nations[event.nation] ?: return
                val by = event.by?.let { state.nations[it] }
                for (player in rt.server.playerList.players) {
                    enqueue(player, spec(CutsceneKind.DESTRUCTION, nation, by, event.capital, ""))
                }
            }
        }
    }

    fun spec(kind: CutsceneKind, nation: Nation, other: Nation?, chunk: Long, subject: String): CutsceneSpec {
        val x = Chunks.x(chunk) * 16 + 8.0
        val z = Chunks.z(chunk) * 16 + 8.0
        val level = rt.server.overworld()
        val y = if (kind == CutsceneKind.DESTRUCTION) 0.0 else {
            level.getChunk(Chunks.x(chunk), Chunks.z(chunk))
            level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x.toInt(), z.toInt()).toDouble()
        }
        return CutsceneSpec(
            id = nextId++,
            kind = kind,
            nation = nation.name,
            color = Texts.dye(nation).textColor,
            dye = nation.color,
            other = other?.name ?: "",
            otherColor = other?.let { Texts.dye(it).textColor } ?: 0xFFFFFF,
            otherDye = other?.color ?: 0,
            x = x, y = y, z = z,
            turn = rt.state.turn,
            subject = subject,
            claims = if (kind == CutsceneKind.FOUNDING) nation.claims.toList() else emptyList()
        )
    }

    fun enqueue(player: ServerPlayer, spec: CutsceneSpec) {
        val queue = queues.getOrPut(player.uuid) { ArrayDeque() }
        if (queue.size >= MAX_QUEUE) return
        queue += spec
    }

    fun tick() {
        if (queues.isEmpty()) return
        for ((uuid, queue) in queues.entries.toList()) {
            if (queue.isEmpty()) {
                queues.remove(uuid)
                continue
            }
            if (CutsceneGuard.isGuarded(uuid)) continue
            val player = rt.server.playerList.getPlayer(uuid)
            if (player == null) {
                queues.remove(uuid)
                continue
            }
            play(player, queue.removeFirst())
        }
    }

    fun play(player: ServerPlayer, spec: CutsceneSpec) {
        if (!ServerPlayNetworking.canSend(player, PlayCutscenePayload.TYPE)) {
            fallback(player, spec)
            return
        }
        val target = Vec3(spec.x, spec.y, spec.z)
        val remote = spec.kind == CutsceneKind.CAPITAL_FALL &&
            (player.level() != rt.server.overworld() || player.position().distanceTo(target) > 64)
        val ticks = (spec.kind.seconds * 20).toLong() + MARGIN_TICKS + LOADING_TICKS
        val relocate = if (remote) Vec3(spec.x, spec.y + 24, spec.z) else null
        CutsceneGuard.lock(rt.server, player, spec.id, ticks, relocate)
        ServerPlayNetworking.send(player, PlayCutscenePayload(spec.encode()))
    }

    private fun fallback(player: ServerPlayer, spec: CutsceneSpec) {
        val (title, subtitle) = when (spec.kind) {
            CutsceneKind.FOUNDING -> Component.literal(spec.nation).withColor(spec.color) to
                Component.literal("${spec.turn}턴, 새 나라가 세워졌다").withStyle(ChatFormatting.GRAY)
            CutsceneKind.CAPITAL_FALL -> Component.literal("수도 함락").withStyle(ChatFormatting.DARK_RED) to
                Component.literal("${spec.nation}의 수도가 ${spec.other.ifEmpty { "적" }}에게 넘어갔다").withStyle(ChatFormatting.GRAY)
            CutsceneKind.DESTRUCTION -> Component.literal("${spec.nation} 멸망").withStyle(ChatFormatting.DARK_GRAY) to
                Component.literal(if (spec.other.isEmpty()) "${spec.turn}턴" else "${spec.other}에 의해 · ${spec.turn}턴").withStyle(ChatFormatting.GRAY)
        }
        player.connection.send(ClientboundSetTitlesAnimationPacket(10, 70, 20))
        player.connection.send(ClientboundSetTitleTextPacket(title))
        player.connection.send(ClientboundSetSubtitleTextPacket(subtitle))
    }

    private companion object {
        const val MAX_QUEUE = 2
        const val MARGIN_TICKS = 30L
        const val LOADING_TICKS = 140L
        const val BROADCAST_GAP_TICKS = 600L
    }
}
