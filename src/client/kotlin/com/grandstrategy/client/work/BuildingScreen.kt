package com.grandstrategy.client.work

import com.grandstrategy.client.ui.BuildingPreview
import com.grandstrategy.client.ui.IconButton
import com.grandstrategy.client.ui.PreviewModel
import com.grandstrategy.client.ui.Ui
import com.grandstrategy.mc.build.Designs
import com.grandstrategy.mc.build.Theme
import com.grandstrategy.net.BuildingView
import com.grandstrategy.net.ToServer
import com.grandstrategy.sim.BuildingKind
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class BuildingScreen(private val building: BuildingView) : Screen(Component.literal("건물 #${building.id}")) {
    private val panelW get() = minOf(456, width - 8)
    private val panelH get() = minOf(240, height - 4)
    /** Button width shrinks on small screens so the preview always keeps its corner. */
    private val bw get() = ((panelW - 160) / 3).coerceIn(70, 98)
    private val col get() = bw + 4
    private val left get() = (width - panelW) / 2
    private val top get() = (height - panelH) / 2
    private val kinds = BuildingKind.entries.filter { it != BuildingKind.WALLS }
    private val gridY get() = top + 134
    private var renovate = true
    private val theme = Theme.of(building.theme)
    private var current: PreviewModel? = null
    private val designs = HashMap<BuildingKind, PreviewModel>()

    // Preview frame in the top-right corner of the panel.
    private val px0 get() = left + 14 + 3 * col + 8
    private val py0 get() = top + 30
    private val px1 get() = left + panelW - 12
    private val py1 get() = top + 152

    override fun init() {
        val x0 = left + 14
        val y = top + 82
        addRenderableWidget(IconButton(x0, y, bw, 22, Component.literal("철거"), Items.TNT) {
            WorkMode.send(ToServer.BuildingAction(building.id, "demolish"))
            onClose()
        })
        addRenderableWidget(IconButton(x0 + col, y, bw, 22, Component.literal("징용 철거"), Items.IRON_SHOVEL) {
            WorkMode.send(ToServer.BuildingAction(building.id, "demolish", conscript = true))
            onClose()
        })
        if (building.detected) {
            addRenderableWidget(IconButton(x0 + 2 * col, y, bw, 22, Component.literal("인식 해제"), Items.BARRIER) {
                WorkMode.send(ToServer.BuildingAction(building.id, "forget"))
                onClose()
            })
        }
        val toggle = IconButton(x0 + 64, gridY - 22, 3 * col - 68, 18, Component.literal(if (renovate) "외형도 바꾼다 (개축)" else "이름표만 바꾼다"),
            if (renovate) Items.BRICKS else Items.NAME_TAG, { renovate }) {
            renovate = !renovate
            rebuildWidgets()
        }
        toggle.setTooltip(Tooltip.create(Component.literal(
            "켜면 용도를 고를 때 그 용도의 설계(${theme.label} 양식)로 다시 짓는다: 기존 블록을 헐고 새 건물을 올린다. 끄면 용도만 바꾼다."
        )))
        addRenderableWidget(toggle)
        kinds.forEachIndexed { k, kind ->
            val col = k % 3
            val row = k / 3
            addRenderableWidget(IconButton(x0 + col * this.col, gridY + row * 24, bw, 22, Component.literal(kind.label), Ui.kindIcon(kind), { kind == building.kind }) {
                WorkMode.send(ToServer.BuildingAction(building.id, if (renovate) "renovate" else "assign", kind))
                onClose()
            })
        }
        addRenderableWidget(IconButton(left + panelW - 30, top + 6, 22, 18, Component.literal(""), Items.BARRIER) { onClose() })
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        WorkMode.selected = null
        super.onClose()
    }

    private fun hoveredKind(mouseX: Int, mouseY: Int): BuildingKind? {
        val x0 = left + 14
        kinds.forEachIndexed { k, kind ->
            val x = x0 + (k % 3) * col
            val y = gridY + (k / 3) * 24
            if (mouseX in x until x + bw && mouseY in y until y + 22) return kind
        }
        return null
    }

    /** The design a renovation to [kind] would build here, at this footprint (or the design's minimum if it's smaller). */
    private fun design(kind: BuildingKind): PreviewModel = designs.getOrPut(kind) {
        val b = building.box
        val (w0, d0) = if (building.facing % 2 == 1) b.sizeZ to b.sizeX else b.sizeX to b.sizeZ
        val (mw, md) = Designs.minSize(kind)
        PreviewModel(Designs.generate(kind, theme, maxOf(w0, mw), maxOf(d0, md), building.id.toLong()))
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        graphics.fillGradient(0, 0, width, height, Ui.argb(0x000000, 90), Ui.argb(0x000000, 170))
        Ui.panel(graphics, left, top, panelW, panelH, parchment = true)
        val owner = building.owner.ifEmpty { "주인 없음" }
        Ui.ribbon(graphics, font, left + 10, top + 8, panelW - 46, if (building.own) 0x3C8CC8 else 0x8A8A8A,
            Component.literal("${building.kind.label} #${building.id} · $owner"), ItemStack(Ui.kindIcon(building.kind)))
        val b = building.box
        val pose = graphics.pose()
        pose.pushMatrix()
        pose.translate((left + 16).toFloat(), (top + 32).toFloat())
        pose.scale(2f, 2f)
        Ui.slot(graphics, 0, 0, Ui.kindIcon(building.kind))
        pose.popMatrix()
        val tx = left + 60
        graphics.text(font, Component.literal("크기 ${b.sizeX}×${b.sizeY}×${b.sizeZ} · ${theme.label} 양식"), tx, top + 34, Ui.argb(Ui.INK), false)
        graphics.text(font, Component.literal("(${b.minX}, ${b.minY}, ${b.minZ}) ~ (${b.maxX}, ${b.maxY}, ${b.maxZ})"), tx, top + 46, Ui.argb(0x6B5A44), false)
        val status = (if (building.complete) "완공" else "공사 중") + if (building.detected) " · 외곽 블록으로 자동 인식" else ""
        graphics.text(font, Component.literal(status), tx, top + 58, Ui.argb(0x6B5A44), false)
        graphics.text(font, Component.literal("작업"), left + 14, top + 72, Ui.argb(Ui.INK), false)
        graphics.text(font, Component.literal("용도 지정"), left + 14, gridY - 18, Ui.argb(Ui.INK), false)
        // Preview frame: a dark well with a gold rim.
        graphics.fill(px0 - 2, py0 - 2, px1 + 2, py1 + 2, Ui.argb(0xB08A4A))
        graphics.fillGradient(px0, py0, px1, py1, Ui.argb(0x2A3A4A), Ui.argb(0x101418))
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val hover = hoveredKind(mouseX, mouseY)
        val model = if (hover != null) design(hover) else {
            current ?: Minecraft.getInstance().level?.let { PreviewModel.fromWorld(it, building.box) }?.also { current = it }
        }
        if (model != null) BuildingPreview.draw(graphics, model, px0 + 2, py0 + 2, px1 - 2, py1 - 2)
        val caption = if (hover != null) "${hover.label} 설계 · ${theme.label}" else "지금 모습"
        graphics.text(font, Component.literal(caption), px0 + (px1 - px0 - font.width(caption)) / 2, py1 + 5, Ui.argb(Ui.INK), false)
        if (hover != null) {
            val tip = if (renovate) "누르면 이 모습으로 개축" else "누르면 용도만 바꾼다"
            graphics.text(font, Component.literal(tip), px0 + (px1 - px0 - font.width(tip)) / 2, py1 + 16, Ui.argb(0x6B5A44), false)
        }
    }
}
