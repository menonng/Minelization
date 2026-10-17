package com.grandstrategy.client.ui

import com.grandstrategy.client.mixin.GuiGraphicsExtractorAccessor
import com.grandstrategy.mc.build.PlacedBlock
import com.grandstrategy.sim.Box
import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.math.Axis
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.block.MovingBlockRenderState
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import net.minecraft.core.BlockPos
import net.minecraft.world.level.CardinalLighting
import net.minecraft.world.level.ColorResolver
import net.minecraft.world.level.LightLayer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A small 3D model of a building drawn inside a GUI: the blocks are rendered into an off-screen picture (the same
 * picture-in-picture path vanilla uses for the player model in the inventory), slowly turning, fully lit.
 */
class PreviewModel(blocks: List<PlacedBlock>) {
    private val map = HashMap<Long, BlockState>()
    val parts: List<PreviewBlock>
    val centerX: Float
    val centerY: Float
    val centerZ: Float
    val span: Float

    init {
        for (b in blocks) if (!b.state.isAir) map[b.pos.asLong()] = b.state
        val level = Minecraft.getInstance().level
        val biome = level?.let { l -> Minecraft.getInstance().player?.let { l.getBiome(it.blockPosition()) } }
        parts = blocks.filter { !it.state.isAir && it.state.renderShape == RenderShape.MODEL }.map { b ->
            PreviewBlock(this).also {
                it.blockPos = b.pos
                it.randomSeedPos = b.pos
                it.blockState = b.state
                it.biome = biome
                it.cardinalLighting = CardinalLighting.DEFAULT
                level?.let { l -> it.lightEngine = l.lightEngine }
            }
        }
        val solid = blocks.filter { !it.state.isAir }
        if (solid.isEmpty()) {
            centerX = 0f; centerY = 0f; centerZ = 0f; span = 1f
        } else {
            val minX = solid.minOf { it.pos.x }
            val maxX = solid.maxOf { it.pos.x } + 1
            val minY = solid.minOf { it.pos.y }
            val maxY = solid.maxOf { it.pos.y } + 1
            val minZ = solid.minOf { it.pos.z }
            val maxZ = solid.maxOf { it.pos.z } + 1
            centerX = (minX + maxX) / 2f
            centerY = (minY + maxY) / 2f
            centerZ = (minZ + maxZ) / 2f
            val dx = (maxX - minX).toFloat()
            val dz = (maxZ - minZ).toFloat()
            span = max(sqrt(dx * dx + dz * dz), (maxY - minY) * 1.4f).coerceAtLeast(1f)
        }
    }

    fun at(pos: BlockPos): BlockState = map[pos.asLong()] ?: Blocks.AIR.defaultBlockState()

    val empty get() = parts.isEmpty()

    companion object {
        /** The blocks of a building as the client sees them right now. */
        fun fromWorld(level: ClientLevel, box: Box): PreviewModel {
            val out = ArrayList<PlacedBlock>()
            val maxSpan = 48
            for (x in box.minX..minOf(box.maxX, box.minX + maxSpan)) for (y in box.minY..minOf(box.maxY, box.minY + maxSpan)) for (z in box.minZ..minOf(box.maxZ, box.minZ + maxSpan)) {
                val pos = BlockPos(x, y, z)
                val state = level.getBlockState(pos)
                if (!state.isAir && state.fluidState.isEmpty) out += PlacedBlock(pos, state)
            }
            return PreviewModel(out)
        }
    }
}

/** One block of a preview. Neighbour lookups go to the model so hidden faces are culled; light is always full. */
class PreviewBlock(private val model: PreviewModel) : MovingBlockRenderState() {
    override fun getBlockState(pos: BlockPos): BlockState = model.at(pos)
    override fun getFluidState(pos: BlockPos): FluidState = model.at(pos).fluidState
    override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
    override fun getBrightness(layer: LightLayer, pos: BlockPos): Int = 15
    override fun getRawBrightness(pos: BlockPos, amount: Int): Int = 15
    override fun getBlockTint(pos: BlockPos, resolver: ColorResolver): Int =
        biome?.let { resolver.getColor(it.value(), pos.x.toDouble(), pos.z.toDouble()) } ?: -1
}

class BuildingPreviewState(
    val model: PreviewModel,
    val yaw: Float,
    private val left: Int,
    private val top: Int,
    private val right: Int,
    private val bottom: Int,
    private val pixelsPerBlock: Float
) : PictureInPictureRenderState {
    private val area: ScreenRectangle? = PictureInPictureRenderState.getBounds(left, top, right, bottom, null)
    override fun x0() = left
    override fun x1() = right
    override fun y0() = top
    override fun y1() = bottom
    override fun scale() = pixelsPerBlock
    override fun scissorArea(): ScreenRectangle? = null
    override fun bounds(): ScreenRectangle? = area
}

class BuildingPreviewRenderer : PictureInPictureRenderer<BuildingPreviewState>() {
    override fun getRenderStateClass(): Class<BuildingPreviewState> = BuildingPreviewState::class.java

    override fun getTextureLabel(): String = "grandstrategy building preview"

    /** Vanilla anchors picture-in-picture models near the bottom (an entity's feet); a building is centred. */
    override fun getTranslateY(height: Int, guiScale: Int): Float = height / 2f

    override fun renderToTexture(state: BuildingPreviewState, pose: PoseStack, collector: SubmitNodeCollector) {
        Minecraft.getInstance().gameRenderer.lighting().setupFor(Lighting.Entry.ITEMS_3D)
        val m = state.model
        pose.rotateDegrees(Axis.XP, 180f + 28f)
        pose.rotateDegrees(Axis.YP, state.yaw)
        pose.translate(-m.centerX, -m.centerY, -m.centerZ)
        for (part in m.parts) {
            pose.pushPose()
            pose.translate(part.blockPos.x.toFloat(), part.blockPos.y.toFloat(), part.blockPos.z.toFloat())
            collector.submitMovingBlock(pose, part, 0)
            pose.popPose()
        }
    }
}

object BuildingPreview {
    fun init() {
        PictureInPictureRendererRegistry.register { BuildingPreviewRenderer() }
    }

    /** Draws [model] turning slowly inside the given screen rectangle. */
    fun draw(g: GuiGraphicsExtractor, model: PreviewModel, x0: Int, y0: Int, x1: Int, y1: Int) {
        if (model.empty) return
        val size = minOf(x1 - x0, y1 - y0).toFloat()
        val yaw = (System.currentTimeMillis() % 16000L) / 16000f * 360f
        val state = BuildingPreviewState(model, yaw, x0, y0, x1, y1, size / model.span)
        (g as GuiGraphicsExtractorAccessor).`grandstrategy$guiRenderState`().addPicturesInPictureState(state)
    }
}
