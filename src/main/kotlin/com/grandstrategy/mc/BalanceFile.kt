package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import com.grandstrategy.sim.Balance
import com.grandstrategy.sim.BalanceConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object BalanceFile {
    val path: Path = FabricLoader.getInstance().configDir.resolve("grandstrategy").resolve("balance.json")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    class Result(val ok: Boolean, val message: String, val changed: List<String> = emptyList())

    fun loadAtStartup() {
        val result = reload()
        if (result.ok) GrandStrategy.LOGGER.info(result.message) else GrandStrategy.LOGGER.error(result.message)
    }

    fun reload(): Result {
        if (!Files.exists(path)) {
            write(Balance.config)
            return Result(true, "Wrote default balance file to $path")
        }
        val text = try {
            Files.readString(path)
        } catch (e: Exception) {
            return Result(false, "Could not read $path: ${e.message}")
        }
        val next = try {
            json.decodeFromString(BalanceConfig.serializer(), text)
        } catch (e: Exception) {
            return Result(false, "balance.json 형식 오류, 기존 값 유지: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val problems = next.problems()
        if (problems.isNotEmpty()) return Result(false, "balance.json 값 오류, 기존 값 유지: ${problems.joinToString("; ")}")
        val changed = diff(json.encodeToJsonElement(BalanceConfig.serializer(), Balance.config), json.encodeToJsonElement(BalanceConfig.serializer(), next))
        Balance.config = next
        write(next)
        return Result(true, if (changed.isEmpty()) "balance.json 다시 읽음 (바뀐 값 없음)" else "balance.json 적용: ${changed.size}개 값 변경", changed)
    }

    private fun write(config: BalanceConfig) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("balance.json.tmp")
        Files.writeString(temp, json.encodeToString(BalanceConfig.serializer(), config))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    private fun diff(old: JsonElement, new: JsonElement, prefix: String = ""): List<String> {
        if (old is JsonObject && new is JsonObject) {
            return (old.keys + new.keys).flatMap { key ->
                val a = old[key]
                val b = new[key]
                val name = if (prefix.isEmpty()) key else "$prefix.$key"
                when {
                    a == null || b == null -> listOf(name)
                    else -> diff(a, b, name)
                }
            }
        }
        return if (old == new) emptyList() else listOf("$prefix: $old → $new")
    }
}
