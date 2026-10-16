package com.grandstrategy.client.work

import com.grandstrategy.net.BuildingView
import com.grandstrategy.net.ToServer
import com.grandstrategy.sim.BuildingKind
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

class BuildingScreen(private val building: BuildingView) : Screen(Component.literal("건물 #${building.id}")) {
    override fun init() {
        val cx = width / 2
        var y = height / 2 - 46
        addRenderableWidget(Button.builder(Component.literal("철거")) {
            WorkMode.send(ToServer.BuildingAction(building.id, "demolish"))
            onClose()
        }.bounds(cx - 102, y, 100, 20).build())
        addRenderableWidget(Button.builder(Component.literal("철거 (징용)")) {
            WorkMode.send(ToServer.BuildingAction(building.id, "demolish", conscript = true))
            onClose()
        }.bounds(cx + 2, y, 100, 20).build())
        y += 40
        val kinds = BuildingKind.entries.filter { it != BuildingKind.WALLS }
        kinds.forEachIndexed { k, kind ->
            val col = k % 3
            val row = k / 3
            addRenderableWidget(Button.builder(Component.literal(kind.label)) {
                WorkMode.send(ToServer.BuildingAction(building.id, "assign", kind))
                onClose()
            }.bounds(cx - 153 + col * 102, y + row * 24, 100, 20).build())
        }
        addRenderableWidget(Button.builder(Component.literal("닫기")) { onClose() }
            .bounds(cx - 50, y + ((kinds.size + 2) / 3) * 24 + 8, 100, 20).build())
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        WorkMode.selected = null
        super.onClose()
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val b = building.box
        val owner = building.owner.ifEmpty { "주인 없음" }
        graphics.centeredText(font, Component.literal("${building.kind.label} #${building.id} · $owner"), width / 2, height / 2 - 80, 0xFFFFE040.toInt())
        graphics.centeredText(font, Component.literal("${b.sizeX}×${b.sizeY}×${b.sizeZ} · (${b.minX}, ${b.minY}, ${b.minZ}) ~ (${b.maxX}, ${b.maxY}, ${b.maxZ})${if (building.detected) " · 자동 인식" else ""}"),
            width / 2, height / 2 - 66, 0xFFDDDDDD.toInt())
        graphics.centeredText(font, Component.literal("용도 지정"), width / 2, height / 2 - 18, 0xFFAAAAAA.toInt())
    }
}
