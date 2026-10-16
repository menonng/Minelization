package com.grandstrategy.client.work

import com.grandstrategy.client.ui.IconButton
import com.grandstrategy.client.ui.Ui
import com.grandstrategy.net.BuildingView
import com.grandstrategy.net.ToServer
import com.grandstrategy.sim.BuildingKind
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class BuildingScreen(private val building: BuildingView) : Screen(Component.literal("건물 #${building.id}")) {
    private val panelW = 330
    private val panelH = 236
    private val left get() = (width - panelW) / 2
    private val top get() = (height - panelH) / 2

    override fun init() {
        val x0 = left + 14
        var y = top + 82
        addRenderableWidget(IconButton(x0, y, 98, 22, Component.literal("철거"), Items.TNT) {
            WorkMode.send(ToServer.BuildingAction(building.id, "demolish"))
            onClose()
        })
        addRenderableWidget(IconButton(x0 + 102, y, 98, 22, Component.literal("징용 철거"), Items.IRON_SHOVEL) {
            WorkMode.send(ToServer.BuildingAction(building.id, "demolish", conscript = true))
            onClose()
        })
        if (building.detected) {
            addRenderableWidget(IconButton(x0 + 204, y, 98, 22, Component.literal("인식 해제"), Items.BARRIER) {
                WorkMode.send(ToServer.BuildingAction(building.id, "forget"))
                onClose()
            })
        }
        y += 44
        val kinds = BuildingKind.entries.filter { it != BuildingKind.WALLS }
        kinds.forEachIndexed { k, kind ->
            val col = k % 3
            val row = k / 3
            addRenderableWidget(IconButton(x0 + col * 102, y + row * 24, 98, 22, Component.literal(kind.label), Ui.kindIcon(kind), { kind == building.kind }) {
                WorkMode.send(ToServer.BuildingAction(building.id, "assign", kind))
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
        graphics.text(font, Component.literal("크기 ${b.sizeX}×${b.sizeY}×${b.sizeZ}"), tx, top + 34, Ui.argb(Ui.INK), false)
        graphics.text(font, Component.literal("(${b.minX}, ${b.minY}, ${b.minZ}) ~ (${b.maxX}, ${b.maxY}, ${b.maxZ})"), tx, top + 46, Ui.argb(0x6B5A44), false)
        val status = (if (building.complete) "완공" else "공사 중") + if (building.detected) " · 외곽 블록으로 자동 인식" else ""
        graphics.text(font, Component.literal(status), tx, top + 58, Ui.argb(0x6B5A44), false)
        graphics.text(font, Component.literal("작업"), left + 14, top + 72, Ui.argb(Ui.INK), false)
        graphics.text(font, Component.literal("용도 지정"), left + 14, top + 116, Ui.argb(Ui.INK), false)
    }
}
