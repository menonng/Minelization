package com.grandstrategy.net

import com.grandstrategy.sim.Box
import com.grandstrategy.sim.BuildingKind
import com.grandstrategy.sim.WorkType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class WorkTool(val label: String) { SELECT("선택"), BUILD("건축"), WALLS("성벽"), DEMOLISH("철거"), FLATTEN("평탄화") }

@Serializable
data class TemplateView(val index: Int, val name: String, val style: String, val kind: BuildingKind, val sizeX: Int, val sizeY: Int, val sizeZ: Int)

@Serializable
data class ChunkView(val key: Long, val color: Int, val own: Boolean)

@Serializable
data class BuildingView(val id: Int, val box: Box, val kind: BuildingKind, val own: Boolean, val complete: Boolean, val owner: String = "", val detected: Boolean = false)

@Serializable
data class OrderView(val id: Int, val type: WorkType, val box: Box, val progress: Long, val total: Long, val crew: Int, val present: Int, val kind: BuildingKind? = null)

@Serializable
sealed class ToServer {
    @Serializable
    data class Mode(val on: Boolean) : ToServer()

    @Serializable
    data class Order(val tool: WorkTool, val box: Box, val template: Int = -1, val rotation: Int = 0, val conscript: Boolean = false) : ToServer()

    @Serializable
    data class Select(val x: Int, val y: Int, val z: Int) : ToServer()

    @Serializable
    data class BuildingAction(val id: Int, val action: String, val kind: BuildingKind? = null, val conscript: Boolean = false) : ToServer()

    @Serializable
    data class Cancel(val order: Int) : ToServer()
}

@Serializable
sealed class ToClient {
    @Serializable
    data class Info(
        val nation: String,
        val color: Int,
        val ruler: Boolean,
        val spectator: Boolean,
        val stock: Map<String, Int>,
        val chunks: List<ChunkView>,
        val buildings: List<BuildingView>,
        val orders: List<OrderView>,
        val builders: Int,
        val idle: Int
    ) : ToClient()

    @Serializable
    data class Templates(val list: List<TemplateView>) : ToClient()

    @Serializable
    data class Selected(val building: BuildingView?, val message: String = "") : ToClient()

    @Serializable
    data class Result(val ok: Boolean, val message: String) : ToClient()
}

object WorkJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; classDiscriminator = "t" }
}
