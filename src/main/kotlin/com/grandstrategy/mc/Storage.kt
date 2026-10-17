package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import com.grandstrategy.sim.WorldState
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
class Settings(
    var turnTicks: Int = 200,
    var paused: Boolean = false,
    var initialNations: Int = 6,
    var seedRadiusChunks: Int = 40,
    var minNations: Int = 4,
    var villageProbes: Int = 8,
    var capitalMarkers: Boolean = true,
    var worldSize: Int = 0,
    var godMode: Boolean = true,
    var pregen: Int = 0,
    var pregenIndex: Long = 0,
    val playerModes: MutableMap<String, String> = mutableMapOf()
)

@Serializable
class SaveFile(
    val version: Int = 1,
    val settings: Settings = Settings(),
    val state: WorldState = WorldState()
)

object Storage {
    private const val FILE_NAME = "grandstrategy.json"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        allowStructuredMapKeys = true
    }

    private fun file(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE_NAME)

    fun load(server: MinecraftServer): SaveFile? {
        val path = file(server)
        if (!Files.exists(path)) return null
        return try {
            json.decodeFromString(SaveFile.serializer(), Files.readString(path))
        } catch (e: Exception) {
            val backup = path.resolveSibling("$FILE_NAME.broken-${System.currentTimeMillis()}")
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING)
            GrandStrategy.LOGGER.error("Could not read {}, kept a copy at {}", path, backup, e)
            null
        }
    }

    fun save(server: MinecraftServer, data: SaveFile) {
        val path = file(server)
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("$FILE_NAME.tmp")
        Files.writeString(temp, json.encodeToString(SaveFile.serializer(), data))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
