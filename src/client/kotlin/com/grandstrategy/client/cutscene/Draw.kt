package com.grandstrategy.client.cutscene

import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

object Draw {
    fun argb(rgb: Int, alpha: Double): Int = ((alpha.coerceIn(0.0, 1.0) * 255).roundToInt() shl 24) or (rgb and 0xFFFFFF)

    fun letterbox(g: GuiGraphicsExtractor, amount: Double) {
        if (amount <= 0) return
        val bar = (g.guiHeight() * 0.11 * amount).roundToInt()
        g.fill(0, 0, g.guiWidth(), bar, 0xFF000000.toInt())
        g.fill(0, g.guiHeight() - bar, g.guiWidth(), g.guiHeight(), 0xFF000000.toInt())
    }

    fun shade(g: GuiGraphicsExtractor, rgb: Int, alpha: Double) {
        if (alpha <= 0) return
        g.fill(0, 0, g.guiWidth(), g.guiHeight(), argb(rgb, alpha))
    }

    fun vignette(g: GuiGraphicsExtractor, rgb: Int, alpha: Double) {
        if (alpha <= 0) return
        val w = g.guiWidth()
        val h = g.guiHeight()
        val edge = (h * 0.35).roundToInt()
        val solid = argb(rgb, alpha)
        val clear = argb(rgb, 0.0)
        g.fillGradient(0, 0, w, edge, solid, clear)
        g.fillGradient(0, h - edge, w, h, clear, solid)
    }

    fun centered(g: GuiGraphicsExtractor, font: Font, text: Component, cx: Double, cy: Double, scale: Double, rgb: Int, alpha: Double, shadow: Boolean = true) {
        if (alpha <= 0.02) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(cx.toFloat(), cy.toFloat())
        pose.scale(scale.toFloat(), scale.toFloat())
        val width = font.width(text)
        g.text(font, text, -width / 2, -font.lineHeight / 2, argb(rgb, alpha), shadow)
        pose.popMatrix()
    }

    fun band(g: GuiGraphicsExtractor, top: Double, bottom: Double, alpha: Double) {
        if (alpha <= 0) return
        val mid = ((top + bottom) / 2).roundToInt()
        g.fillGradient(0, top.roundToInt(), g.guiWidth(), mid, argb(0x000000, 0.0), argb(0x000000, alpha))
        g.fillGradient(0, mid, g.guiWidth(), bottom.roundToInt(), argb(0x000000, alpha), argb(0x000000, 0.0))
    }

    fun plaque(g: GuiGraphicsExtractor, cx: Double, cy: Double, w: Double, h: Double, parchment: Boolean, alpha: Double) {
        if (alpha <= 0.02 || w < 16) return
        val a = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt()
        val x = (cx - w / 2).roundToInt()
        val y = (cy - h / 2).roundToInt()
        com.grandstrategy.client.ui.Ui.nine(g, if (parchment) com.grandstrategy.client.ui.Ui.PANEL_PARCHMENT else com.grandstrategy.client.ui.Ui.PANEL_DARK,
            x, y, w.roundToInt(), h.roundToInt(), 32, 32, 7, color = (a shl 24) or 0xFFFFFF)
    }

    fun ribbon(g: GuiGraphicsExtractor, font: Font, cx: Double, cy: Double, w: Double, rgb: Int, text: String, alpha: Double) {
        if (alpha <= 0.02) return
        val a = (alpha.coerceIn(0.0, 1.0) * 255).roundToInt()
        val x = (cx - w / 2).roundToInt()
        com.grandstrategy.client.ui.Ui.nine(g, com.grandstrategy.client.ui.Ui.RIBBON, x, (cy - 8).roundToInt(), w.roundToInt(), 16, 64, 16, 8, color = (a shl 24) or (rgb and 0xFFFFFF))
        val tw = font.width(text)
        g.text(font, Component.literal(text), (cx - tw / 2).roundToInt(), (cy - 4).roundToInt(), argb(0xFFF4D8, alpha), true)
    }

    fun icon(g: GuiGraphicsExtractor, stack: net.minecraft.world.item.ItemStack, cx: Double, cy: Double, scale: Double, alpha: Double) {
        if (alpha <= 0.3) return
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate((cx - 8 * scale).toFloat(), (cy - 8 * scale).toFloat())
        pose.scale(scale.toFloat(), scale.toFloat())
        g.item(stack, 0, 0)
        pose.popMatrix()
    }

    fun ink(rgb: Int): Int {
        val r = (rgb shr 16 and 0xFF) * 6 / 10
        val gg = (rgb shr 8 and 0xFF) * 6 / 10
        val b = (rgb and 0xFF) * 6 / 10
        return (r shl 16) or (gg shl 8) or b
    }

    fun spaced(text: String): String = text.toCharArray().joinToString(" ")

    fun rule(g: GuiGraphicsExtractor, cx: Int, y: Int, halfWidth: Int, rgb: Int, alpha: Double) {
        if (alpha <= 0 || halfWidth <= 0) return
        g.fill(cx - halfWidth, y, cx + halfWidth, y + 1, argb(rgb, alpha))
    }
}
