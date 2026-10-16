package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.EventKind
import com.grandstrategy.sim.GameEvent
import com.grandstrategy.sim.Nation
import com.grandstrategy.sim.PresenceProvider
import com.grandstrategy.sim.Simulation
import com.grandstrategy.sim.Treaty
import com.grandstrategy.sim.WorldState
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class Runtime(val server: MinecraftServer, save: SaveFile?) {
    val settings: Settings = save?.settings ?: Settings()
    val state: WorldState = save?.state ?: WorldState(seed = server.overworld().seed)
    val sim = Simulation(state, BiomeTerrain(server.overworld()))
    val hooks = WorldHooks(this)
    val observers = HashMap<UUID, Boolean>()

    private var ticks = 0L
    private val notifiedProposals = HashSet<Int>()
    private var playerChunks: Map<UUID, Long> = emptyMap()

    var lastTurnMs = 0.0
        private set
    var avgTurnMs = 0.0
        private set
    var maxTurnMs = 0.0
        private set
    var measuredTurns = 0L
        private set

    init {
        val spawn = server.overworld().respawnData.pos()
        sim.spawnCenter = Chunks.pack(spawn.x shr 4, spawn.z shr 4)
        sim.spawnRadius = settings.seedRadiusChunks
        sim.minNations = settings.minNations
        sim.presence = PresenceProvider { nation, chunk -> presence(nation, chunk) }
        sim.onEvent(::dispatch)
    }

    fun tick() {
        ticks++
        hooks.tick(ticks)
        if (!settings.paused && state.seeded && ticks % settings.turnTicks.coerceAtLeast(1) == 0L) runTurn()
        if (ticks % AUTOSAVE_TICKS == 0L) save()
    }

    fun runTurn() {
        playerChunks = server.playerList.players
            .filter { it.level() == server.overworld() }
            .associate { it.uuid to it.chunkPosition().pack() }
        val start = System.nanoTime()
        sim.turn()
        val ms = (System.nanoTime() - start) / 1e6
        lastTurnMs = ms
        measuredTurns++
        avgTurnMs += (ms - avgTurnMs) / measuredTurns.coerceAtMost(200)
        if (ms > maxTurnMs) maxTurnMs = ms
        notifyProposals()
    }

    fun seedWorld() {
        if (state.seeded) return
        val created = sim.seedNations(settings.initialNations, sim.spawnCenter, settings.seedRadiusChunks)
        state.seeded = true
        GrandStrategy.LOGGER.info("Seeded {} nations around {}", created, Chunks.format(sim.spawnCenter))
    }

    fun save() {
        try {
            Storage.save(server, SaveFile(settings = settings, state = state))
        } catch (e: Exception) {
            GrandStrategy.LOGGER.error("Failed to save nation data", e)
        }
    }

    private fun presence(nation: Int, chunk: Long): Int {
        val members = state.nations[nation]?.citizens ?: return 0
        return playerChunks.count { (uuid, at) -> uuid.toString() in members && Chunks.distance(at, chunk) <= 1 }
    }

    fun citizenNation(player: ServerPlayer): Nation? {
        val id = player.uuid.toString()
        return state.alive().firstOrNull { id in it.citizens }
    }

    fun ruledNation(player: ServerPlayer): Nation? = state.rulerNation(player.uuid.toString())

    private fun dispatch(event: GameEvent) {
        val players = server.playerList.players
        for (player in players) {
            val own = citizenNation(player)
            val involved = own != null && own.id in event.nations
            val show = when (event.kind) {
                EventKind.MAJOR -> true
                EventKind.WAR, EventKind.DIPLOMACY -> involved || player.uuid in observers
                EventKind.ECONOMY -> involved && own?.ruler == player.uuid.toString()
                EventKind.INFO -> observers[player.uuid] == true
            }
            if (show) player.sendSystemMessage(Texts.event(event))
        }
    }

    fun notifyProposals() {
        for (p in state.proposals.values.toList()) {
            if (p.id in notifiedProposals) continue
            val to = state.nation(p.to) ?: continue
            val from = state.nation(p.from) ?: continue
            val rulerId = to.ruler ?: continue
            val player = server.playerList.getPlayer(UUID.fromString(rulerId)) ?: continue
            notifiedProposals += p.id
            val what = if (p.type == "peace") "평화" else {
                val t = runCatching { Treaty.valueOf(p.type.removePrefix("treaty:")) }.getOrNull()
                "${t?.label ?: p.type} 조약"
            }
            player.sendSystemMessage(
                Texts.prefix().append(Texts.nation(from)).append(Texts.plain("이(가) $what 을(를) 제안했다. ", ChatFormatting.WHITE))
                    .append(Texts.button("수락", "/gs accept ${p.id}", "제안을 받아들인다", ChatFormatting.GREEN))
                    .append(Component.literal(" "))
                    .append(Texts.button("거절", "/gs decline ${p.id}", "제안을 거절한다", ChatFormatting.RED))
            )
        }
    }

    companion object {
        const val AUTOSAVE_TICKS = 6000L
    }
}
