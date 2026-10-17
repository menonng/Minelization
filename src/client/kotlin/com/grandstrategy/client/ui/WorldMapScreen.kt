package com.grandstrategy.client.ui

import com.grandstrategy.client.work.WorkMode
import com.grandstrategy.net.MapInfo
import com.grandstrategy.net.MapOpen
import com.grandstrategy.net.MapRequest
import com.grandstrategy.net.MapTiles
import com.grandstrategy.sim.Chunks
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.material.MapColor
import java.util.Base64
import kotlin.math.floor
import kotlin.math.roundToInt

object WorldMap {
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("grandstrategy", "map"))
    private val openKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.world_map", InputConstants.KEY_M, category))

    /** [version] is what the texture shows, [latest] what the server last reported; they differ while pixels are still owed. */
    class ClientTile(val key: Long, var version: Int, var latest: Int, var texture: Identifier?, var owners: IntArray, var flags: IntArray)

    const val MIN_ZOOM = 1.0 / 32
    const val MAX_ZOOM = 8.0

    val tiles = HashMap<Long, ClientTile>()
    var info: MapInfo? = null
    var centerX = 0.0
    var centerZ = 0.0
    var zoom = 1.0
    var centered = false
    var fitted = false

    fun init() {}

    fun tick(mc: Minecraft) {
        while (openKey.consumeClick()) if (mc.gui.screen() == null) open()
    }

    fun open() {
        val mc = Minecraft.getInstance()
        if (!centered) {
            mc.player?.let {
                centerX = it.x
                centerZ = it.z
            }
            centered = true
        }
        mc.gui.setScreen(WorldMapScreen())
        WorkMode.send(MapOpen(true))
    }

    fun onInfo(message: MapInfo) {
        info = message
        val mc = Minecraft.getInstance()
        if (!fitted && message.borderSize in 1.0..100000.0) {
            fitted = true
            centered = true
            centerX = message.borderX
            centerZ = message.borderZ
            val w = mc.window.guiScaledWidth - 16.0
            val h = mc.window.guiScaledHeight - 50.0
            val fit = minOf(w, h) / message.borderSize
            var z = MIN_ZOOM
            while (z * 2 <= fit && z < MAX_ZOOM) z *= 2
            zoom = z
        }
        if (message.open && mc.gui.screen() !is WorldMapScreen) {
            if (!centered) {
                mc.player?.let {
                    centerX = it.x
                    centerZ = it.z
                }
                centered = true
            }
            mc.gui.setScreen(WorldMapScreen())
        }
    }

    fun onTiles(message: MapTiles) {
        val mc = Minecraft.getInstance()
        for (data in message.tiles) {
            val tile = tiles.getOrPut(data.key) { ClientTile(data.key, 0, 0, null, IntArray(64), IntArray(64)) }
            tile.owners = data.owners.toIntArray()
            tile.flags = data.flags.toIntArray()
            tile.latest = data.version
            val pixels = data.pixels ?: continue
            val bytes = Base64.getDecoder().decode(pixels)
            val image = NativeImage(128, 128, false)
            for (i in 0 until 128 * 128) {
                val packed = bytes[i].toInt() and 0xFF
                image.setPixel(i % 128, i / 128, if (packed shr 2 == 0) 0xFF141210.toInt() else MapColor.getColorFromPackedId(packed))
            }
            val id = Identifier.fromNamespaceAndPath("grandstrategy", "map/${Chunks.x(data.key)}_${Chunks.z(data.key)}".replace('-', 'm'))
            if (tile.texture != null) mc.textureManager.release(tile.texture!!)
            val texture = DynamicTexture({ "grandstrategy map tile" }, image)
            mc.textureManager.register(id, texture)
            texture.upload()
            tile.texture = id
            tile.version = data.version
        }
    }

    fun clear() {
        val mc = Minecraft.getInstance()
        for (t in tiles.values) t.texture?.let { mc.textureManager.release(it) }
        tiles.clear()
        info = null
        centered = false
        fitted = false
    }
}

class WorldMapScreen : Screen(Component.literal("세계 지도")) {
    private var dragging = false
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastRequest = 0L
    private var refreshCursor = 0
    private var showBorders = true
    private var showLabels = true
    private val mapLeft get() = 8
    private val mapTop get() = 30
    private val mapRight get() = width - 8
    private val mapBottom get() = height - 20

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        WorkMode.send(MapOpen(false))
        super.onClose()
    }

    override fun init() {
        var x = width - 30
        addRenderableWidget(IconButton(x, 6, 22, 18, Component.literal(""), Items.BARRIER) { onClose() })
        x -= 26
        tool(x, Items.COMPASS, "내 위치로") {
            Minecraft.getInstance().player?.let {
                WorldMap.centerX = it.x
                WorldMap.centerZ = it.z
            }
        }
        x -= 26
        tool(x, Items.SPYGLASS, "확대") { zoomAt(2.0, (mapLeft + mapRight) / 2.0, (mapTop + mapBottom) / 2.0) }
        x -= 26
        tool(x, Items.MAP, "축소") { zoomAt(0.5, (mapLeft + mapRight) / 2.0, (mapTop + mapBottom) / 2.0) }
        x -= 26
        val labels = IconButton(x, 6, 22, 18, Component.literal(""), Items.NAME_TAG, { showLabels }) { showLabels = !showLabels }
        labels.setTooltip(Tooltip.create(Component.literal("나라 이름 표시")))
        addRenderableWidget(labels)
        x -= 26
        val borders = IconButton(x, 6, 22, 18, Component.literal(""), Items.BANNER.white(), { showBorders }) { showBorders = !showBorders }
        borders.setTooltip(Tooltip.create(Component.literal("국경 표시")))
        addRenderableWidget(borders)
    }

    private fun tool(x: Int, item: net.minecraft.world.item.Item, tip: String, action: () -> Unit) {
        val b = IconButton(x, 6, 22, 18, Component.literal(""), item, action = action)
        b.setTooltip(Tooltip.create(Component.literal(tip)))
        addRenderableWidget(b)
    }

    private fun zoomAt(factor: Double, sx: Double, sy: Double) {
        val (bx, bz) = toWorld(sx, sy)
        WorldMap.zoom = (WorldMap.zoom * factor).coerceIn(WorldMap.MIN_ZOOM, WorldMap.MAX_ZOOM)
        val (nx, nz) = toWorld(sx, sy)
        WorldMap.centerX += bx - nx
        WorldMap.centerZ += bz - nz
    }

    private fun toWorld(sx: Double, sy: Double): Pair<Double, Double> {
        val cx = (mapLeft + mapRight) / 2.0
        val cy = (mapTop + mapBottom) / 2.0
        return (WorldMap.centerX + (sx - cx) / WorldMap.zoom) to (WorldMap.centerZ + (sy - cy) / WorldMap.zoom)
    }

    private fun toScreen(wx: Double, wz: Double): Pair<Double, Double> {
        val cx = (mapLeft + mapRight) / 2.0
        val cy = (mapTop + mapBottom) / 2.0
        return (cx + (wx - WorldMap.centerX) * WorldMap.zoom) to (cy + (wz - WorldMap.centerZ) * WorldMap.zoom)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (event.y() > mapTop && event.y() < mapBottom) {
            dragging = true
            lastX = event.x()
            lastY = event.y()
            return true
        }
        return false
    }

    override fun mouseDragged(event: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (dragging) {
            WorldMap.centerX -= (event.x() - lastX) / WorldMap.zoom
            WorldMap.centerZ -= (event.y() - lastY) / WorldMap.zoom
            lastX = event.x()
            lastY = event.y()
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        dragging = false
        return super.mouseReleased(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (scrollY != 0.0) zoomAt(if (scrollY > 0) 2.0 else 0.5, mouseX, mouseY)
        return true
    }

    /** Visible tile range clipped to the world border (plus one tile), or null when nothing is on screen. */
    private fun visibleTiles(): IntArray? {
        val (minX, minZ) = toWorld(mapLeft.toDouble(), mapTop.toDouble())
        val (maxX, maxZ) = toWorld(mapRight.toDouble(), mapBottom.toDouble())
        var tx0 = floor(minX / 128).toInt()
        var tx1 = floor(maxX / 128).toInt()
        var tz0 = floor(minZ / 128).toInt()
        var tz1 = floor(maxZ / 128).toInt()
        WorldMap.info?.let { b ->
            val half = b.borderSize / 2
            tx0 = maxOf(tx0, floor((b.borderX - half) / 128).toInt() - 1)
            tx1 = minOf(tx1, floor((b.borderX + half) / 128).toInt() + 1)
            tz0 = maxOf(tz0, floor((b.borderZ - half) / 128).toInt() - 1)
            tz1 = minOf(tz1, floor((b.borderZ + half) / 128).toInt() + 1)
        }
        return if (tx0 > tx1 || tz0 > tz1) null else intArrayOf(tx0, tz0, tx1, tz1)
    }

    override fun tick() {
        val now = System.currentTimeMillis()
        val (tx0, tz0, tx1, tz1) = (visibleTiles() ?: return).toList()
        val center = toWorld((mapLeft + mapRight) / 2.0, (mapTop + mapBottom) / 2.0)
        val ctx = center.first / 128 - 0.5
        val ctz = center.second / 128 - 0.5
        val stale = ArrayList<Long>()
        val known = ArrayList<Long>()
        for (tz in tz0..tz1) for (tx in tx0..tx1) {
            val key = Chunks.pack(tx, tz)
            val t = WorldMap.tiles[key]
            if (t == null || t.latest != t.version) stale += key else known += key
        }
        // Missing tiles stream in quickly, nearest the view center first; known ones are re-polled slowly
        // in a rotating window so new chunks, owners and borders show up without refetching everything.
        if (now - lastRequest < (if (stale.isNotEmpty()) 250 else 1500)) return
        lastRequest = now
        stale.sortBy {
            val dx = Chunks.x(it) - ctx
            val dz = Chunks.z(it) - ctz
            dx * dx + dz * dz
        }
        val keys = ArrayList<Long>(stale.take(48))
        if (keys.size < 48 && known.isNotEmpty()) {
            val n = minOf(48 - keys.size, known.size)
            for (k in 0 until n) keys += known[(refreshCursor + k) % known.size]
            refreshCursor = (refreshCursor + n) % known.size
        }
        if (keys.isEmpty()) return
        WorkMode.send(MapRequest(keys, keys.map { WorldMap.tiles[it]?.version ?: -1 }))
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, Ui.argb(0x120E0A))
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.enableScissor(mapLeft, mapTop, mapRight, mapBottom)
        g.fill(mapLeft, mapTop, mapRight, mapBottom, Ui.argb(0x1A1612))
        drawTiles(g)
        if (showBorders) drawTerritory(g)
        drawBorder(g)
        drawMarkers(g)
        if (showLabels) drawLabels(g)
        drawPlayers(g)
        g.disableScissor()
        frame(g)
        Ui.ribbon(g, font, 8, 6, minOf(320, width - 180), 0x3C6A8A, Component.literal("세계 지도 · 확대 ${zoomLabel()}"), ItemStack(Items.FILLED_MAP))
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        footer(g, mouseX, mouseY)
    }

    private fun frame(g: GuiGraphicsExtractor) {
        val gold = Ui.argb(0xB08A4A)
        val dark = Ui.argb(0x3A2A1A)
        g.fill(mapLeft - 3, mapTop - 3, mapRight + 3, mapTop - 1, dark)
        g.fill(mapLeft - 3, mapBottom + 1, mapRight + 3, mapBottom + 3, dark)
        g.fill(mapLeft - 3, mapTop - 3, mapLeft - 1, mapBottom + 3, dark)
        g.fill(mapRight + 1, mapTop - 3, mapRight + 3, mapBottom + 3, dark)
        g.fill(mapLeft - 1, mapTop - 1, mapRight + 1, mapTop, gold)
        g.fill(mapLeft - 1, mapBottom, mapRight + 1, mapBottom + 1, gold)
        g.fill(mapLeft - 1, mapTop - 1, mapLeft, mapBottom + 1, gold)
        g.fill(mapRight, mapTop - 1, mapRight + 1, mapBottom + 1, gold)
    }

    private fun zoomLabel(): String = if (WorldMap.zoom >= 1) "×${WorldMap.zoom.roundToInt()}" else "1/${(1 / WorldMap.zoom).roundToInt()}"

    private fun drawTiles(g: GuiGraphicsExtractor) {
        val (tx0, tz0, tx1, tz1) = (visibleTiles() ?: return).toList()
        val size = (128 * WorldMap.zoom)
        for (tz in tz0..tz1) for (tx in tx0..tx1) {
            val tile = WorldMap.tiles[Chunks.pack(tx, tz)] ?: continue
            val tex = tile.texture ?: continue
            val (sx, sy) = toScreen(tx * 128.0, tz * 128.0)
            val x0 = sx.roundToInt()
            val y0 = sy.roundToInt()
            val x1 = (sx + size).roundToInt()
            val y1 = (sy + size).roundToInt()
            g.blit(RenderPipelines.GUI_TEXTURED, tex, x0, y0, 0f, 0f, x1 - x0, y1 - y0, 128, 128, 128, 128)
        }
    }

    private fun ownerAt(chunk: Long): Int {
        val tile = WorldMap.tiles[Chunks.pack(Chunks.x(chunk) shr 3, Chunks.z(chunk) shr 3)] ?: return -1
        return tile.owners[(Chunks.z(chunk) and 7) * 8 + (Chunks.x(chunk) and 7)]
    }

    private fun drawTerritory(g: GuiGraphicsExtractor) {
        val nations = WorldMap.info?.nations?.associateBy { it.id } ?: return
        val (tx0, tz0, tx1, tz1) = (visibleTiles() ?: return).toList()
        val (minX, minZ) = toWorld(mapLeft.toDouble(), mapTop.toDouble())
        val (maxX, maxZ) = toWorld(mapRight.toDouble(), mapBottom.toDouble())
        val cx0 = maxOf(floor(minX / 16).toInt(), tx0 * 8)
        val cx1 = minOf(floor(maxX / 16).toInt(), tx1 * 8 + 7)
        val cz0 = maxOf(floor(minZ / 16).toInt(), tz0 * 8)
        val cz1 = minOf(floor(maxZ / 16).toInt(), tz1 * 8 + 7)
        val c = 16 * WorldMap.zoom
        val w = if (WorldMap.zoom >= 1) 2 else 1
        val edges = c >= 1.5
        for (cz in cz0..cz1) {
            val rowY = toScreen(0.0, cz * 16.0).second
            val y0 = rowY.roundToInt()
            val y1 = maxOf((rowY + c).roundToInt(), y0 + 1)
            var cx = cx0
            while (cx <= cx1) {
                val owner = ownerAt(Chunks.pack(cx, cz))
                val color = if (owner > 0) nations[owner]?.color else null
                if (color == null) {
                    cx++
                    continue
                }
                // One fill per run of same-owner chunks keeps the draw count small when zoomed out.
                var end = cx
                while (end + 1 <= cx1 && ownerAt(Chunks.pack(end + 1, cz)) == owner) end++
                val sx = toScreen(cx * 16.0, 0.0).first
                val x0 = sx.roundToInt()
                val x1 = maxOf((sx + c * (end - cx + 1)).roundToInt(), x0 + 1)
                g.fill(x0, y0, x1, y1, Ui.argb(color, 70))
                if (edges) {
                    val edge = Ui.argb(Ui.lighten(color, 40), 230)
                    if (ownerAt(Chunks.pack(cx - 1, cz)) != owner) g.fill(x0, y0, x0 + w, y1, edge)
                    if (ownerAt(Chunks.pack(end + 1, cz)) != owner) g.fill(x1 - w, y0, x1, y1, edge)
                    for (k in cx..end) {
                        val kx = toScreen(k * 16.0, 0.0).first
                        val a = kx.roundToInt()
                        val b = (kx + c).roundToInt()
                        if (ownerAt(Chunks.pack(k, cz - 1)) != owner) g.fill(a, y0, b, y0 + w, edge)
                        if (ownerAt(Chunks.pack(k, cz + 1)) != owner) g.fill(a, y1 - w, b, y1, edge)
                    }
                }
                cx = end + 1
            }
        }
    }

    private fun drawBorder(g: GuiGraphicsExtractor) {
        val info = WorldMap.info ?: return
        val half = info.borderSize / 2
        val (x0, y0) = toScreen(info.borderX - half, info.borderZ - half)
        val (x1, y1) = toScreen(info.borderX + half, info.borderZ + half)
        val c = Ui.argb(0x7AB8FF, 200)
        g.fill(x0.roundToInt(), y0.roundToInt(), x1.roundToInt(), y0.roundToInt() + 1, c)
        g.fill(x0.roundToInt(), y1.roundToInt() - 1, x1.roundToInt(), y1.roundToInt(), c)
        g.fill(x0.roundToInt(), y0.roundToInt(), x0.roundToInt() + 1, y1.roundToInt(), c)
        g.fill(x1.roundToInt() - 1, y0.roundToInt(), x1.roundToInt(), y1.roundToInt(), c)
    }

    private fun icon(g: GuiGraphicsExtractor, stack: ItemStack, sx: Double, sy: Double, scale: Float) {
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate((sx - 8 * scale).toFloat(), (sy - 8 * scale).toFloat())
        pose.scale(scale, scale)
        g.item(stack, 0, 0)
        pose.popMatrix()
    }

    private fun drawMarkers(g: GuiGraphicsExtractor) {
        val info = WorldMap.info ?: return
        val scale = when {
            WorldMap.zoom >= 1 -> 1f
            WorldMap.zoom >= 0.125 -> 0.75f
            else -> 0.55f
        }
        val colors = info.nations.associate { it.id to it.color }
        for (t in info.towns) {
            val (sx, sy) = toScreen(Chunks.x(t.center) * 16 + 8.0, Chunks.z(t.center) * 16 + 8.0)
            if (WorldMap.zoom >= 0.125) {
                icon(g, ItemStack(Items.BELL), sx, sy, scale * 0.8f)
            } else {
                // Zoomed far out, bells would pile on top of each other; a dot in the owner's color reads better.
                val x = sx.roundToInt()
                val y = sy.roundToInt()
                g.fill(x - 2, y - 2, x + 2, y + 2, Ui.argb(0x000000))
                g.fill(x - 1, y - 1, x + 1, y + 1, Ui.argb(colors[t.owner] ?: 0xE0D8C0))
            }
        }
        for (n in info.nations) {
            val (sx, sy) = toScreen(Chunks.x(n.capital) * 16 + 8.0, Chunks.z(n.capital) * 16 + 8.0)
            icon(g, NationMenu.flagOf(n.flag, n.dye), sx, sy, scale)
        }
    }

    private fun drawLabels(g: GuiGraphicsExtractor) {
        val info = WorldMap.info ?: return
        val (tx0, tz0, tx1, tz1) = (visibleTiles() ?: return).toList()
        val sums = HashMap<Int, DoubleArray>()
        val step = when {
            WorldMap.zoom < 0.1 -> 4
            WorldMap.zoom < 0.5 -> 2
            else -> 1
        }
        for (cz in tz0 * 8..tz1 * 8 + 7 step step) for (cx in tx0 * 8..tx1 * 8 + 7 step step) {
            val owner = ownerAt(Chunks.pack(cx, cz))
            if (owner <= 0) continue
            val s = sums.getOrPut(owner) { DoubleArray(3) }
            s[0] += cx * 16 + 8.0
            s[1] += cz * 16 + 8.0
            s[2] += 1.0
        }
        for (n in info.nations) {
            val s = sums[n.id] ?: continue
            if (s[2] * step * step < 3) continue
            val (sx, sy) = toScreen(s[0] / s[2], s[1] / s[2])
            val scale = (0.8 + kotlin.math.sqrt(s[2] * step * step) * WorldMap.zoom * 0.04).coerceIn(0.8, 2.5)
            val pose = g.pose()
            pose.pushMatrix()
            pose.translate(sx.toFloat(), sy.toFloat() + 10)
            pose.scale(scale.toFloat(), scale.toFloat())
            val w = font.width(n.name)
            // A dark plate keeps names readable over snow, sand and the nation's own tint.
            g.fill(-w / 2 - 3, -6, w / 2 + 3, 5, Ui.argb(0x000000, 120))
            g.fill(-w / 2 - 3, 5, w / 2 + 3, 6, Ui.argb(n.color, 200))
            g.text(font, Component.literal(n.name), -w / 2, -4, Ui.argb(Ui.lighten(n.color, 60)), true)
            pose.popMatrix()
        }
    }

    private fun drawPlayers(g: GuiGraphicsExtractor) {
        val info = WorldMap.info ?: return
        val mc = Minecraft.getInstance()
        for (p in info.players) {
            val px = if (p.self) mc.player?.x ?: p.x else p.x
            val pz = if (p.self) mc.player?.z ?: p.z else p.z
            val yaw = if (p.self) mc.player?.yRot ?: p.yaw else p.yaw
            val (sx, sy) = toScreen(px, pz)
            val x = sx.roundToInt()
            val y = sy.roundToInt()
            val color = if (p.self) 0xFFFFFF else 0xFFE060
            g.fill(x - 3, y - 3, x + 4, y + 4, Ui.argb(0x000000))
            g.fill(x - 2, y - 2, x + 3, y + 3, Ui.argb(color))
            val rad = Math.toRadians(yaw.toDouble())
            val dx = -kotlin.math.sin(rad)
            val dz = kotlin.math.cos(rad)
            for (k in 3..8) g.fill((x + dx * k).roundToInt(), (y + dz * k).roundToInt(), (x + dx * k).roundToInt() + 1, (y + dz * k).roundToInt() + 1, Ui.argb(color))
            if (!p.self) g.text(font, Component.literal(p.name), x + 6, y - 4, Ui.argb(color), true)
        }
    }

    private fun footer(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val info = WorldMap.info
        val y = height - 15
        val parts = ArrayList<String>()
        if (mouseX in mapLeft until mapRight && mouseY in mapTop until mapBottom) {
            val (wx, wz) = toWorld(mouseX.toDouble(), mouseY.toDouble())
            val chunk = Chunks.pack(floor(wx / 16).toInt(), floor(wz / 16).toInt())
            parts += "X ${floor(wx).toInt()}  Z ${floor(wz).toInt()}"
            val owner = ownerAt(chunk)
            val name = info?.nations?.firstOrNull { it.id == owner }?.name
            parts += when {
                name != null -> "$name 영토"
                owner == 0 -> "무주지"
                else -> "아직 그려지지 않은 곳"
            }
        }
        if (info != null) parts += "경계 ${info.borderSize.toInt()} · 기록된 청크 ${info.colored}"
        parts += "끌어서 이동 · 휠 확대/축소 · [M] 닫기"
        g.text(font, Component.literal(parts.joinToString("   ")), 10, y, Ui.argb(0xD8D0C0), true)
    }
}

