package com.grandstrategy.client.ui

import com.grandstrategy.net.SetupOffer
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class SetupScreen(private val offer: SetupOffer) : Screen(Component.literal("세계 준비")) {
    private var chosen = offer.tiers.indexOfFirst { it.size == offer.current }.let { if (it < 0) 2 else it }
    private var pregen = offer.firstTime
    private var god = offer.god
    private val panelW get() = minOf(400, width - 12)
    private val panelH get() = minOf(230, height - 12)
    private val left get() = (width - panelW) / 2
    private val top get() = (height - panelH) / 2
    private val cardW get() = (panelW - 28) / 5
    private val cardY get() = top + 52

    private val icons: List<Item> = listOf(Items.MAP, Items.FILLED_MAP, Items.COMPASS, Items.RECOVERY_COMPASS, Items.SPYGLASS)

    override fun isPauseScreen(): Boolean = false

    override fun init() {
        val y = top + panelH - 58
        val pregenTip = if (offer.chunky) "Chunky로 테두리 안 지형을 미리 만든다" else "내장 생성기로 미리 만든다 (Chunky를 함께 설치하면 더 빠르다)"
        val p = IconButton(left + 14, y, 180, 20, Component.literal(if (pregen) "지형 미리 생성: 켬" else "지형 미리 생성: 끔"), Items.GRASS_BLOCK, { pregen }) {
            pregen = !pregen
            rebuildWidgets()
        }
        p.setTooltip(Tooltip.create(Component.literal(pregenTip)))
        addRenderableWidget(p)
        val g = IconButton(left + 200, y, 186, 20, Component.literal(if (god) "조물주 모드: 켬" else "조물주 모드: 끔"), Items.TOTEM_OF_UNDYING, { god }) {
            god = !god
            rebuildWidgets()
        }
        g.setTooltip(Tooltip.create(Component.literal("켜면 플레이어는 관전자로 시작하고 (모험 모드로 바꿀 수 있다) 피해를 입지 않으며 몹이 노리지 않는다")))
        addRenderableWidget(g)
        addRenderableWidget(IconButton(left + panelW / 2 - 70, top + panelH - 32, 140, 22, Component.literal(if (offer.firstTime) "세계 시작" else "적용"), Items.BEACON) {
            val flags = listOfNotNull(if (pregen) "pregen" else null, if (god) "god" else null).joinToString(",")
            NationMenu.act("setup", offer.tiers[chosen].size, flags)
            onClose()
        })
        if (!offer.firstTime) addRenderableWidget(IconButton(left + panelW - 30, top + 6, 22, 18, Component.literal(""), Items.BARRIER) { onClose() })
    }

    override fun shouldCloseOnEsc(): Boolean = !offer.firstTime

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        offer.tiers.indices.forEach { k ->
            val x = left + 14 + k * cardW
            if (event.x() >= x && event.x() < x + cardW - 4 && event.y() >= cardY && event.y() < cardY + 96) {
                chosen = k
                return true
            }
        }
        return false
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fillGradient(0, 0, width, height, Ui.argb(0x000000, 110), Ui.argb(0x000000, 190))
        Ui.panel(g, left, top, panelW, panelH, parchment = true)
        Ui.ribbon(g, font, left + 10, top + 8, panelW - 46, 0x3C7A3C,
            Component.literal(if (offer.firstTime) "새 세계 준비 · 크기를 고른다" else "세계 설정"), ItemStack(Items.FILLED_MAP))
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        g.text(font, Component.literal("월드 경계 크기와 처음 생기는 나라 수가 정해진다"), left + 14, top + 34, Ui.argb(0x5A3A16), false)
        offer.tiers.forEachIndexed { k, t ->
            val x = left + 14 + k * cardW
            val w = cardW - 4
            val sel = k == chosen
            val hover = mouseX in x until x + w && mouseY in cardY until cardY + 96
            g.fill(x, cardY, x + w, cardY + 96, Ui.argb(if (sel) 0xE8C252 else 0x000000, if (sel) 110 else if (hover) 50 else 25))
            if (sel) {
                g.fill(x, cardY, x + w, cardY + 2, Ui.argb(0xB08A2A))
                g.fill(x, cardY + 94, x + w, cardY + 96, Ui.argb(0xB08A2A))
            }
            val pose = g.pose()
            pose.pushMatrix()
            pose.translate((x + w / 2 - 16).toFloat(), (cardY + 6).toFloat())
            pose.scale(2f, 2f)
            g.item(ItemStack(icons[k]), 0, 0)
            pose.popMatrix()
            fun center(text: String, y: Int, color: Int) = g.text(font, Component.literal(text), x + (w - font.width(text)) / 2, y, Ui.argb(color), false)
            center("${t.size}", cardY + 42, 0x3A2A1A)
            center(t.label, cardY + 53, 0x6B5A44)
            center("나라 ${t.nations}개", cardY + 66, 0x2E5A2E)
            val minutes = t.chunks / (if (offer.chunky) 90.0 else 40.0) / 60
            center("${"%.1f".format(t.chunks / 1000.0)}k 청크", cardY + 77, 0x7A6A54)
            center(if (minutes < 1) "생성 1분 미만" else "생성 약 ${minutes.toInt()}분", cardY + 86, 0x7A6A54)
        }
        val t = offer.tiers[chosen]
        val note = "선택: ${t.size}×${t.size} (${t.label}) · ${if (offer.chunky) "Chunky 감지됨" else "Chunky 없음 → 내장 생성기"}"
        g.text(font, Component.literal(note), left + 14, top + panelH - 72, Ui.argb(0x5A3A16), false)
    }
}
