package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.GameType
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object CutsceneGuard {
    @Serializable
    data class Origin(
        val dimension: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
        val gameMode: String
    )

    private class Lock(
        val cutscene: Int,
        val endTick: Long,
        val origin: Origin,
        val relocated: Boolean,
        val hold: Vec3
    )

    private val locks = ConcurrentHashMap<UUID, Lock>()
    private val json = Json { ignoreUnknownKeys = true }

    fun isGuarded(uuid: UUID): Boolean = locks.containsKey(uuid)

    fun activeCutscene(uuid: UUID): Int? = locks[uuid]?.cutscene

    fun lock(server: MinecraftServer, player: ServerPlayer, cutscene: Int, durationTicks: Long, relocateTo: Vec3?) {
        release(server, player)
        val origin = Origin(
            player.level().dimension().identifier().toString(),
            player.x, player.y, player.z, player.yRot, player.xRot,
            player.gameMode().name
        )
        if (relocateTo != null) {
            persist(server) { it[player.uuid.toString()] = origin }
            player.setGameMode(GameType.SPECTATOR)
            val moved = player.teleportTo(server.overworld(), relocateTo.x, relocateTo.y, relocateTo.z, emptySet(), player.yRot, player.xRot, false)
            GrandStrategy.LOGGER.info("Cutscene relocate {} -> {} ok={} now={}", player.gameProfile.name(), relocateTo, moved, player.position())
        }
        val hold = relocateTo ?: player.position()
        locks[player.uuid] = Lock(cutscene, server.tickCount + durationTicks, origin, relocateTo != null, hold)
    }

    fun tick(server: MinecraftServer) {
        if (locks.isEmpty()) return
        for ((uuid, lock) in locks.entries.toList()) {
            val player = server.playerList.getPlayer(uuid)
            if (player == null) {
                locks.remove(uuid)
                continue
            }
            if (server.tickCount >= lock.endTick) {
                release(server, player)
                continue
            }
            player.deltaMovement = Vec3.ZERO
            player.clearFire()
            player.fallDistance = 0.0
            player.airSupply = player.maxAirSupply
            if (player.position().distanceToSqr(lock.hold) > 0.01) {
                player.teleportTo(player.level(), lock.hold.x, lock.hold.y, lock.hold.z, emptySet(), player.yRot, player.xRot, false)
            }
        }
    }

    fun finish(server: MinecraftServer, player: ServerPlayer, cutscene: Int) {
        if (locks[player.uuid]?.cutscene == cutscene) release(server, player)
    }

    fun release(server: MinecraftServer, player: ServerPlayer) {
        val lock = locks.remove(player.uuid) ?: return
        if (lock.relocated) restore(server, player, lock.origin)
    }

    fun restoreIfPending(server: MinecraftServer, player: ServerPlayer) {
        if (isGuarded(player.uuid)) return
        val pending = read(server)[player.uuid.toString()] ?: return
        restore(server, player, pending)
    }

    private fun restore(server: MinecraftServer, player: ServerPlayer, origin: Origin) {
        val key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(origin.dimension))
        val level = server.getLevel(key) ?: server.overworld()
        player.teleportTo(level, origin.x, origin.y, origin.z, emptySet(), origin.yaw, origin.pitch, false)
        player.setGameMode(runCatching { GameType.valueOf(origin.gameMode) }.getOrDefault(GameType.SURVIVAL))
        persist(server) { it.remove(player.uuid.toString()) }
    }

    private fun file(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("grandstrategy_cutscene_restore.json")

    private fun read(server: MinecraftServer): MutableMap<String, Origin> {
        val path = file(server)
        if (!Files.exists(path)) return mutableMapOf()
        return runCatching {
            json.decodeFromString<Map<String, Origin>>(Files.readString(path)).toMutableMap()
        }.getOrElse {
            GrandStrategy.LOGGER.error("Could not read {}", path, it)
            mutableMapOf()
        }
    }

    private fun persist(server: MinecraftServer, change: (MutableMap<String, Origin>) -> Unit) {
        val data = read(server)
        change(data)
        val path = file(server)
        Files.createDirectories(path.parent)
        if (data.isEmpty()) Files.deleteIfExists(path) else Files.writeString(path, json.encodeToString(data))
    }
}
