package com.grandstrategy.mc

import com.grandstrategy.GrandStrategy
import com.grandstrategy.mc.build.RoadBlocks
import com.grandstrategy.sim.Content
import com.grandstrategy.sim.ContentConfig
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * config/grandstrategy/content.json: eras, named technologies, weapons, energy and ideologies. Written with the
 * built-in content on first run; edit it and `/gs admin reload` to add an era or a weapon without touching code.
 * A file with mistakes (unknown ids, unknown blocks, missing starting weapon...) is rejected and the old content kept.
 */
object ContentFile {
    val path: Path = FabricLoader.getInstance().configDir.resolve("grandstrategy").resolve("content.json")

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        allowStructuredMapKeys = true
    }

    fun loadAtStartup() {
        val result = reload()
        if (result.ok) GrandStrategy.LOGGER.info(result.message) else GrandStrategy.LOGGER.error(result.message)
    }

    fun reload(): BalanceFile.Result {
        if (!Files.exists(path)) {
            write(Content.config)
            return BalanceFile.Result(true, "기본 content.json을 썼다 ($path)")
        }
        val next = try {
            json.decodeFromString(ContentConfig.serializer(), Files.readString(path))
        } catch (e: Exception) {
            return BalanceFile.Result(false, "content.json 형식 오류, 기존 내용 유지: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val problems = next.problems() + blockProblems(next)
        if (problems.isNotEmpty()) return BalanceFile.Result(false, "content.json 오류, 기존 내용 유지: ${problems.take(6).joinToString("; ")}")
        val changed = next != Content.config
        Content.config = next
        return BalanceFile.Result(true, "content.json 적용: 시대 ${next.eras.size} · 기술 ${next.techs.size} · 무기 ${next.weapons.size} · " +
            "에너지 ${next.energy.size} · 사상 ${next.ideologies.size}${if (changed) "" else " (바뀐 것 없음)"}")
    }

    private fun blockProblems(c: ContentConfig): List<String> = c.eras.flatMap { e ->
        (e.road + listOfNotNull(e.plaza, e.bridge)).filter { RoadBlocks.block(it) == null }.map { "${e.id}: 없는 블록 $it" }
    }

    private fun write(c: ContentConfig) {
        Files.createDirectories(path.parent)
        val temp = path.resolveSibling("content.json.tmp")
        Files.writeString(temp, json.encodeToString(ContentConfig.serializer(), c))
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
