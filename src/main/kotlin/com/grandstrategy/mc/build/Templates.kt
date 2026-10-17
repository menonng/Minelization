package com.grandstrategy.mc.build

import com.grandstrategy.GrandStrategy
import com.grandstrategy.mixin.StructureTemplateAccessor
import com.grandstrategy.sim.BuildingKind
import kotlinx.serialization.Serializable
import net.minecraft.core.BlockPos
import net.minecraft.core.Vec3i
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.BiomeTags
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.levelgen.structure.templatesystem.JigsawReplacementProcessor
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate

@Serializable
data class TemplateInfo(val index: Int, val id: String, val name: String, val style: String, val kind: BuildingKind, val sizeX: Int, val sizeY: Int, val sizeZ: Int)

class PlacedBlock(val pos: BlockPos, val state: BlockState)

class Templates(private val server: MinecraftServer) {
    val all: List<TemplateInfo>
    private val cache = HashMap<String, StructureTemplate>()

    init {
        val found = server.resourceManager.listResources("structure") { id ->
            id.namespace == "minecraft" && id.path.startsWith("structure/village/") && "/houses/" in id.path && "zombie" !in id.path && id.path.endsWith(".nbt")
        }.keys.sortedBy { it.path }
        val list = ArrayList<TemplateInfo>()
        for (file in found) {
            val path = file.path.removePrefix("structure/").removeSuffix(".nbt")
            val parts = path.split('/')
            if (parts.size < 4) continue
            val style = parts[1]
            val name = parts.last()
            val kind = classify(name) ?: continue
            val id = Identifier.fromNamespaceAndPath("minecraft", path)
            val template = load(id.toString()) ?: continue
            val size = template.size
            if (size.x < 3 || size.z < 3) continue
            list += TemplateInfo(list.size, id.toString(), name, style, kind, size.x, size.y, size.z)
        }
        all = list
        GrandStrategy.LOGGER.info("Loaded {} vanilla village building templates", all.size)
    }

    fun load(id: String): StructureTemplate? = cache.getOrPut(id) {
        server.structureTemplateManager.get(Identifier.parse(id)).orElse(null) ?: return null
    }

    fun byKind(kind: BuildingKind, style: String): List<TemplateInfo> {
        val exact = all.filter { it.kind == kind && it.style == style }
        return exact.ifEmpty { all.filter { it.kind == kind && it.style == "plains" } }.ifEmpty { all.filter { it.kind == kind } }
    }

    fun styleAt(level: ServerLevel, x: Int, z: Int): String {
        val biome = level.getUncachedNoiseBiome(x shr 2, 160 shr 2, z shr 2)
        val path = biome.unwrapKey().map { it.identifier().path }.orElse("")
        return when {
            "desert" in path || biome.`is`(BiomeTags.IS_BADLANDS) -> "desert"
            biome.`is`(BiomeTags.IS_SAVANNA) -> "savanna"
            "snowy" in path || "frozen" in path || "ice" in path || "grove" in path -> "snowy"
            biome.`is`(BiomeTags.IS_TAIGA) -> "taiga"
            else -> "plains"
        }
    }

    fun footprint(info: TemplateInfo, rotation: Int): Vec3i =
        if (rotation % 2 == 1) Vec3i(info.sizeZ, info.sizeY, info.sizeX) else Vec3i(info.sizeX, info.sizeY, info.sizeZ)

    fun blocks(level: ServerLevel, info: TemplateInfo, rotation: Int, minX: Int, baseY: Int, minZ: Int): List<PlacedBlock> {
        val template = load(info.id) ?: return emptyList()
        val rot = Rotation.entries[rotation and 3]
        val settings = StructurePlaceSettings().setRotation(rot).setIgnoreEntities(true).addProcessor(JigsawReplacementProcessor.INSTANCE)
        val palettes = (template as StructureTemplateAccessor).`grandstrategy$palettes`()
        if (palettes.isEmpty()) return emptyList()
        val raw = StructureTemplate.processBlockInfos(level, BlockPos.ZERO, BlockPos.ZERO, settings, palettes[0].blocks())
        if (raw.isEmpty()) return emptyList()
        val ox = minX - raw.minOf { it.pos().x }
        val oz = minZ - raw.minOf { it.pos().z }
        val oy = baseY - raw.minOf { it.pos().y }
        return raw.map { PlacedBlock(it.pos().offset(ox, oy, oz), it.state().rotate(rot)) }
            .sortedWith(compareBy<PlacedBlock>({ it.pos.y }, { if (it.state.isAir) 0 else 1 }, { it.pos.x }, { it.pos.z }))
    }

    private fun classify(name: String): BuildingKind? = when {
        "small_house" in name || "medium_house" in name || "big_house" in name || "fisher" in name -> BuildingKind.HOUSE
        "farm" in name -> BuildingKind.FARM
        "fletcher" in name -> BuildingKind.LUMBER_CAMP
        "mason" in name -> BuildingKind.QUARRY
        "tool_smith" in name || "toolsmith" in name -> BuildingKind.MINE
        "cartographer" in name || "butcher" in name -> BuildingKind.MARKET
        "library" in name -> BuildingKind.LIBRARY
        "weaponsmith" in name || "weapon_smith" in name || "armorer" in name -> BuildingKind.BARRACKS
        "temple" in name -> BuildingKind.TEMPLE
        else -> null
    }
}
