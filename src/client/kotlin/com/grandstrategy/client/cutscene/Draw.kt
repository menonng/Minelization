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

    fun spaced(text: String): String = text.toCharArray().joinToString(" ")

    fun rule(g: GuiGraphicsExtractor, cx: Int, y: Int, halfWidth: Int, rgb: Int, alpha: Double) {
        if (alpha <= 0 || halfWidth <= 0) return
        g.fill(cx - halfWidth, y, cx + halfWidth, y + 1, argb(rgb, alpha))
    }
}
