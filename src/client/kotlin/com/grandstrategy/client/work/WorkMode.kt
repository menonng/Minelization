package com.grandstrategy.client.work

import com.grandstrategy.net.BuildingView
import com.grandstrategy.net.ClientMessagePayload
import com.grandstrategy.net.TemplateView
import com.grandstrategy.net.ToClient
import com.grandstrategy.net.ToServer
import com.grandstrategy.net.WorkJson
import com.grandstrategy.net.WorkTool
import com.grandstrategy.sim.Box
import com.grandstrategy.sim.BuildingKind
import com.grandstrategy.sim.Chunks
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3

object WorkMode {
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("grandstrategy", "work"))
    private val toggleKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.work_mode", InputConstants.KEY_B, category))
    private val toolKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.tool", InputConstants.KEY_C, category))
    private val prevKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.prev", InputConstants.KEY_Z, category))
    private val nextKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.next", InputConstants.KEY_X, category))
    private val rotateKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.rotate", InputConstants.KEY_R, category))
    private val conscriptKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.conscript", InputConstants.KEY_V, category))
    private val confirmKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.confirm", InputConstants.KEY_RETURN, category))
    private val clearKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.clear", InputConstants.KEY_BACKSPACE, category))

    fun init() {}

    var active = false
        private set
    var tool = WorkTool.SELECT
        private set
    private var templateIndex = 0
    private var rotation = 0
    private var conscript = false
    private var dragStart: BlockPos? = null
    private var dragEnd: BlockPos? = null
    private var selection: Box? = null
    private var hover: BlockPos? = null
    private var wasDown = false
    private var useWasDown = false
    private var info: ToClient.Info? = null
    private var templates: List<TemplateView> = emptyList()
    var selected: BuildingView? = null
    private var lastMessage = ""
    private var lastMessageOk = true
    private var lastMessageAt = 0L
    private var ticks = 0L

    fun swallowAttack(): Boolean = active

    fun onMessage(message: ToClient) {
        when (message) {
            is ToClient.Info -> info = message
            is ToClient.Templates -> templates = message.list
            is ToClient.Selected -> {
                val building = message.building
                selected = building
                if (building != null) Minecraft.getInstance().gui.setScreen(BuildingScreen(building))
                else toast(message.message, false)
            }
            is ToClient.Result -> {
                toast(message.message, message.ok)
                if (message.ok) selection = null
            }
            else -> {}
        }
    }

    private fun toast(text: String, ok: Boolean) {
        lastMessage = text
        lastMessageOk = ok
        lastMessageAt = ticks
    }

    fun send(message: ToServer) {
        ClientPlayNetworking.send(ClientMessagePayload(WorkJson.json.encodeToString(ToServer.serializer(), message)))
    }

    private val autoWork = System.getProperty("grandstrategy.capture") == "true"

    fun tick(mc: Minecraft) {
        ticks++
        val player = mc.player ?: return
        if (autoWork && !active && player.isSpectator && ticks % 40 == 0L) setActive(true)
        while (toggleKey.consumeClick()) setActive(!active)
        if (active && !player.isSpectator) {
            toast("작업 지정은 관전자 모드에서만 쓸 수 있다", false)
            setActive(false)
        }
        if (!active || mc.gui.screen() != null) return
        while (toolKey.consumeClick()) {
            tool = WorkTool.entries[(tool.ordinal + 1) % WorkTool.entries.size]
            selection = null
        }
        while (nextKey.consumeClick()) if (templates.isNotEmpty()) templateIndex = (templateIndex + 1) % templates.size
        while (prevKey.consumeClick()) if (templates.isNotEmpty()) templateIndex = (templateIndex - 1 + templates.size) % templates.size
        while (rotateKey.consumeClick()) rotation = (rotation + 1) % 4
        while (conscriptKey.consumeClick()) conscript = !conscript
        while (clearKey.consumeClick()) selection = null
        hover = raycast(mc)
        val down = mc.options.keyAttack.isDown
        if (down && !wasDown) {
            dragStart = hover
            dragEnd = hover
        } else if (down) {
            hover?.let { dragEnd = it }
        } else if (wasDown) {
            val a = dragStart
            val b = dragEnd
            if (a != null && b != null) {
                if (tool == WorkTool.SELECT) {
                    send(ToServer.Select(b.x, b.y, b.z))
                } else {
                    selection = Box.of(a.x, a.y, a.z, b.x, b.y, b.z)
                }
            }
            dragStart = null
            dragEnd = null
        }
        wasDown = down
        val use = mc.options.keyUse.isDown
        val confirm = (use && !useWasDown) || confirmKey.consumeClick()
        useWasDown = use
        if (confirm) confirmSelection()
        if (ticks % 5 == 0L) drawOutlines(mc)
    }

    private fun setActive(on: Boolean) {
        if (active == on) return
        active = on
        selection = null
        dragStart = null
        send(ToServer.Mode(on))
    }

    fun blockVanillaInput(mc: Minecraft) {
        if (!active) return
        for (key in mc.options.keyHotbarSlots) while (key.consumeClick()) {}
        while (mc.options.keyPickItem.consumeClick()) {}
        while (mc.options.keySwapOffhand.consumeClick()) {}
    }

    private fun confirmSelection() {
        val box = selection ?: return
        problem(box)?.let {
            toast(it, false)
            return
        }
        val area = Box(box.minX, box.minY, box.minZ, box.maxX, if (tool == WorkTool.DEMOLISH) box.maxY + 12 else box.maxY, box.maxZ)
        send(ToServer.Order(tool, area, templateIndex, rotation, conscript))
    }

    private fun raycast(mc: Minecraft): BlockPos? {
        val camera = mc.gameRenderer.mainCamera()
        val from = camera.position()
        val f = camera.forwardVector()
        val to = from.add(f.x() * 192.0, f.y() * 192.0, f.z() * 192.0)
        val player = mc.player ?: return null
        val hit = mc.level?.clip(ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.ANY, player)) ?: return null
        if (hit.type != HitResult.Type.BLOCK) return null
        return (hit as BlockHitResult).blockPos
    }

    fun currentTemplate(): TemplateView? = templates.getOrNull(templateIndex)

    private fun footprint(box: Box): Box? {
        val t = currentTemplate() ?: return null
        val sx = if (rotation % 2 == 1) t.sizeZ else t.sizeX
        val sz = if (rotation % 2 == 1) t.sizeX else t.sizeZ
        if (box.sizeX < sx || box.sizeZ < sz) return null
        val minX = box.minX + (box.sizeX - sx) / 2
        val minZ = box.minZ + (box.sizeZ - sz) / 2
        return Box(minX, box.minY, minZ, minX + sx - 1, box.minY + t.sizeY - 1, minZ + sz - 1)
    }

    fun problem(box: Box): String? {
        val i = info ?: return "정보를 받는 중"
        if (!i.ruler) return "다스리는 나라가 없다"
        if (box.sizeX > 48 || box.sizeZ > 48) return "48×48 초과"
        val own = i.chunks.filter { it.own }.map { it.key }.toHashSet()
        val target = if (tool == WorkTool.BUILD) footprint(box) ?: return "영역이 건물보다 작다" else box
        for (c in target.chunks()) if (c !in own) return "영토 밖 ${Chunks.format(c)}"
        if (tool == WorkTool.BUILD || tool == WorkTool.WALLS || tool == WorkTool.FLATTEN) {
            val hit = i.buildings.firstOrNull { b ->
                b.box.minX <= target.maxX + 1 && b.box.maxX >= target.minX - 1 && b.box.minZ <= target.maxZ + 1 && b.box.maxZ >= target.minZ - 1
            }
            if (hit != null) return "${hit.kind.label} #${hit.id}와 겹침"
        }
        return null
    }

    private fun drawOutlines(mc: Minecraft) {
        val level = mc.level ?: return
        val player = mc.player ?: return
        val live = dragStart?.let { a -> dragEnd?.let { b -> Box.of(a.x, a.y, a.z, b.x, b.y, b.z) } }
        val box = live ?: selection
        if (box != null) {
            val ok = problem(box) == null
            outline(level, box, if (ok) 0x3CFF5A else 0xFF3C3C, 1.4f)
            if (tool == WorkTool.BUILD) footprint(box)?.let { outline(level, it, 0xFFFFFF, 1.0f) }
        }
        val i = info ?: return
        for (b in i.buildings) {
            if (Vec3(b.box.centerX, b.box.minY.toDouble(), b.box.centerZ).distanceTo(player.position()) > 72) continue
            val color = when {
                selected?.id == b.id -> 0xFFE040
                !b.complete -> 0xFF9A1E
                b.own -> 0x3CC8FF
                else -> 0xC8C8C8
            }
            outline(level, b.box, color, 0.9f)
        }
        for (o in i.orders) {
            if (o.type == com.grandstrategy.sim.WorkType.BUILD) continue
            outline(level, o.box, 0xFF9A1E, 1.0f)
        }
        hover?.let { h -> outline(level, Box(h.x, h.y, h.z, h.x, h.y, h.z), 0xFFFFFF, 0.6f) }
    }

    private fun outline(level: net.minecraft.client.multiplayer.ClientLevel, box: Box, color: Int, size: Float) {
        val dust = DustParticleOptions(color, size)
        val x0 = box.minX.toDouble(); val x1 = box.maxX + 1.0
        val y0 = box.minY.toDouble(); val y1 = box.maxY + 1.0
        val z0 = box.minZ.toDouble(); val z1 = box.maxZ + 1.0
        fun line(ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double) {
            val len = maxOf(kotlin.math.abs(bx - ax), kotlin.math.abs(by - ay), kotlin.math.abs(bz - az))
            val steps = maxOf(1, (len / 0.8).toInt())
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                level.addAlwaysVisibleParticle(dust, true, ax + (bx - ax) * t, ay + (by - ay) * t, az + (bz - az) * t, 0.0, 0.0, 0.0)
            }
        }
        line(x0, y0, z0, x1, y0, z0); line(x0, y0, z1, x1, y0, z1); line(x0, y0, z0, x0, y0, z1); line(x1, y0, z0, x1, y0, z1)
        line(x0, y1, z0, x1, y1, z0); line(x0, y1, z1, x1, y1, z1); line(x0, y1, z0, x0, y1, z1); line(x1, y1, z0, x1, y1, z1)
        line(x0, y0, z0, x0, y1, z0); line(x1, y0, z0, x1, y1, z0); line(x0, y0, z1, x0, y1, z1); line(x1, y0, z1, x1, y1, z1)
    }

    private fun toolIcon(t: WorkTool): net.minecraft.world.item.Item = when (t) {
        WorkTool.SELECT -> net.minecraft.world.item.Items.SPYGLASS
        WorkTool.BUILD -> net.minecraft.world.item.Items.BRICKS
        WorkTool.WALLS -> net.minecraft.world.item.Items.STONE_BRICK_WALL
        WorkTool.DEMOLISH -> net.minecraft.world.item.Items.TNT
        WorkTool.FLATTEN -> net.minecraft.world.item.Items.IRON_SHOVEL
    }

    fun hud(g: GuiGraphicsExtractor) {
        if (!active) return
        val font = Minecraft.getInstance().font
        val i = info
        val x = 6
        val w = 236
        val inner = x + 10
        val orders = i?.orders?.take(5) ?: emptyList()
        val box = dragStart?.let { a -> dragEnd?.let { b -> Box.of(a.x, a.y, a.z, b.x, b.y, b.z) } } ?: selection
        val showToast = lastMessage.isNotEmpty() && ticks - lastMessageAt < 100
        var h = 24 + 26 + (if (tool == WorkTool.BUILD) 28 else 0) + 22 + 20 + 14 + 12
        if (orders.isNotEmpty()) h += 12 + orders.size * 20
        if (showToast) h += 14
        val bosses = runCatching {
            ((Minecraft.getInstance().gui.hud.bossOverlay as Any) as com.grandstrategy.client.mixin.BossHealthOverlayAccessor).`grandstrategy$events`().size
        }.getOrDefault(0)
        val top = 6 + bosses * 19
        com.grandstrategy.client.ui.Ui.panel(g, x, top, w, h)
        val header = Component.literal("작업 지정 · ${i?.nation?.ifEmpty { "소속 없음" } ?: "…"}")
        com.grandstrategy.client.ui.Ui.ribbon(g, font, x + 6, top + 6, w - 12, i?.color ?: 0x8A8A8A, header, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.WRITABLE_BOOK))
        var y = top + 28
        WorkTool.entries.forEachIndexed { k, t -> com.grandstrategy.client.ui.Ui.slot(g, inner + k * 21, y, toolIcon(t), t == tool) }
        g.text(font, Component.literal(tool.label), inner + 5 * 21 + 6, y + 2, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.GOLD), true)
        g.text(font, Component.literal("[C] 바꾸기  [B] 끄기"), inner + 5 * 21 + 6, y + 11, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.MUTED), false)
        y += 26
        if (tool == WorkTool.BUILD) {
            val t = currentTemplate()
            com.grandstrategy.client.ui.Ui.slot(g, inner, y, com.grandstrategy.client.ui.Ui.kindIcon(t?.kind), false)
            g.text(font, Component.literal(t?.let { "${it.kind.label} · ${it.name}" } ?: "건물 목록 받는 중"), inner + 24, y + 1, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.TEXT), true)
            g.text(font, Component.literal(t?.let { "${it.sizeX}×${it.sizeY}×${it.sizeZ} · 회전 ${rotation * 90}°  [Z/X] [R]" } ?: ""), inner + 24, y + 11, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.MUTED), false)
            y += 28
        }
        com.grandstrategy.client.ui.Ui.slot(g, inner, y, net.minecraft.world.item.Items.BELL, conscript)
        g.text(font, Component.literal(if (conscript) "징용 켬 · 일반 국민도 동원" else "징용 끔 · 건축가만"), inner + 24, y + 1,
            com.grandstrategy.client.ui.Ui.argb(if (conscript) 0xFF9A1E else com.grandstrategy.client.ui.Ui.TEXT), true)
        g.text(font, Component.literal("[V]  건축가 ${i?.builders ?: 0} · 쉬는 국민 ${i?.idle ?: 0}"), inner + 24, y + 11, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.MUTED), false)
        y += 22
        if (i != null) {
            var rx = inner
            for ((label, value) in i.stock) {
                g.item(net.minecraft.world.item.ItemStack(com.grandstrategy.client.ui.Ui.resourceIcon(label)), rx, y)
                g.text(font, Component.literal(value.toString()), rx + 17, y + 5, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.TEXT), true)
                rx += 17 + font.width(value.toString()) + 8
            }
        }
        y += 20
        val (statusText, statusColor) = when {
            tool == WorkTool.SELECT -> "건물을 클릭하면 외곽을 감싼 상자로 인식" to com.grandstrategy.client.ui.Ui.MUTED
            box == null -> "좌클릭으로 끌어서 영역 지정" to com.grandstrategy.client.ui.Ui.MUTED
            else -> {
                val p = problem(box)
                "${box.sizeX}×${box.sizeY}×${box.sizeZ} · ${p ?: "가능 — Enter/우클릭으로 지시"}" to (if (p == null) 0x5AE66E else 0xFF6A5A)
            }
        }
        g.fill(inner - 2, y - 2, x + w - 8, y + 10, com.grandstrategy.client.ui.Ui.argb(0x000000, 90))
        g.text(font, Component.literal(statusText), inner, y, com.grandstrategy.client.ui.Ui.argb(statusColor), true)
        y += 14
        if (orders.isNotEmpty()) {
            g.text(font, Component.literal("진행 중인 작업"), inner, y, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.GOLD), true)
            y += 12
            for (o in orders) {
                val icon = when (o.type) {
                    com.grandstrategy.sim.WorkType.BUILD -> com.grandstrategy.client.ui.Ui.kindIcon(o.kind)
                    com.grandstrategy.sim.WorkType.DEMOLISH -> net.minecraft.world.item.Items.TNT
                    com.grandstrategy.sim.WorkType.FLATTEN -> net.minecraft.world.item.Items.IRON_SHOVEL
                }
                g.item(net.minecraft.world.item.ItemStack(icon), inner, y)
                val fraction = if (o.total > 0) o.progress.toDouble() / o.total else 0.0
                g.text(font, Component.literal("#${o.id} ${o.type.label}${o.kind?.let { " " + it.label } ?: ""}"), inner + 20, y, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.TEXT), true)
                val people = "인부 ${o.present}/${o.crew}"
                g.text(font, Component.literal(people), x + w - 12 - font.width(people), y, com.grandstrategy.client.ui.Ui.argb(com.grandstrategy.client.ui.Ui.MUTED), false)
                com.grandstrategy.client.ui.Ui.bar(g, inner + 20, y + 10, w - 46, fraction, if (o.type == com.grandstrategy.sim.WorkType.BUILD) 0x5AC85A else 0xE09A3C)
                y += 20
            }
        }
        if (showToast) {
            g.text(font, Component.literal(lastMessage), inner, y + 2, com.grandstrategy.client.ui.Ui.argb(if (lastMessageOk) 0x5AE66E else 0xFF6A5A), true)
        }
    }
}
