package com.grandstrategy.mc.build

import com.grandstrategy.sim.BuildingKind
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.FenceGateBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.Rotation
import net.minecraft.world.level.block.WallBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.Property
import kotlin.random.Random

/** The five vanilla village looks. Every building use has a design in each of them. */
enum class Theme(val id: String, val label: String) {
    PLAINS("plains", "평원"), DESERT("desert", "사막"), SAVANNA("savanna", "사바나"), SNOWY("snowy", "설원"), TAIGA("taiga", "타이가");

    companion object {
        fun of(id: String): Theme = entries.firstOrNull { it.id == id } ?: PLAINS
    }
}

/** Materials of a theme. Designs are written once against these slots. */
class Palette(
    val base: BlockState,
    val floor: BlockState,
    val wall: BlockState,
    val trim: BlockState,
    val log: Block,
    val roofStairs: Block,
    val roofSlab: Block,
    val roof: BlockState,
    val fence: BlockState,
    val gate: Block,
    val door: Block,
    val stone: BlockState,
    val stoneStairs: Block,
    val stoneSlab: Block,
    val stoneWall: BlockState,
    val glass: BlockState,
    val stained: BlockState,
    val awning: Pair<BlockState, BlockState>,
    val path: BlockState,
    val yard: BlockState,
    val crops: List<Block>,
    val sapling: BlockState,
    val flatRoof: Boolean
)

/**
 * Original building designs, generated from a footprint so they fit any lot and can replace an existing building in
 * place. Local frame: x across the front, z from front (0) to back, y = 0 is the ground layer (it replaces the top
 * terrain block), y = 1 is the first block above ground. The front faces north (−z); placement rotates the design so
 * the front faces the street.
 */
object Designs {
    private fun dye(c: DyeColor) = Blocks.WOOL.pick(c).defaultBlockState()
    private fun terracotta(c: DyeColor) = Blocks.DYED_TERRACOTTA.pick(c).defaultBlockState()

    fun palette(theme: Theme): Palette = when (theme) {
        Theme.PLAINS -> Palette(
            base = Blocks.COBBLESTONE.defaultBlockState(), floor = Blocks.OAK_PLANKS.defaultBlockState(), wall = Blocks.OAK_PLANKS.defaultBlockState(),
            trim = Blocks.STRIPPED_OAK_LOG.defaultBlockState(), log = Blocks.OAK_LOG, roofStairs = Blocks.DARK_OAK_STAIRS, roofSlab = Blocks.DARK_OAK_SLAB,
            roof = Blocks.DARK_OAK_PLANKS.defaultBlockState(), fence = Blocks.OAK_FENCE.defaultBlockState(), gate = Blocks.OAK_FENCE_GATE, door = Blocks.OAK_DOOR,
            stone = Blocks.STONE_BRICKS.defaultBlockState(), stoneStairs = Blocks.STONE_BRICK_STAIRS, stoneSlab = Blocks.STONE_BRICK_SLAB,
            stoneWall = Blocks.COBBLESTONE_WALL.defaultBlockState(), glass = Blocks.GLASS_PANE.defaultBlockState(),
            stained = Blocks.STAINED_GLASS_PANE.pick(DyeColor.LIGHT_BLUE).defaultBlockState(), awning = dye(DyeColor.RED) to dye(DyeColor.WHITE),
            path = Blocks.DIRT_PATH.defaultBlockState(), yard = Blocks.COARSE_DIRT.defaultBlockState(), crops = listOf(Blocks.WHEAT, Blocks.CARROTS),
            sapling = Blocks.OAK_SAPLING.defaultBlockState(), flatRoof = false
        )
        Theme.DESERT -> Palette(
            base = Blocks.CUT_SANDSTONE.defaultBlockState(), floor = Blocks.SMOOTH_SANDSTONE.defaultBlockState(), wall = Blocks.SMOOTH_SANDSTONE.defaultBlockState(),
            trim = Blocks.CUT_SANDSTONE.defaultBlockState(), log = Blocks.JUNGLE_LOG, roofStairs = Blocks.SMOOTH_SANDSTONE_STAIRS, roofSlab = Blocks.SMOOTH_SANDSTONE_SLAB,
            roof = Blocks.SMOOTH_SANDSTONE.defaultBlockState(), fence = Blocks.JUNGLE_FENCE.defaultBlockState(), gate = Blocks.JUNGLE_FENCE_GATE, door = Blocks.JUNGLE_DOOR,
            stone = Blocks.CUT_SANDSTONE.defaultBlockState(), stoneStairs = Blocks.SANDSTONE_STAIRS, stoneSlab = Blocks.CUT_SANDSTONE_SLAB,
            stoneWall = Blocks.SANDSTONE_WALL.defaultBlockState(), glass = Blocks.GLASS_PANE.defaultBlockState(),
            stained = Blocks.STAINED_GLASS_PANE.pick(DyeColor.ORANGE).defaultBlockState(), awning = dye(DyeColor.ORANGE) to dye(DyeColor.YELLOW),
            path = Blocks.SMOOTH_SANDSTONE.defaultBlockState(), yard = Blocks.SAND.defaultBlockState(), crops = listOf(Blocks.CARROTS, Blocks.WHEAT),
            sapling = Blocks.DEAD_BUSH.defaultBlockState(), flatRoof = true
        )
        Theme.SAVANNA -> Palette(
            base = terracotta(DyeColor.ORANGE), floor = Blocks.ACACIA_PLANKS.defaultBlockState(), wall = Blocks.ACACIA_PLANKS.defaultBlockState(),
            trim = terracotta(DyeColor.WHITE), log = Blocks.ACACIA_LOG, roofStairs = Blocks.ACACIA_STAIRS, roofSlab = Blocks.ACACIA_SLAB,
            roof = Blocks.ACACIA_PLANKS.defaultBlockState(), fence = Blocks.ACACIA_FENCE.defaultBlockState(), gate = Blocks.ACACIA_FENCE_GATE, door = Blocks.ACACIA_DOOR,
            stone = Blocks.MUD_BRICKS.defaultBlockState(), stoneStairs = Blocks.MUD_BRICK_STAIRS, stoneSlab = Blocks.MUD_BRICK_SLAB,
            stoneWall = Blocks.MUD_BRICK_WALL.defaultBlockState(), glass = Blocks.GLASS_PANE.defaultBlockState(),
            stained = Blocks.STAINED_GLASS_PANE.pick(DyeColor.YELLOW).defaultBlockState(), awning = dye(DyeColor.YELLOW) to dye(DyeColor.ORANGE),
            path = Blocks.DIRT_PATH.defaultBlockState(), yard = Blocks.COARSE_DIRT.defaultBlockState(), crops = listOf(Blocks.POTATOES, Blocks.WHEAT),
            sapling = Blocks.ACACIA_SAPLING.defaultBlockState(), flatRoof = false
        )
        Theme.SNOWY -> Palette(
            base = Blocks.STONE_BRICKS.defaultBlockState(), floor = Blocks.SPRUCE_PLANKS.defaultBlockState(), wall = terracotta(DyeColor.WHITE),
            trim = Blocks.PACKED_ICE.defaultBlockState(), log = Blocks.SPRUCE_LOG, roofStairs = Blocks.SPRUCE_STAIRS, roofSlab = Blocks.SPRUCE_SLAB,
            roof = Blocks.SPRUCE_PLANKS.defaultBlockState(), fence = Blocks.SPRUCE_FENCE.defaultBlockState(), gate = Blocks.SPRUCE_FENCE_GATE, door = Blocks.SPRUCE_DOOR,
            stone = Blocks.STONE_BRICKS.defaultBlockState(), stoneStairs = Blocks.STONE_BRICK_STAIRS, stoneSlab = Blocks.STONE_BRICK_SLAB,
            stoneWall = Blocks.STONE_BRICK_WALL.defaultBlockState(), glass = Blocks.GLASS_PANE.defaultBlockState(),
            stained = Blocks.STAINED_GLASS_PANE.pick(DyeColor.CYAN).defaultBlockState(), awning = dye(DyeColor.LIGHT_BLUE) to dye(DyeColor.WHITE),
            path = Blocks.DIRT_PATH.defaultBlockState(), yard = Blocks.SNOW_BLOCK.defaultBlockState(), crops = listOf(Blocks.POTATOES, Blocks.BEETROOTS),
            sapling = Blocks.SPRUCE_SAPLING.defaultBlockState(), flatRoof = false
        )
        Theme.TAIGA -> Palette(
            base = Blocks.MOSSY_COBBLESTONE.defaultBlockState(), floor = Blocks.SPRUCE_PLANKS.defaultBlockState(), wall = Blocks.SPRUCE_PLANKS.defaultBlockState(),
            trim = Blocks.COBBLESTONE.defaultBlockState(), log = Blocks.STRIPPED_SPRUCE_LOG, roofStairs = Blocks.SPRUCE_STAIRS, roofSlab = Blocks.SPRUCE_SLAB,
            roof = Blocks.SPRUCE_PLANKS.defaultBlockState(), fence = Blocks.SPRUCE_FENCE.defaultBlockState(), gate = Blocks.SPRUCE_FENCE_GATE, door = Blocks.SPRUCE_DOOR,
            stone = Blocks.MOSSY_STONE_BRICKS.defaultBlockState(), stoneStairs = Blocks.MOSSY_STONE_BRICK_STAIRS, stoneSlab = Blocks.MOSSY_STONE_BRICK_SLAB,
            stoneWall = Blocks.MOSSY_COBBLESTONE_WALL.defaultBlockState(), glass = Blocks.GLASS_PANE.defaultBlockState(),
            stained = Blocks.STAINED_GLASS_PANE.pick(DyeColor.GREEN).defaultBlockState(), awning = dye(DyeColor.GREEN) to dye(DyeColor.BROWN),
            path = Blocks.PODZOL.defaultBlockState(), yard = Blocks.COARSE_DIRT.defaultBlockState(), crops = listOf(Blocks.POTATOES, Blocks.CARROTS),
            sapling = Blocks.SPRUCE_SAPLING.defaultBlockState(), flatRoof = false
        )
    }

    /** Lot size (width, depth) a design is drawn at by default, and the smallest it can shrink to. */
    fun defaultSize(kind: BuildingKind): Pair<Int, Int> = when (kind) {
        BuildingKind.HOUSE -> 9 to 7
        BuildingKind.FARM -> 9 to 9
        BuildingKind.LUMBER_CAMP -> 11 to 7
        BuildingKind.QUARRY -> 9 to 9
        BuildingKind.MINE -> 9 to 9
        BuildingKind.MARKET -> 11 to 9
        BuildingKind.LIBRARY -> 9 to 11
        BuildingKind.BARRACKS -> 11 to 11
        BuildingKind.TEMPLE -> 9 to 13
        BuildingKind.WALLS, BuildingKind.OTHER -> 7 to 7
    }

    fun minSize(kind: BuildingKind): Pair<Int, Int> = when (kind) {
        BuildingKind.LIBRARY, BuildingKind.BARRACKS -> 9 to 9
        BuildingKind.TEMPLE -> 9 to 11
        else -> 7 to 7
    }

    fun generate(kind: BuildingKind, theme: Theme, w: Int, d: Int, seed: Long): List<PlacedBlock> {
        val s = Sketch(w, d, palette(theme), Random(seed), theme)
        when (kind) {
            BuildingKind.HOUSE -> s.house()
            BuildingKind.FARM -> s.farm()
            BuildingKind.LUMBER_CAMP -> s.lumberCamp()
            BuildingKind.QUARRY -> s.quarry()
            BuildingKind.MINE -> s.mine()
            BuildingKind.MARKET -> s.market()
            BuildingKind.LIBRARY -> s.library()
            BuildingKind.BARRACKS -> s.barracks()
            BuildingKind.TEMPLE -> s.temple()
            BuildingKind.WALLS, BuildingKind.OTHER -> s.storehouse()
        }
        return s.result()
    }

    /** Rotates a generated design and moves it so its footprint starts at ([minX], [minZ]) with ground layer y = [groundY]. */
    fun place(blocks: List<PlacedBlock>, rotation: Int, minX: Int, groundY: Int, minZ: Int): List<PlacedBlock> {
        val rot = Rotation.entries[rotation and 3]
        val turned = blocks.map { b ->
            val p = b.pos
            val (x, z) = when (rot) {
                Rotation.NONE -> p.x to p.z
                Rotation.CLOCKWISE_90 -> -p.z to p.x
                Rotation.CLOCKWISE_180 -> -p.x to -p.z
                Rotation.COUNTERCLOCKWISE_90 -> p.z to -p.x
            }
            PlacedBlock(BlockPos(x, p.y, z), b.state.rotate(rot), b.follow)
        }
        if (turned.isEmpty()) return turned
        val ox = minX - turned.minOf { it.pos.x }
        val oz = minZ - turned.minOf { it.pos.z }
        // Yard details keep a ground-relative y until settle() knows the terrain.
        return turned.map { PlacedBlock(it.pos.offset(ox, if (it.follow) 0 else groundY, oz), it.state, it.follow) }
    }

    /**
     * Fits a placed design to the terrain instead of flattening the lot: every column of the building body gets a
     * foundation from the real ground up to the body (stone on the outside, earth hidden inside), terrain is only cut
     * where the body itself stands, and yard details sit on the ground of their own column. [ground] is the top natural
     * block of a column, or null over water (yard details there are skipped).
     */
    fun settle(placed: List<PlacedBlock>, theme: Theme, groundY: Int, ground: (Int, Int) -> Int?): List<PlacedBlock> {
        val pal = palette(theme)
        val bottoms = HashMap<Long, Int>()
        for (b in placed) {
            if (b.follow || b.state.isAir) continue
            val key = BlockPos.asLong(b.pos.x, 0, b.pos.z)
            val old = bottoms[key]
            if (old == null || b.pos.y < old) bottoms[key] = b.pos.y
        }
        val out = ArrayList<PlacedBlock>(placed.size + bottoms.size * 2)
        val dirt = Blocks.DIRT.defaultBlockState()
        for ((key, bottom) in bottoms) {
            val x = BlockPos.getX(key)
            val z = BlockPos.getZ(key)
            val g = ground(x, z) ?: continue
            if (g >= bottom - 1) continue
            val edge = Direction.Plane.HORIZONTAL.any { BlockPos.asLong(x + it.stepX, 0, z + it.stepZ) !in bottoms }
            for (y in g + 1 until bottom) out += PlacedBlock(BlockPos(x, y, z), if (edge) pal.base else dirt)
        }
        for (b in placed) {
            if (!b.follow) {
                out += b
                continue
            }
            val key = BlockPos.asLong(b.pos.x, 0, b.pos.z)
            // A detail inside the body's footprint sits on the body's ground level; elsewhere on the column's own ground.
            val base = if (key in bottoms) groundY else ground(b.pos.x, b.pos.z) ?: continue
            out += PlacedBlock(BlockPos(b.pos.x, base + b.pos.y, b.pos.z), b.state)
        }
        out.sortWith(compareBy<PlacedBlock>({ it.pos.y }, { if (it.state.isAir) 0 else 1 }, { it.pos.x }, { it.pos.z }))
        return out
    }

    /** "gs:design/<kind>/<theme>" with an optional "@WxD" size. */
    fun id(kind: BuildingKind, theme: Theme, size: Pair<Int, Int>? = null): String =
        "gs:design/${kind.name.lowercase()}/${theme.id}" + (size?.let { "@${it.first}x${it.second}" } ?: "")

    fun parse(id: String): Triple<BuildingKind, Theme, Pair<Int, Int>?>? {
        if (!id.startsWith("gs:design/")) return null
        val (body, size) = id.removePrefix("gs:design/").split('@').let { it[0] to it.getOrNull(1) }
        val parts = body.split('/')
        val kind = runCatching { BuildingKind.valueOf(parts[0].uppercase()) }.getOrNull() ?: return null
        val dims = size?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        return Triple(kind, Theme.of(parts.getOrElse(1) { "plains" }), dims)
    }
}

/** Generic block-state tweaks by property name, so designs don't depend on each block class's property constants. */
internal fun BlockState.with(name: String, value: String): BlockState {
    val prop = block.stateDefinition.getProperty(name) ?: return this
    return withValue(prop, value)
}

private fun <T : Comparable<T>> BlockState.withValue(prop: Property<T>, value: String): BlockState =
    prop.getValue(value).map { setValue(prop, it) }.orElse(this)

/** Fully grown crop of any kind (wheat, carrots, beetroot...). */
internal fun ripe(block: Block): BlockState {
    val state = block.defaultBlockState()
    val age = block.stateDefinition.getProperty("age") ?: return state
    return maxed(state, age)
}

private fun <T : Comparable<T>> maxed(state: BlockState, prop: Property<T>): BlockState = state.setValue(prop, prop.possibleValues.max())

internal class Sketch(val w: Int, val d: Int, val p: Palette, val rng: Random, val theme: Theme) {
    private val blocks = HashMap<Long, BlockState>()
    private val decor = HashMap<Long, BlockState>()
    private val air = Blocks.AIR.defaultBlockState()

    fun set(x: Int, y: Int, z: Int, s: BlockState) {
        blocks[BlockPos.asLong(x, y, z)] = s
    }

    /** A yard detail [dy] above the ground of its column (0 replaces the ground block itself). */
    fun deco(x: Int, dy: Int, z: Int, s: BlockState) {
        decor[BlockPos.asLong(x, dy, z)] = s
    }

    fun get(x: Int, y: Int, z: Int): BlockState? = blocks[BlockPos.asLong(x, y, z)] ?: decor[BlockPos.asLong(x, y, z)]

    fun fill(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, s: BlockState) {
        for (x in minOf(x0, x1)..maxOf(x0, x1)) for (y in minOf(y0, y1)..maxOf(y0, y1)) for (z in minOf(z0, z1)..maxOf(z0, z1)) set(x, y, z, s)
    }

    /** Clears terrain from the volume the building itself occupies (never the whole lot). */
    fun clearBox(x0: Int, z0: Int, x1: Int, z1: Int, height: Int) = fill(x0, 1, z0, x1, height, z1, air)

    fun ring(x0: Int, z0: Int, x1: Int, z1: Int, y: Int, s: BlockState) {
        for (x in x0..x1) {
            set(x, y, z0, s)
            set(x, y, z1, s)
        }
        for (z in z0..z1) {
            set(x0, y, z, s)
            set(x1, y, z, s)
        }
    }

    fun log(axis: String) = p.log.defaultBlockState().with("axis", axis)
    fun stairs(b: Block, facing: String, top: Boolean = false) = b.defaultBlockState().with("facing", facing).with("half", if (top) "top" else "bottom")
    fun slab(b: Block, top: Boolean = false) = b.defaultBlockState().with("type", if (top) "top" else "bottom")
    fun lantern(hanging: Boolean = false) = Blocks.LANTERN.defaultBlockState().with("hanging", hanging.toString())

    fun door(x: Int, y: Int, z: Int, facing: String = "south") {
        set(x, y, z, p.door.defaultBlockState().with("facing", facing).with("half", "lower"))
        set(x, y + 1, z, p.door.defaultBlockState().with("facing", facing).with("half", "upper"))
    }

    /** Walls of a box with log corner posts and a stone plinth on the ground layer. */
    fun shell(x0: Int, z0: Int, x1: Int, z1: Int, top: Int, wall: BlockState = p.wall, corner: BlockState = log("y")) {
        fill(x0, -1, z0, x1, -1, z1, p.base)
        fill(x0, 0, z0, x1, 0, z1, p.floor)
        ring(x0, z0, x1, z1, 0, p.base)
        for (y in 1..top) ring(x0, z0, x1, z1, y, wall)
        for (y in 0..top) for ((x, z) in listOf(x0 to z0, x1 to z0, x0 to z1, x1 to z1)) set(x, y, z, corner)
    }

    /** Glass along the walls at [y], every other block, skipping corners and anything already open (doors). */
    fun windows(x0: Int, z0: Int, x1: Int, z1: Int, y: Int, pane: BlockState = p.glass, rows: Int = 1) {
        for (x in x0 + 2 until x1 step 2) for (z in listOf(z0, z1)) for (r in 0 until rows) if (get(x, y + r, z) == p.wall || get(x, y + r, z) == p.stone) set(x, y + r, z, pane)
        for (z in z0 + 2 until z1 step 2) for (x in listOf(x0, x1)) for (r in 0 until rows) if (get(x, y + r, z) == p.wall || get(x, y + r, z) == p.stone) set(x, y + r, z, pane)
    }

    /** Pitched roof over the box (one block of overhang), ridge along the longer side. Flat with a parapet in the desert. */
    fun roof(x0: Int, z0: Int, x1: Int, z1: Int, y0: Int, gable: BlockState = p.wall): Int {
        if (p.flatRoof) {
            fill(x0, y0, z0, x1, y0, z1, p.roof)
            for (x in x0..x1) for (z in listOf(z0, z1)) if ((x - x0) % 2 == 0) set(x, y0 + 1, z, p.trim)
            for (z in z0..z1) for (x in listOf(x0, x1)) if ((z - z0) % 2 == 0) set(x, y0 + 1, z, p.trim)
            return y0 + 1
        }
        val alongX = (x1 - x0) >= (z1 - z0)
        var lo = if (alongX) z0 - 1 else x0 - 1
        var hi = if (alongX) z1 + 1 else x1 + 1
        var y = y0
        val from = if (alongX) x0 - 1 else z0 - 1
        val to = if (alongX) x1 + 1 else z1 + 1
        while (lo <= hi) {
            for (k in from..to) {
                if (lo == hi) {
                    if (alongX) set(k, y, lo, slab(p.roofSlab)) else set(lo, y, k, slab(p.roofSlab))
                } else if (alongX) {
                    set(k, y, lo, stairs(p.roofStairs, "south"))
                    set(k, y, hi, stairs(p.roofStairs, "north"))
                } else {
                    set(lo, y, k, stairs(p.roofStairs, "east"))
                    set(hi, y, k, stairs(p.roofStairs, "west"))
                }
            }
            // Gable ends under this course of the roof.
            for (m in lo + 1 until hi) {
                if (alongX) {
                    set(x0, y, m, gable)
                    set(x1, y, m, gable)
                } else {
                    set(m, y, z0, gable)
                    set(m, y, z1, gable)
                }
            }
            lo++
            hi--
            y++
        }
        return y
    }

    // ---- Designs ----

    fun house() {
        val x0 = 1
        val z0 = 1
        val x1 = w - 2
        val z1 = d - 2
        clearBox(x0 - 1, z0 - 1, x1 + 1, z1 + 1, 10)
        shell(x0, z0, x1, z1, 3)
        fill(x0 + 1, 1, z0 + 1, x1 - 1, 3, z1 - 1, Blocks.AIR.defaultBlockState())
        val dx = (x0 + x1) / 2
        door(dx, 1, z0)
        windows(x0, z0, x1, z1, 2)
        val top = roof(x0, z0, x1, z1, 4)
        deco(dx, 0, 0, p.path)
        // Porch light on a post by the path.
        deco(dx - 2, 1, 0, p.fence)
        deco(dx - 2, 2, 0, lantern())
        // A little furniture: workbench, barrel, a lantern under the roof.
        set(x0 + 1, 1, z1 - 1, Blocks.CRAFTING_TABLE.defaultBlockState())
        set(x1 - 1, 1, z1 - 1, Blocks.BARREL.defaultBlockState().with("facing", "up"))
        set(x1 - 1, 1, z0 + 1, Blocks.POTTED_POPPY.defaultBlockState())
        set(dx, 3, (z0 + z1) / 2, lantern(hanging = true))
        if (!p.flatRoof && rng.nextBoolean()) {
            // Chimney through the roof with a smoking campfire on top.
            for (y in 0..top) set(x1 - 1, y, z1, p.base)
            set(x1 - 1, top + 1, z1, Blocks.CAMPFIRE.defaultBlockState().with("lit", "true"))
        }
        // Window boxes under the front windows.
        for (x in x0 + 2 until x1 step 2) if (get(x, 2, z0) == p.glass) deco(x, 1, z0 - 1, Blocks.POTTED_CORNFLOWER.defaultBlockState())
    }

    fun farm() {
        clearBox(0, 0, w - 1, d - 1, 4)
        val water = w / 2
        for (x in 1 until w - 1) for (z in 1 until d - 1) {
            if (x == water) {
                set(x, -1, z, p.base)
                set(x, 0, z, Blocks.WATER.defaultBlockState())
            } else {
                set(x, 0, z, Blocks.FARMLAND.defaultBlockState().with("moisture", "7"))
                val crop = p.crops[((x - 1) / 2) % p.crops.size]
                set(x, 1, z, ripe(crop))
            }
        }
        val border = if (theme == Theme.DESERT) p.stoneWall else p.fence
        ring(0, 0, w - 1, d - 1, 1, border)
        ring(0, 0, w - 1, d - 1, 0, p.base)
        set(water, 1, 0, p.gate.defaultBlockState().with("facing", "north"))
        set(water, 0, 0, p.path)
        for ((x, z) in listOf(0 to 0, w - 1 to 0, 0 to d - 1, w - 1 to d - 1)) set(x, 2, z, lantern())
        set(1, 1, 1, Blocks.COMPOSTER.defaultBlockState())
        // Scarecrow.
        set(w - 3, 1, d - 3, p.fence)
        set(w - 3, 2, d - 3, Blocks.HAY_BLOCK.defaultBlockState())
        set(w - 3, 3, d - 3, Blocks.CARVED_PUMPKIN.defaultBlockState().with("facing", "north"))
        set(w - 4, 2, d - 3, p.fence)
        set(w - 2, 2, d - 3, p.fence)
        set(w - 3, 0, d - 3, p.base)
        set(1, 1, d - 2, Blocks.HAY_BLOCK.defaultBlockState())
    }

    fun lumberCamp() {
        val sx = w / 2
        clearBox(0, 0, sx + 1, d - 1, 6)
        fill(0, 0, 0, sx + 1, 0, d - 1, p.yard)
        for (x in sx + 2 until w) for (z in 0 until d) deco(x, 0, z, p.yard)
        // Open shed full of seasoned logs.
        for ((x, z) in listOf(1 to 1, sx to 1, 1 to d - 2, sx to d - 2)) for (y in 1..3) set(x, y, z, log("y"))
        for (x in 0..sx + 1) for (z in 0 until d) set(x, 4, z, slab(p.roofSlab))
        for (x in 0..sx + 1) {
            set(x, 4, 0, stairs(p.roofStairs, "south"))
            set(x, 4, d - 1, stairs(p.roofStairs, "north"))
        }
        for (x in 2 until sx) for (z in 2 until d - 2) {
            set(x, 1, z, log("x"))
            if (z in 3 until d - 3) set(x, 2, z, log("x"))
        }
        set(sx - 1, 3, (d - 1) / 2, lantern(hanging = true))
        // Yard: chopping stumps, a sawbench, stacked logs and new saplings.
        deco(sx + 2, 1, 2, Blocks.STRIPPED_OAK_LOG.defaultBlockState().with("axis", "y"))
        deco(sx + 2, 1, d - 3, Blocks.STONECUTTER.defaultBlockState().with("facing", "north"))
        deco(sx + 3, 1, d - 3, Blocks.FLETCHING_TABLE.defaultBlockState())
        for (z in 1 until d - 1) {
            deco(w - 2, 1, z, log("z"))
            if (z in 2 until d - 2) deco(w - 2, 2, z, log("z"))
        }
        for (x in sx + 2 until w - 2 step 2) {
            deco(x, 0, d - 1, Blocks.GRASS_BLOCK.defaultBlockState())
            deco(x, 1, d - 1, p.sapling)
        }
        deco(sx + 2, 1, 0, p.fence)
        deco(sx + 2, 2, 0, lantern())
    }

    fun quarry() {
        clearBox(1, 1, w - 2, d - 2, 6)
        val floor = listOf(Blocks.STONE.defaultBlockState(), Blocks.ANDESITE.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState())
        val levels = minOf(4, (minOf(w, d) - 1) / 2)
        // Stepped pit: each terrace one block lower and one block narrower.
        for (k in 1..levels) for (x in k until w - k) for (z in k until d - k) {
            for (y in -k + 1..0) set(x, y, z, Blocks.AIR.defaultBlockState())
            set(x, -k, z, if (theme == Theme.DESERT) p.stone else floor[rng.nextInt(floor.size)])
        }
        for (x in 0 until w) for (z in listOf(0, d - 1)) {
            deco(x, 0, z, p.base)
            if (x != w / 2) deco(x, 1, z, p.stoneWall)
        }
        for (z in 0 until d) for (x in listOf(0, w - 1)) {
            deco(x, 0, z, p.base)
            deco(x, 1, z, p.stoneWall)
        }
        deco(w / 2, 0, 0, p.path)
        // Crane: a post, a jib over the pit, and a chain with a block of stone on the hook.
        for (y in 1..5) set(w - 1, y, d - 1, log("y"))
        for (x in w / 2..w - 1) set(x, 6, d - 1, log("x"))
        for (z in d / 2..d - 1) set(w / 2, 6, z, log("z"))
        for (y in 2..5) set(w / 2, y, d / 2, Blocks.IRON_CHAIN.defaultBlockState().with("axis", "y"))
        set(w / 2, 1, d / 2, Blocks.COBBLESTONE.defaultBlockState())
        // Cut stone waiting to be carted off.
        deco(0, 2, d - 1, Blocks.COBBLESTONE.defaultBlockState())
        deco(1, 1, d - 1, Blocks.STONE_BRICKS.defaultBlockState())
        deco(0, 2, 1, Blocks.STONECUTTER.defaultBlockState().with("facing", "east"))
        deco(w - 1, 2, 0, lantern())
        deco(0, 2, 0, lantern())
    }

    fun mine() {
        val cx = w / 2
        val cz = d - 3
        clearBox(cx - 2, cz - 2, cx + 2, cz + 2, 9)
        for (x in 0 until w) for (z in 0 until d) deco(x, 0, z, Blocks.GRAVEL.defaultBlockState())
        val depth = 7
        // Shaft lined with cobblestone, a ladder down the north side.
        for (y in -depth..0) for (x in cx - 2..cx + 2) for (z in cz - 2..cz + 2) {
            val inner = kotlin.math.abs(x - cx) <= 1 && kotlin.math.abs(z - cz) <= 1
            set(x, y, z, if (inner) Blocks.AIR.defaultBlockState() else Blocks.COBBLESTONE.defaultBlockState())
        }
        fill(cx - 1, -depth - 1, cz - 1, cx + 1, -depth - 1, cz + 1, Blocks.STONE.defaultBlockState())
        for (y in -depth..0) set(cx, y, cz - 1, Blocks.LADDER.defaultBlockState().with("facing", "south"))
        // Headframe with a winding wheel and the hoist chain.
        for ((x, z) in listOf(cx - 2 to cz - 2, cx + 2 to cz - 2, cx - 2 to cz + 2, cx + 2 to cz + 2)) for (y in 1..5) set(x, y, z, log("y"))
        for (x in cx - 2..cx + 2) {
            set(x, 6, cz - 2, log("x"))
            set(x, 6, cz + 2, log("x"))
            set(x, 6, cz, log("x"))
        }
        for (z in cz - 2..cz + 2) {
            set(cx - 2, 6, z, log("z"))
            set(cx + 2, 6, z, log("z"))
        }
        set(cx, 7, cz, Blocks.GRINDSTONE.defaultBlockState().with("face", "floor").with("facing", "east"))
        for (y in 1..5) set(cx + 1, y, cz, Blocks.IRON_CHAIN.defaultBlockState().with("axis", "y"))
        // Rails from the gate to the shaft lip.
        for (z in 0 until cz - 2) deco(cx, 1, z, Blocks.RAIL.defaultBlockState().with("shape", "north_south"))
        // Ore piles and the smithy.
        deco(1, 1, 1, Blocks.COAL_ORE.defaultBlockState())
        deco(1, 1, 2, Blocks.IRON_ORE.defaultBlockState())
        deco(1, 2, 1, Blocks.COAL_ORE.defaultBlockState())
        deco(2, 1, 1, Blocks.COPPER_ORE.defaultBlockState())
        deco(w - 2, 1, 1, Blocks.SMITHING_TABLE.defaultBlockState())
        deco(w - 2, 1, 2, Blocks.BLAST_FURNACE.defaultBlockState().with("facing", "west"))
        deco(cx - 1, 1, 0, p.fence)
        deco(cx - 1, 2, 0, lantern())
        deco(cx + 1, 1, 0, p.fence)
        deco(cx + 1, 2, 0, lantern())
    }

    fun market() {
        for (x in 0 until w) for (z in 0 until d) {
            val edge = x == 0 || z == 0 || x == w - 1 || z == d - 1
            deco(x, 0, z, if (edge) p.base else p.path)
        }
        val goods = listOf(Blocks.MELON, Blocks.PUMPKIN, Blocks.HAY_BLOCK, Blocks.BARREL)
        var z0 = 1
        var stall = 0
        while (z0 + 2 < d - 1) {
            for (left in listOf(true, false)) {
                val xa = if (left) 0 else w - 3
                val counter = if (left) xa + 2 else xa
                val back = if (left) xa else xa + 2
                val stripe = if (stall % 2 == 0) p.awning else p.awning.second to p.awning.first
                clearBox(xa, z0, xa + 2, z0 + 2, 4)
                fill(xa, 0, z0, xa + 2, 0, z0 + 2, p.path)
                for (z in z0..z0 + 2) {
                    set(counter, 1, z, p.floor)
                    set(back, 1, z, goods[(stall + z) % goods.size].defaultBlockState().let { if (it.block == Blocks.BARREL) it.with("facing", "up") else it })
                    for (x in xa..xa + 2) set(x, 3, z, if ((z - z0) % 2 == 0) stripe.first else stripe.second)
                }
                for (z in listOf(z0, z0 + 2)) {
                    set(counter, 2, z, p.fence)
                    set(back, 1, z, p.fence)
                    set(back, 2, z, p.fence)
                }
                set(xa + 1, 2, z0 + 1, lantern(hanging = true))
                stall++
            }
            z0 += 4
        }
        // A well in the middle of the square.
        val cx = w / 2
        val cz = d / 2
        clearBox(cx - 1, cz - 1, cx + 1, cz + 1, 4)
        fill(cx - 1, 0, cz - 1, cx + 1, 0, cz + 1, p.base)
        set(cx, -1, cz, p.base)
        set(cx, 0, cz, Blocks.WATER.defaultBlockState())
        for ((x, z) in listOf(cx - 1 to cz, cx + 1 to cz, cx to cz - 1, cx to cz + 1)) set(x, 1, z, p.stoneWall)
        for ((x, z) in listOf(cx - 1 to cz - 1, cx + 1 to cz - 1, cx - 1 to cz + 1, cx + 1 to cz + 1)) {
            set(x, 1, z, p.fence)
            set(x, 2, z, p.fence)
        }
        fill(cx - 1, 3, cz - 1, cx + 1, 3, cz + 1, slab(p.roofSlab))
        set(cx, 2, cz, lantern(hanging = true))
    }

    fun library() {
        val x0 = 1
        val z0 = 1
        val x1 = w - 2
        val z1 = d - 2
        clearBox(x0 - 1, z0 - 1, x1 + 1, z1 + 1, 14)
        // Stone ground floor walls, upper walls in the theme's wall material, stone corner piers.
        shell(x0, z0, x1, z1, 6, corner = p.stone)
        for (y in 1..2) ring(x0, z0, x1, z1, y, p.stone)
        for (y in 0..6) for ((x, z) in listOf(x0 to z0, x1 to z0, x0 to z1, x1 to z1)) set(x, y, z, p.stone)
        fill(x0 + 1, 1, z0 + 1, x1 - 1, 6, z1 - 1, Blocks.AIR.defaultBlockState())
        // Tall windows.
        windows(x0, z0, x1, z1, 2, rows = 3)
        val dx = (x0 + x1) / 2
        door(dx, 1, z0)
        set(dx, 3, z0, p.stone)
        // Shelves lining the inside walls (seen through the windows), reading desks in the middle.
        for (y in 1..3) {
            for (z in z0 + 1..z1 - 1) {
                set(x0 + 1, y, z, Blocks.BOOKSHELF.defaultBlockState())
                set(x1 - 1, y, z, Blocks.BOOKSHELF.defaultBlockState())
            }
            for (x in x0 + 1..x1 - 1) if (x != dx) set(x, y, z1 - 1, Blocks.BOOKSHELF.defaultBlockState())
        }
        for (z in z0 + 2..z1 - 2 step 2) set(dx, 1, z, Blocks.LECTERN.defaultBlockState().with("facing", "north"))
        for (z in z0 + 1..z1 - 2) if (get(dx, 1, z)?.isAir != false) set(dx, 1, z, Blocks.CARPET.pick(DyeColor.RED).defaultBlockState())
        // Gallery floor and a railing above the shelves.
        for (z in z0 + 1..z1 - 1) {
            set(x0 + 1, 4, z, p.floor)
            set(x1 - 1, 4, z, p.floor)
            set(x0 + 1, 5, z, p.fence)
            set(x1 - 1, 5, z, p.fence)
        }
        set(dx, 6, (z0 + z1) / 2, lantern(hanging = true))
        set(dx, 6, z0 + 2, lantern(hanging = true))
        roof(x0, z0, x1, z1, 7, p.stone)
        // Entrance path and lamps.
        deco(dx, 0, 0, p.path)
        deco(dx - 1, 1, 0, p.fence)
        deco(dx - 1, 2, 0, lantern())
        deco(dx + 1, 1, 0, p.fence)
        deco(dx + 1, 2, 0, lantern())
    }

    fun barracks() {
        clearBox(0, 0, w - 1, d - 1, 8)
        fill(0, 0, 0, w - 1, 0, d - 1, Blocks.GRAVEL.defaultBlockState())
        // Curtain wall with crenellations and taller corner turrets.
        fill(0, -1, 0, w - 1, -1, d - 1, p.base)
        for (y in 0..3) ring(0, 0, w - 1, d - 1, y, p.stone)
        for (x in 0 until w) for (z in listOf(0, d - 1)) if (x % 2 == 0) set(x, 4, z, p.stone)
        for (z in 0 until d) for (x in listOf(0, w - 1)) if (z % 2 == 0) set(x, 4, z, p.stone)
        for ((x, z) in listOf(0 to 0, w - 1 to 0, 0 to d - 1, w - 1 to d - 1)) {
            set(x, 4, z, p.stone)
            set(x, 5, z, slab(p.stoneSlab))
        }
        // Gate.
        val c = w / 2
        fill(c - 1, 1, 0, c + 1, 2, 0, Blocks.AIR.defaultBlockState())
        fill(c - 1, 0, 0, c + 1, 0, 0, p.path)
        set(c, 2, 0, lantern(hanging = true))
        // Hall along the back wall.
        val hz = d - 5
        shell(1, hz, w - 2, d - 2, 3)
        fill(2, 1, hz + 1, w - 3, 3, d - 3, Blocks.AIR.defaultBlockState())
        fill(1, 4, hz, w - 2, 4, d - 2, slab(p.stoneSlab))
        door(c, 1, hz)
        windows(1, hz, w - 2, d - 2, 2)
        set(2, 1, d - 3, Blocks.SMITHING_TABLE.defaultBlockState())
        set(3, 1, d - 3, Blocks.ANVIL.defaultBlockState().with("facing", "east"))
        set(w - 3, 1, d - 3, Blocks.GRINDSTONE.defaultBlockState().with("face", "floor"))
        // Training yard: straw targets and the flag.
        for (x in listOf(2, w - 3)) {
            set(x, 1, 2, Blocks.HAY_BLOCK.defaultBlockState())
            set(x, 2, 2, Blocks.TARGET.defaultBlockState())
        }
        for (y in 1..7) set(c, y, hz - 2, p.fence)
        for (y in 6..7) for (x in c + 1..c + 2) set(x, y, hz - 2, Blocks.WOOL.pick(DyeColor.RED).defaultBlockState())
    }

    fun temple() {
        val c = w / 2
        val x0 = 1
        val x1 = w - 2
        val z0 = 3
        val z1 = d - 2
        clearBox(x0 - 1, z0 - 1, x1 + 1, z1 + 1, 16)
        clearBox(c - 1, 0, c + 1, 2, 16)
        // Nave.
        shell(x0, z0, x1, z1, 5, wall = p.stone, corner = p.stone)
        fill(x0 + 1, 1, z0 + 1, x1 - 1, 5, z1 - 1, Blocks.AIR.defaultBlockState())
        for (z in z0 + 2 until z1 step 2) for (x in listOf(x0, x1)) for (y in 2..4) set(x, y, z, p.stained)
        set(c, 3, z1, p.stained)
        set(c, 4, z1, p.stained)
        roof(x0, z0, x1, z1, 6, p.stone)
        // Bell tower over the entrance.
        val top = 10
        for (y in 0..top) for (x in c - 1..c + 1) for (z in 0..2) {
            val edge = x != c || z == 0 || z == 2
            set(x, y, z, if (y == 0) p.base else if (edge) p.stone else Blocks.AIR.defaultBlockState())
        }
        set(c, 0, 1, p.floor)
        door(c, 1, 0)
        // Open belfry with the bell hanging inside.
        for (y in 8..9) {
            set(c, y, 0, Blocks.AIR.defaultBlockState())
            set(c, y, 2, Blocks.AIR.defaultBlockState())
            set(c - 1, y, 1, Blocks.AIR.defaultBlockState())
            set(c + 1, y, 1, Blocks.AIR.defaultBlockState())
        }
        set(c, top, 1, p.stone)
        set(c, 9, 1, Blocks.BELL.defaultBlockState().with("attachment", "ceiling").with("facing", "north"))
        // Through the tower into the nave.
        for (y in 1..2) {
            set(c, y, 2, Blocks.AIR.defaultBlockState())
            set(c, y, z0, Blocks.AIR.defaultBlockState())
        }
        if (p.flatRoof) {
            for ((x, z) in listOf(c - 1 to 0, c + 1 to 0, c - 1 to 2, c + 1 to 2)) set(x, top + 1, z, p.trim)
        } else {
            for (x in c - 1..c + 1) {
                set(x, top + 1, 0, stairs(p.roofStairs, "south"))
                set(x, top + 1, 2, stairs(p.roofStairs, "north"))
            }
            set(c - 1, top + 1, 1, stairs(p.roofStairs, "east"))
            set(c + 1, top + 1, 1, stairs(p.roofStairs, "west"))
            set(c, top + 1, 1, p.roof)
            set(c, top + 2, 1, Blocks.END_ROD.defaultBlockState().with("facing", "up"))
        }
        // Inside: an aisle, pews and the altar.
        for (z in z0..z1 - 2) set(c, 1, z, Blocks.CARPET.pick(DyeColor.RED).defaultBlockState())
        for (z in z0 + 2 until z1 - 2 step 2) for (x in listOf(c - 2, c - 1, c + 1, c + 2)) if (x in x0 + 1..x1 - 1) set(x, 1, z, stairs(p.roofStairs, "north"))
        set(c, 1, z1 - 1, p.stone)
        set(c - 1, 1, z1 - 1, slab(p.stoneSlab, top = true))
        set(c + 1, 1, z1 - 1, slab(p.stoneSlab, top = true))
        set(c, 2, z1 - 1, Blocks.CANDLE.defaultBlockState().with("candles", "3").with("lit", "true"))
        set(c, 5, (z0 + z1) / 2, lantern(hanging = true))
        deco(c, 0, 0, p.path)
    }

    fun storehouse() {
        val x0 = 1
        val z0 = 1
        val x1 = w - 2
        val z1 = d - 2
        clearBox(x0 - 1, z0 - 1, x1 + 1, z1 + 1, 9)
        shell(x0, z0, x1, z1, 3)
        fill(x0 + 1, 1, z0 + 1, x1 - 1, 3, z1 - 1, Blocks.AIR.defaultBlockState())
        val dx = (x0 + x1) / 2
        fill(dx - 1, 1, z0, dx + 1, 2, z0, Blocks.AIR.defaultBlockState())
        roof(x0, z0, x1, z1, 4)
        for (x in x0 + 1..x1 - 1) for (y in 1..2) if (x != dx) set(x, y, z1 - 1, Blocks.BARREL.defaultBlockState().with("facing", "up"))
        set(x0 + 1, 1, z0 + 1, Blocks.HAY_BLOCK.defaultBlockState())
        deco(dx, 0, 0, p.path)
    }

    /** Sorted for placement (lowest first, air before blocks on a layer), with fences, panes and walls joined up. */
    fun result(): List<PlacedBlock> {
        val out = ArrayList<PlacedBlock>(blocks.size)
        for ((key, state) in blocks) {
            val pos = BlockPos.of(key)
            out += PlacedBlock(pos, connect(pos, state))
        }
        for ((key, state) in decor) {
            val pos = BlockPos.of(key)
            if (blocks.containsKey(key)) continue
            out += PlacedBlock(pos, connect(pos, state), follow = true)
        }
        out.sortWith(compareBy<PlacedBlock>({ it.pos.y }, { if (it.state.isAir) 0 else 1 }, { it.pos.x }, { it.pos.z }))
        return out
    }

    private fun connect(pos: BlockPos, state: BlockState): BlockState {
        val b = state.block
        if (b !is FenceBlock && b !is IronBarsBlock && b !is WallBlock) return state
        var s = state
        for (dir in Direction.Plane.HORIZONTAL) {
            val n = get(pos.x + dir.stepX, pos.y, pos.z + dir.stepZ)
            val joins = n != null && !n.isAir && when (b) {
                is FenceBlock -> n.block is FenceBlock || n.block is FenceGateBlock || n.isSolidRender
                is IronBarsBlock -> n.block is IronBarsBlock || n.isSolidRender
                else -> n.block is WallBlock || n.block is FenceGateBlock || n.isSolidRender
            }
            val name = dir.serializedName
            s = if (b is WallBlock) s.with(name, if (joins) "low" else "none") else s.with(name, joins.toString())
        }
        if (b is WallBlock) {
            val ns = s.getValue(WallBlock.NORTH) != net.minecraft.world.level.block.state.properties.WallSide.NONE &&
                s.getValue(WallBlock.SOUTH) != net.minecraft.world.level.block.state.properties.WallSide.NONE
            val ew = s.getValue(WallBlock.EAST) != net.minecraft.world.level.block.state.properties.WallSide.NONE &&
                s.getValue(WallBlock.WEST) != net.minecraft.world.level.block.state.properties.WallSide.NONE
            val straight = (ns && !ew && s.getValue(WallBlock.EAST) == net.minecraft.world.level.block.state.properties.WallSide.NONE) ||
                (ew && !ns && s.getValue(WallBlock.NORTH) == net.minecraft.world.level.block.state.properties.WallSide.NONE)
            s = s.with("up", (!straight).toString())
        }
        return s
    }
}
