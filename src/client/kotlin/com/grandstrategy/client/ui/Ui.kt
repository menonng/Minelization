package com.grandstrategy.client.ui

import com.grandstrategy.sim.BuildingKind
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractButton
import net.minecraft.client.gui.narration.NarrationElementOutput
import net.minecraft.client.input.InputWithModifiers
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

object Ui {
    private fun tex(name: String) = Identifier.fromNamespaceAndPath("grandstrategy", "textures/gui/$name.png")
    val PANEL_DARK = tex("panel_dark")
    val PANEL_PARCHMENT = tex("panel_parchment")
    val BUTTON = tex("button")
    val RIBBON = tex("ribbon")
    val BAR = tex("bar")
    val SLOT = tex("slot")

    const val GOLD = 0xE8C252
    const val INK = 0x3A2A1A
    const val TEXT = 0xF2EEE6
    const val MUTED = 0xA8A39A

    fun argb(rgb: Int, alpha: Int = 255) = (alpha shl 24) or (rgb and 0xFFFFFF)

    fun nine(g: GuiGraphicsExtractor, id: Identifier, x: Int, y: Int, w: Int, h: Int, texW: Int, texH: Int, border: Int, v0: Int = 0, sliceH: Int = texH, color: Int = -1) {
        val cw = texW - 2 * border
        val ch = sliceH - 2 * border
        val iw = w - 2 * border
        val ih = h - 2 * border
        fun part(dx: Int, dy: Int, dw: Int, dh: Int, u: Int, v: Int, uw: Int, vh: Int) {
            if (dw <= 0 || dh <= 0) return
            g.blit(RenderPipelines.GUI_TEXTURED, id, dx, dy, u.toFloat(), v.toFloat(), dw, dh, uw, vh, texW, texH, color)
        }
        part(x, y, border, border, 0, v0, border, border)
        part(x + border, y, iw, border, border, v0, cw, border)
        part(x + w - border, y, border, border, texW - border, v0, border, border)
        part(x, y + border, border, ih, 0, v0 + border, border, ch)
        part(x + border, y + border, iw, ih, border, v0 + border, cw, ch)
        part(x + w - border, y + border, border, ih, texW - border, v0 + border, border, ch)
        part(x, y + h - border, border, border, 0, v0 + sliceH - border, border, border)
        part(x + border, y + h - border, iw, border, border, v0 + sliceH - border, cw, border)
        part(x + w - border, y + h - border, border, border, texW - border, v0 + sliceH - border, border, border)
    }

    fun panel(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, parchment: Boolean = false) =
        nine(g, if (parchment) PANEL_PARCHMENT else PANEL_DARK, x, y, w, h, 32, 32, 7)

    fun ribbon(g: GuiGraphicsExtractor, font: Font, x: Int, y: Int, w: Int, rgb: Int, text: Component, icon: ItemStack? = null) {
        nine(g, RIBBON, x, y, w, 16, 64, 16, 8, color = argb(rgb))
        val tx = if (icon != null) x + 24 else x + 10
        if (icon != null) g.item(icon, x + 6, y)
        g.text(font, text, tx, y + 4, argb(if (luma(rgb) > 150) 0x1A1A1A else 0xFFFFFF), luma(rgb) <= 150)
    }

    fun luma(rgb: Int): Int = ((rgb shr 16 and 0xFF) * 299 + (rgb shr 8 and 0xFF) * 587 + (rgb and 0xFF) * 114) / 1000

    fun bar(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, fraction: Double, rgb: Int) {
        nine(g, BAR, x, y, w, 8, 64, 8, 2)
        val fill = ((w - 4) * fraction.coerceIn(0.0, 1.0)).toInt()
        if (fill > 0) {
            g.fillGradient(x + 2, y + 2, x + 2 + fill, y + 6, argb(lighten(rgb, 40)), argb(lighten(rgb, -40)))
            g.fill(x + 2, y + 2, x + 2 + fill, y + 3, argb(lighten(rgb, 80), 160))
        }
    }

    fun lighten(rgb: Int, d: Int): Int {
        val r = ((rgb shr 16 and 0xFF) + d).coerceIn(0, 255)
        val gg = ((rgb shr 8 and 0xFF) + d).coerceIn(0, 255)
        val b = ((rgb and 0xFF) + d).coerceIn(0, 255)
        return (r shl 16) or (gg shl 8) or b
    }

    fun slot(g: GuiGraphicsExtractor, x: Int, y: Int, item: Item?, highlight: Boolean = false) {
        g.blit(RenderPipelines.GUI_TEXTURED, SLOT, x, y, 0f, 0f, 18, 18, 18, 18)
        if (highlight) {
            g.fill(x - 1, y - 1, x + 19, y, argb(GOLD))
            g.fill(x - 1, y + 18, x + 19, y + 19, argb(GOLD))
            g.fill(x - 1, y, x, y + 18, argb(GOLD))
            g.fill(x + 18, y, x + 19, y + 18, argb(GOLD))
            g.fill(x + 1, y + 1, x + 17, y + 17, argb(GOLD, 70))
        }
        if (item != null) g.item(ItemStack(item), x + 1, y + 1)
    }

    fun kindIcon(kind: BuildingKind?): Item = when (kind) {
        BuildingKind.HOUSE -> Items.OAK_DOOR
        BuildingKind.FARM -> Items.WHEAT
        BuildingKind.LUMBER_CAMP -> Items.IRON_AXE
        BuildingKind.QUARRY -> Items.STONECUTTER
        BuildingKind.MINE -> Items.IRON_PICKAXE
        BuildingKind.MARKET -> Items.EMERALD
        BuildingKind.LIBRARY -> Items.BOOKSHELF
        BuildingKind.BARRACKS -> Items.IRON_SWORD
        BuildingKind.WALLS -> Items.STONE_BRICK_WALL
        BuildingKind.TEMPLE -> Items.BELL
        BuildingKind.OTHER, null -> Items.CHEST
    }

    fun resourceIcon(label: String): Item = when (label) {
        "식량" -> Items.WHEAT
        "목재" -> Items.OAK_LOG
        "석재" -> Items.COBBLESTONE
        "철" -> Items.IRON_INGOT
        "금" -> Items.GOLD_INGOT
        else -> Items.PAPER
    }
}

class IconButton(
    x: Int, y: Int, w: Int, h: Int,
    private val label: Component,
    private val icon: Item?,
    private val selected: () -> Boolean = { false },
    private val action: () -> Unit
) : AbstractButton(x, y, w, h, label) {
    override fun onPress(input: InputWithModifiers) = action()

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val state = when {
            !active -> 2
            isHoveredOrFocused || selected() -> 1
            else -> 0
        }
        Ui.nine(g, Ui.BUTTON, x, y, width, height, 20, 60, 3, v0 = state * 20, sliceH = 20)
        val font = Minecraft.getInstance().font
        var tx = x + 6
        if (icon != null) {
            g.item(ItemStack(icon), x + 3, y + (height - 16) / 2)
            tx = x + 22
        }
        val color = if (state == 1) 0x2A1E08 else Ui.TEXT
        g.text(font, label, tx, y + (height - 8) / 2, Ui.argb(color), state != 1)
    }

    override fun updateWidgetNarration(output: NarrationElementOutput) {
        defaultButtonNarrationText(output)
    }
}
