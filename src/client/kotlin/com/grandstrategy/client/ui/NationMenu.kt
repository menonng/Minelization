package com.grandstrategy.client.ui

import com.grandstrategy.client.work.WorkMode
import com.grandstrategy.net.MapFlags
import com.grandstrategy.net.MenuAction
import com.grandstrategy.net.MenuOpen
import com.grandstrategy.net.MenuView
import com.grandstrategy.net.NationRow
import com.grandstrategy.net.ToClient
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.EventKind
import com.grandstrategy.sim.Facility
import com.grandstrategy.sim.Policy
import com.grandstrategy.sim.Resource
import com.grandstrategy.sim.TechBranch
import com.grandstrategy.sim.Terrain
import com.grandstrategy.sim.Treaty
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.levelgen.Heightmap
import kotlin.math.abs
import kotlin.math.roundToInt

object NationMenu {
    private val category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("grandstrategy", "menu"))
    private val openKey = KeyMappingHelper.registerKeyMapping(KeyMapping("key.grandstrategy.menu", InputConstants.KEY_G, category))

    var toast = ""
    var toastOk = true
    var toastAt = 0L

    fun init() {}

    fun tick(mc: Minecraft) {
        while (openKey.consumeClick()) if (mc.gui.screen() == null) WorkMode.send(MenuOpen("overview"))
    }

    fun onView(view: MenuView) {
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen()
        if (screen is NationMenuScreen) screen.update(view)
        else if (view.open) mc.gui.setScreen(NationMenuScreen(view))
    }

    fun onResult(result: ToClient.Result) {
        toast = result.message
        toastOk = result.ok
        toastAt = System.currentTimeMillis()
    }

    fun act(action: String, a: Any = "", b: Any = "") = WorkMode.send(MenuAction(action, a.toString(), b.toString()))

    fun bannerOf(dye: Int): ItemStack = ItemStack(Items.BANNER.pick(DyeColor.byId(dye)))

    private val flagCache = HashMap<Pair<com.grandstrategy.sim.Flag?, Int>, ItemStack>()

    /** A banner item showing a nation's flag (falls back to a plain banner of its colour). */
    fun flagOf(flag: com.grandstrategy.sim.Flag?, dye: Int): ItemStack {
        val level = Minecraft.getInstance().level ?: return bannerOf(dye)
        if (flagCache.size > 256) flagCache.clear()
        return flagCache.getOrPut(flag to dye) { com.grandstrategy.mc.FlagItems.stack(flag, dye, level.registryAccess()) }
    }

    fun techIcon(t: TechBranch): Item = when (t) {
        TechBranch.AGRICULTURE -> Items.WHEAT_SEEDS
        TechBranch.MINING -> Items.IRON_PICKAXE
        TechBranch.INDUSTRY -> Items.ANVIL
        TechBranch.LOGISTICS -> Items.MINECART
        TechBranch.ADMINISTRATION -> Items.WRITABLE_BOOK
        TechBranch.EDUCATION -> Items.BOOKSHELF
        TechBranch.MILITARY -> Items.IRON_SWORD
        TechBranch.CONSTRUCTION -> Items.BRICKS
        TechBranch.MARINE -> Items.HEART_OF_THE_SEA
    }

    fun policyIcon(p: Policy): Item = when (p) {
        Policy.BALANCED -> Items.COMPASS
        Policy.ECONOMY -> Items.EMERALD
        Policy.MILITARY -> Items.CROSSBOW
        Policy.SCIENCE -> Items.ENCHANTING_TABLE
        Policy.EXPANSION -> Items.OAK_SAPLING
    }

    fun facilityIcon(f: Facility): Item = when (f) {
        Facility.FARM -> Items.COMPOSTER
        Facility.LUMBER_CAMP -> Items.IRON_AXE
        Facility.QUARRY -> Items.STONECUTTER
        Facility.MINE -> Items.BLAST_FURNACE
        Facility.MARKET -> Items.EMERALD
        Facility.LIBRARY -> Items.LECTERN
        Facility.BARRACKS -> Items.SHIELD
        Facility.WALLS -> Items.STONE_BRICK_WALL
    }

    fun resourceItem(r: Resource): Item = when (r) {
        Resource.FOOD -> Items.WHEAT
        Resource.WOOD -> Items.OAK_LOG
        Resource.STONE -> Items.COBBLESTONE
        Resource.IRON -> Items.IRON_INGOT
        Resource.GOLD -> Items.GOLD_INGOT
    }

    fun treatyIcon(t: Treaty): Item = when (t) {
        Treaty.NON_AGGRESSION -> Items.SHIELD
        Treaty.TRADE -> Items.EMERALD
        Treaty.ALLIANCE -> Items.GOLDEN_APPLE
        Treaty.TRUCE -> WHITE_FLAG
    }

    val terrainColor = intArrayOf(0x7FB238, 0x4E7A2C, 0x3F6A4E, 0x2F8A2A, 0xB5A64F, 0xE3D49A, 0xB5653A, 0x8C9A6E, 0x8E8E8E, 0xEAF2F2, 0x4A78D0, 0xD8CFA0, 0x3A5FC8)

    fun number(v: Double): String = when {
        abs(v) >= 1000 -> "%.1fk".format(v / 1000)
        abs(v) >= 100 -> "%.0f".format(v)
        else -> "%.1f".format(v)
    }

    fun signed(v: Double) = (if (v >= 0) "+" else "") + number(v)
}

private val WHITE_FLAG: Item get() = Items.BANNER.white()

private const val RESEARCH_CARD_H = 40

class NationMenuScreen(private var view: MenuView) : Screen(Component.literal("국가")) {
    private var page = view.page.ifEmpty { "overview" }
    private var selected: Int? = null
    private var showTowns = false
    private var scroll = 0
    private var confirmWar: Int? = null
    private val panelW get() = minOf(400, width - 12)
    private val panelH get() = minOf(252, height - 12)
    private val left get() = (width - panelW) / 2
    private val top get() = (height - panelH) / 2
    private val cx get() = left + 10
    private val cy get() = top + 54
    private val cw get() = panelW - 20
    private val ch get() = panelH - 66

    fun update(next: MenuView) {
        view = next
        if (next.open && next.page.isNotEmpty()) {
            page = next.page
            scroll = 0
        }
        rebuildWidgets()
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        NationMenu.act("close")
        super.onClose()
    }

    private fun go(next: String) {
        page = next
        scroll = 0
        NationMenu.act("page", next)
        rebuildWidgets()
    }

    override fun init() {
        val tabs = listOf("overview" to ("국가" to Items.BELL), "map" to ("지도" to Items.FILLED_MAP), "diplomacy" to ("외교" to WHITE_FLAG),
            "research" to ("연구" to Items.BOOKSHELF), "era" to ("시대" to Items.CLOCK), "flag" to ("국기" to Items.LOOM), "log" to ("기록" to Items.WRITABLE_BOOK))
        if (view.nation != null) {
            val tw = (panelW - 20) / tabs.size
            tabs.forEachIndexed { k, (id, meta) ->
                addRenderableWidget(IconButton(left + 10 + k * tw, top + 28, tw - 3, 20, Component.literal(meta.first), meta.second, { page == id }) { go(id) })
            }
        }
        addRenderableWidget(IconButton(left + panelW - 30, top + 6, 22, 18, Component.literal(""), Items.BARRIER) { onClose() })
        val player = Minecraft.getInstance().player
        val spectating = player?.isSpectator == true
        val mode = IconButton(left + panelW - 54, top + 6, 22, 18, Component.literal(""), if (spectating) Items.ENDER_EYE else Items.LEATHER_BOOTS) {
            NationMenu.act("mode", if (spectating) "adventure" else "spectator")
            onClose()
        }
        mode.setTooltip(Tooltip.create(Component.literal(if (spectating) "모험 모드로 바꾸기 (땅을 걸어 다닌다)" else "관전 모드로 바꾸기 (하늘에서 본다)")))
        addRenderableWidget(mode)
        val n = view.nation
        if (n == null) return buildChoose()
        if (page == "map") button(left + panelW - 120, top + panelH - 30, 106, "전체 지도", Items.FILLED_MAP, "[M] 세계 지도 열기") {
            onClose()
            WorldMap.open()
        }
        when (page) {
            "overview" -> buildOverview()
            "diplomacy" -> buildDiplomacy()
            "research" -> buildResearch()
            "era" -> buildEra()
            "flag" -> buildFlag()
        }
    }

    // ---- Era page: the ladder of ages, and what each age unlocks ----

    private var eraKind = "weapon"
    private val eraKinds = listOf("tech" to ("기술" to Items.BOOK), "weapon" to ("무기" to Items.IRON_SWORD),
        "energy" to ("에너지" to Items.BLAZE_POWDER), "ideology" to ("사상" to Items.WRITABLE_BOOK))
    private val eraX get() = cx + 124
    private val eraW get() = cw - 124
    private val eraListY get() = cy + 50

    private fun buildEra() {
        val bw = (eraW - 6) / eraKinds.size
        eraKinds.forEachIndexed { k, (id, meta) ->
            addRenderableWidget(IconButton(eraX + k * (bw + 2), cy + 26, bw, 18, Component.literal(meta.first), meta.second, { eraKind == id }) {
                eraKind = id
                scroll = 0
                rebuildWidgets()
            })
        }
    }

    private fun eraRows(): List<com.grandstrategy.net.UnlockRow> {
        val c = view.content ?: return emptyList()
        return c.items.filter { if (eraKind == "weapon") it.kind == "weapon" || it.kind == "wmd" else it.kind == eraKind }
    }

    private fun drawEra(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val n = view.nation ?: return
        val c = view.content
        if (c == null) {
            ink(g, "불러오는 중…", cx, cy)
            return
        }
        heading(g, "시대", cx, cy)
        val rowH = ((ch - 30) / c.eras.size).coerceIn(9, 13)
        c.eras.forEachIndexed { k, e ->
            val y = cy + 14 + k * rowH
            val current = k == c.eraIndex
            val past = k < c.eraIndex
            if (current) g.fill(cx - 2, y - 1, cx + 116, y + rowH - 1, Ui.argb(0xE8C252, 110))
            g.fill(cx, y + rowH / 2 - 1, cx + 3, y + rowH / 2 + 1, Ui.argb(if (past || current) 0x8A5A1A else 0xB0A080))
            ink(g, e.name, cx + 6, y + (rowH - 8) / 2, if (current) 0x3A1A00 else if (past) 0x6B5A44 else 0xA09070)
            val need = "${e.techTotal}"
            ink(g, need, cx + 112 - font.width(need), y + (rowH - 8) / 2, if (past || current) 0x8A6A3A else 0xB0A080)
        }
        val next = c.eras.getOrNull(c.eraIndex + 1)
        val barY = cy + 16 + c.eras.size * rowH
        if (next != null && barY + 8 < cy + ch) {
            val prev = c.eras[c.eraIndex].techTotal
            Ui.bar(g, cx, barY, 116, (c.techTotal - prev).toDouble() / (next.techTotal - prev).coerceAtLeast(1), 0x8A5AD0)
        }
        // Right side: the nation's current state, then the list for the chosen kind.
        ink(g, "${n.era} 시대 · 연구 합계 ${n.techTotal}" + (next?.let { " / 다음 ${it.name} ${it.techTotal}" } ?: ""), eraX, cy, 0x5A3A16)
        ink(g, "무기 ${n.weapon} · 에너지 ${n.energy} · 사상 ${n.ideology.ifEmpty { "없음" }}", eraX, cy + 12, 0x6B5A44)
        val rows = eraRows()
        val rh = 22
        val visible = ((cy + ch - eraListY) / rh).coerceAtLeast(1)
        rows.drop(scroll).take(visible).forEachIndexed { k, r ->
            val y = eraListY + k * rh
            val chosen = r.kind == "ideology" && r.id == c.ideology
            val hover = mouseX in eraX until eraX + eraW && mouseY in y until y + rh - 2
            g.fill(eraX, y, eraX + eraW, y + rh - 2, Ui.argb(if (chosen) 0xE8C252 else if (r.kind == "wmd") 0xA02A1A else 0x000000,
                if (chosen) 100 else if (r.kind == "wmd") 30 else if (hover && r.unlocked) 40 else 18))
            val icon = when (r.kind) {
                "tech" -> Items.BOOK
                "weapon" -> Items.IRON_SWORD
                "wmd" -> Items.TNT
                "energy" -> Items.BLAZE_POWDER
                else -> Items.WRITABLE_BOOK
            }
            drawIcon(g, ItemStack(if (r.unlocked) icon else Items.BARRIER), eraX + 10, y + 10, 12)
            val era = c.eras.getOrNull(r.era)?.name ?: ""
            ink(g, "${r.name}  ", eraX + 20, y + 1, if (r.unlocked) Ui.INK else 0x9A8A70)
            ink(g, era, eraX + 20 + font.width("${r.name}  "), y + 1, 0x8A6A3A)
            val info = font.plainSubstrByWidth(if (r.unlocked) r.value else "필요: ${r.need}", eraW - 24)
            ink(g, info, eraX + 20, y + 11, if (r.unlocked) 0x2E5A2E else 0x8A6A50)
            if (hover) {
                val tip = listOfNotNull(r.name, r.value.ifEmpty { null }, "필요: ${r.need}", r.detail.ifEmpty { null },
                    if (r.kind == "ideology" && r.unlocked && view.ruler && !chosen) "클릭: 국가 사상으로 삼는다" else null).joinToString("\n")
                g.setTooltipForNextFrame(font, font.split(Component.literal(tip), 220), mouseX, mouseY)
            }
        }
        if (rows.size > visible) ink(g, "휠: ${scroll + 1}-${minOf(rows.size, scroll + visible)} / ${rows.size}", eraX + eraW - 70, cy + ch - 8, 0x8A6A3A)
    }

    // ---- Flag page: the flag, and banners from the player's own bag to drag onto it ----

    private var dragSlot = -1
    private var dragStack: ItemStack = ItemStack.EMPTY
    private var dragX = 0.0
    private var dragY = 0.0
    private val flagFrameX get() = cx + 8
    private val flagFrameY get() = cy + 16
    private val flagFrameW = 84
    private val flagFrameH = 120
    private val bagX get() = cx + 112
    private val bagY get() = cy + 58

    private fun bannerSlots(): List<Pair<Int, ItemStack>> {
        val inv = Minecraft.getInstance().player?.inventory ?: return emptyList()
        return (0 until inv.containerSize).mapNotNull { i -> inv.getItem(i).takeIf { it.item is net.minecraft.world.item.BannerItem }?.let { i to it } }
    }

    private fun buildFlag() {
        button(bagX, cy + ch - 24, 120, "무작위 국기", Items.LOOM, "국가색 바탕에 무늬를 무작위로 새로 짠다", view.ruler) { NationMenu.act("flag_random") }
    }

    private fun bagSlotAt(mx: Double, my: Double): Pair<Int, ItemStack>? {
        val perRow = ((cw - 112) / 20).coerceAtLeast(1)
        bannerSlots().forEachIndexed { k, entry ->
            val x = bagX + (k % perRow) * 20
            val y = bagY + (k / perRow) * 20
            if (mx >= x && mx < x + 18 && my >= y && my < y + 18) return entry
        }
        return null
    }

    private fun overFlag(mx: Double, my: Double) = mx >= flagFrameX && mx < flagFrameX + flagFrameW && my >= flagFrameY && my < flagFrameY + flagFrameH

    private fun drawFlag(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val n = view.nation ?: return
        heading(g, "국기", cx, cy)
        val hot = dragSlot >= 0 && overFlag(mouseX.toDouble(), mouseY.toDouble())
        g.fill(flagFrameX - 3, flagFrameY - 3, flagFrameX + flagFrameW + 3, flagFrameY + flagFrameH + 3, Ui.argb(if (hot) 0xF0D060 else 0xB08A4A))
        g.fillGradient(flagFrameX, flagFrameY, flagFrameX + flagFrameW, flagFrameY + flagFrameH, Ui.argb(0x3A4A5A), Ui.argb(0x1A2028))
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate((flagFrameX + flagFrameW / 2 - 40).toFloat(), (flagFrameY + flagFrameH / 2 - 40).toFloat())
        pose.scale(5f, 5f)
        g.item(NationMenu.flagOf(if (hot) com.grandstrategy.mc.FlagItems.fromStack(dragStack) else n.flag, n.dye), 0, 0)
        pose.popMatrix()
        ink(g, if (hot) "놓으면 이 무늬가 국기가 된다" else "${n.name}의 국기", flagFrameX - 2, flagFrameY + flagFrameH + 6, 0x5A3A16)
        heading(g, "가방의 배너", bagX, cy)
        val lines = font.split(Component.literal(
            if (view.ruler) "베틀에서 만든 배너를 왼쪽 국기 칸으로 끌어다 놓으면 그 무늬가 국기가 된다. 배너는 가방에 그대로 남는다."
            else "국기는 통치자만 바꿀 수 있다."
        ), cw - 112)
        lines.take(3).forEachIndexed { k, line -> g.text(font, line, bagX, cy + 14 + k * 10, Ui.argb(0x6B5A44), false) }
        val banners = bannerSlots()
        val perRow = ((cw - 112) / 20).coerceAtLeast(1)
        banners.forEachIndexed { k, (slot, stack) ->
            val x = bagX + (k % perRow) * 20
            val y = bagY + (k / perRow) * 20
            g.fill(x, y, x + 18, y + 18, Ui.argb(0x000000, if (slot == dragSlot) 90 else 40))
            if (slot != dragSlot) g.item(stack, x + 1, y + 1)
        }
        if (banners.isEmpty()) ink(g, "가방에 배너가 없다 (모험 모드로 베틀에서 만들 수 있다)", bagX, bagY + 4, 0x9A6A4A)
        if (dragSlot >= 0) {
            val pose2 = g.pose()
            pose2.pushMatrix()
            pose2.translate((dragX - 12).toFloat(), (dragY - 12).toFloat())
            pose2.scale(1.5f, 1.5f)
            g.item(dragStack, 0, 0)
            pose2.popMatrix()
        }
    }

    override fun mouseDragged(event: net.minecraft.client.input.MouseButtonEvent, dx: Double, dy: Double): Boolean {
        if (page == "flag" && dragSlot >= 0) {
            dragX = event.x()
            dragY = event.y()
            return true
        }
        return super.mouseDragged(event, dx, dy)
    }

    override fun mouseReleased(event: net.minecraft.client.input.MouseButtonEvent): Boolean {
        if (page == "flag" && dragSlot >= 0) {
            if (overFlag(event.x(), event.y()) && view.ruler) NationMenu.act("flag_slot", dragSlot)
            dragSlot = -1
            dragStack = ItemStack.EMPTY
            return true
        }
        return super.mouseReleased(event)
    }

    private fun button(x: Int, y: Int, w: Int, label: String, icon: Item?, tip: String? = null, enabled: Boolean = true, selectedCheck: () -> Boolean = { false }, action: () -> Unit) {
        val b = IconButton(x, y, w, 20, Component.literal(label), icon, selectedCheck, action)
        b.active = enabled
        if (tip != null) b.setTooltip(Tooltip.create(Component.literal(tip)))
        addRenderableWidget(b)
    }

    private fun costText(cost: Map<Resource, Double>) = cost.entries.joinToString(", ") { "${it.key.label} ${"%.0f".format(it.value)}" }

    private fun buildChoose() {
        val rows = view.nations.take(7)
        rows.forEachIndexed { k, n ->
            val y = cy + 18 + k * 22
            button(left + panelW - 170, y, 76, "통치", Items.GOLDEN_HELMET, "통치자가 된다 (AI 나라만)", n.ruler == "AI") { NationMenu.act("pick", n.id) }
            button(left + panelW - 90, y, 76, "합류", Items.OAK_DOOR, "국민으로 합류한다") { NationMenu.act("join", n.id) }
        }
        button(cx, top + panelH - 34, 160, "여기에 건국", Items.GRASS_BLOCK, view.foundError ?: "무작위 국호로 세운다", view.foundError == null) { NationMenu.act("found") }
    }

    private fun buildOverview() {
        val n = view.nation ?: return
        val ruler = view.ruler
        val col2 = cx + cw / 2 + 4
        val colW = cw / 2 - 8
        button(col2 + colW - 70, cy + 38, 70, "징집 +5", Items.IRON_SWORD, "비용 ${costText(n.recruitCost)}", ruler) { NationMenu.act("recruit", 5) }
        button(col2, cy + 98, 120, "현 청크 편입", Items.GRASS_BLOCK, "비용 ${costText(n.claimCost)}", ruler) { NationMenu.act("claim", view.here) }
        Policy.entries.forEachIndexed { k, p ->
            val b = IconButton(cx + k * 24, cy + 148, 22, 20, Component.literal(""), NationMenu.policyIcon(p), { n.policy == p }) { NationMenu.act("policy", p.name) }
            b.active = ruler
            b.setTooltip(Tooltip.create(Component.literal("${p.label}: ${p.description}")))
            addRenderableWidget(b)
        }
        button(cx + 124, cy + 148, minOf(66, cw / 2 - 132), if (n.autoGovern) "자동 끔" else "자동", Items.COMPARATOR, if (n.autoGovern) "자동 통치 중 — 클릭해서 끈다" else "켜면 AI가 대신 운영한다", ruler, { n.autoGovern }) { NationMenu.act("auto") }
        view.proposals.take(2).forEachIndexed { k, p ->
            val y = cy + 124 + k * 22
            button(col2 + colW - 92, y, 44, "수락", Items.EMERALD, "${p.from}: ${p.label}", ruler) { NationMenu.act("accept", p.id) }
            button(col2 + colW - 46, y, 44, "거절", Items.BARRIER, "${p.from}: ${p.label}", ruler) { NationMenu.act("decline", p.id) }
        }
    }

    private fun buildDiplomacy() {
        val n = view.nation ?: return
        button(cx, cy + ch - 20, 90, if (showTowns) "나라 목록" else "자유 마을", if (showTowns) WHITE_FLAG else Items.BELL) {
            showTowns = !showTowns
            selected = null
            scroll = 0
            rebuildWidgets()
        }
        val ruler = view.ruler
        val dx = cx + 214
        val dw = cw - 214
        if (showTowns) {
            val town = view.towns.firstOrNull { it.id == selected } ?: return
            if (town.owner.isEmpty()) {
                button(dx, cy + 96, dw, "사절 보내기", Items.EMERALD, "금 15, 영향력 +15. 100이 되면 평화 편입", ruler) { NationMenu.act("envoy", town.id) }
                button(dx, cy + 120, dw, "무력 정복", Items.IRON_SWORD, "전투력이 방어력의 2배 필요. 다른 나라 호감도 하락", ruler) { NationMenu.act("conquer", town.id) }
            }
            return
        }
        val target = view.nations.firstOrNull { it.id == selected && it.id != n.id } ?: return
        var y = cy + 92
        for (t in listOf(Treaty.NON_AGGRESSION, Treaty.TRADE, Treaty.ALLIANCE)) {
            val has = t in target.treaties
            button(dx, y, dw, if (has) "${t.label} 파기" else "${t.label} 제안", NationMenu.treatyIcon(t), null, ruler && !target.atWar, { has }) {
                NationMenu.act(if (has) "untreaty" else "treaty", target.id, t.name)
            }
            y += 22
        }
        if (target.atWar) {
            button(dx, y, dw, "평화 제안", WHITE_FLAG, "점수 ${target.warScore.toInt()} · 피로 ${target.exhaustion.toInt()}", ruler) { NationMenu.act("peace", target.id) }
        } else {
            val armed = confirmWar == target.id
            button(dx, y, dw, if (armed) "정말 선전포고" else "선전포고", Items.TNT, "국경이 맞닿아야 한다. 불가침을 깨면 모두의 호감도 하락", ruler, { armed }) {
                if (armed) {
                    NationMenu.act("war", target.id)
                    confirmWar = null
                } else confirmWar = target.id
                rebuildWidgets()
            }
        }
    }

    private fun buildResearch() {
        val n = view.nation ?: return
        val fw = (cw - 4) / 8
        Facility.entries.forEachIndexed { k, f ->
            val b = IconButton(cx + k * (fw + 0) + 1, cy + ch - 22, fw - 2, 20, Component.literal("${n.facilities[f] ?: 0}"), NationMenu.facilityIcon(f)) { NationMenu.act("build", f.name) }
            b.active = view.ruler
            b.setTooltip(Tooltip.create(Component.literal("${f.label} ${n.facilities[f] ?: 0}개 — 클릭: 알맞은 자리를 찾아 건설 지시")))
            addRenderableWidget(b)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        val mx = event.x()
        val my = event.y()
        when (page) {
            "map" -> mapTileAt(mx, my)?.let { tile ->
                if (view.ruler && claimable(tile)) NationMenu.act("claim", tile.key)
                return true
            }
            "diplomacy" -> {
                val rowH = 22
                if (mx >= cx && mx < cx + 208 && my >= cy + 14 && my < cy + ch - 24) {
                    val k = ((my - cy - 14) / rowH).toInt() + scroll
                    val list = if (showTowns) view.towns.map { it.id } else view.nations.filter { it.id != view.nation?.id }.map { it.id }
                    list.getOrNull(k)?.let {
                        selected = it
                        confirmWar = null
                        rebuildWidgets()
                        return true
                    }
                }
            }
            "flag" -> bagSlotAt(mx, my)?.let { (slot, stack) ->
                if (!view.ruler) return true
                dragSlot = slot
                dragStack = stack.copy()
                dragX = mx
                dragY = my
                return true
            }
            "era" -> {
                val rh = 22
                if (mx >= eraX && mx < eraX + eraW && my >= eraListY) {
                    val k = ((my - eraListY) / rh).toInt() + scroll
                    val r = eraRows().getOrNull(k)
                    if (r != null && r.kind == "ideology" && r.unlocked && view.ruler && r.id != view.content?.ideology) {
                        NationMenu.act("ideology", r.id)
                        return true
                    }
                }
            }
            "research" -> {
                val (cardW, cardH) = (cw - 6) / 4 to RESEARCH_CARD_H
                TechBranch.entries.forEachIndexed { k, t ->
                    val x = cx + (k % 4) * (cardW + 2)
                    val y = cy + 4 + (k / 4) * (cardH + 4)
                    if (mx >= x && mx < x + cardW && my >= y && my < y + cardH && view.ruler) {
                        NationMenu.act("research", t.name)
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        val max = when (page) {
            "diplomacy" -> (if (showTowns) view.towns.size else view.nations.size) - 6
            "log" -> view.events.size - 8
            "era" -> eraRows().size - ((ch - (eraListY - cy)) / 22)
            else -> 0
        }.coerceAtLeast(0)
        scroll = (scroll - scrollY.toInt()).coerceIn(0, max)
        return true
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fillGradient(0, 0, width, height, Ui.argb(0x000000, 80), Ui.argb(0x000000, 160))
        Ui.panel(g, left, top, panelW, panelH, parchment = true)
        val n = view.nation
        val title = if (n != null) "${n.name} · ${view.turn}턴 · 통치자 ${n.ruler}" else "나라 선택 · ${view.turn}턴"
        Ui.ribbon(g, font, left + 10, top + 8, panelW - 70, n?.color ?: 0x8A6A3A, Component.literal(title), n?.let { NationMenu.flagOf(it.flag, it.dye) } ?: ItemStack(Items.MAP))
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(g, mouseX, mouseY, partialTick)
        if (view.nation == null) drawChoose(g) else when (page) {
            "overview" -> drawOverview(g)
            "map" -> drawMap(g, mouseX, mouseY)
            "diplomacy" -> drawDiplomacy(g, mouseX, mouseY)
            "research" -> drawResearch(g, mouseX, mouseY)
            "era" -> drawEra(g, mouseX, mouseY)
            "flag" -> drawFlag(g, mouseX, mouseY)
            "log" -> drawLog(g)
        }
        if (NationMenu.toast.isNotEmpty() && System.currentTimeMillis() - NationMenu.toastAt < 4000) {
            val w = font.width(NationMenu.toast) + 12
            val x = left + (panelW - w) / 2
            val y = top + panelH + 2
            if (y + 14 < height) {
                g.fill(x, y, x + w, y + 14, Ui.argb(0x000000, 200))
                g.text(font, Component.literal(NationMenu.toast), x + 6, y + 3, Ui.argb(if (NationMenu.toastOk) 0x7CF08A else 0xFF7A6A), false)
            }
        }
    }

    private fun ink(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int = Ui.INK) = g.text(font, Component.literal(text), x, y, Ui.argb(color), false)

    private fun heading(g: GuiGraphicsExtractor, text: String, x: Int, y: Int) {
        ink(g, text, x, y, 0x5A3A16)
        g.fill(x, y + 9, x + font.width(text), y + 10, Ui.argb(0xB08A4A))
    }

    private fun drawChoose(g: GuiGraphicsExtractor) {
        heading(g, "이 세계의 나라들", cx, cy - 22)
        view.nations.take(7).forEachIndexed { k, n ->
            val y = cy + 18 + k * 22
            g.item(NationMenu.flagOf(n.flag, n.dye), cx, y + 2)
            g.text(font, Component.literal(n.name), cx + 20, y + 2, Ui.argb(Ui.readable(n.color)), false)
            ink(g, "영토 ${n.claims} · 인구 ${n.population} · 병력 ${n.military} · ${n.ruler}", cx + 20, y + 11, 0x6B5A44)
        }
        ink(g, view.foundError ?: "지금 선 곳에 새 나라를 세울 수 있다", cx + 166, top + panelH - 28, if (view.foundError == null) 0x2E6A2E else 0x9A2A1A)
    }

    private fun drawOverview(g: GuiGraphicsExtractor) {
        val n = view.nation ?: return
        heading(g, "자원 (보관 한도 ${NationMenu.number(n.storage)})", cx, cy)
        var x = cx
        val step = (cw - 4) / 5
        for (r in Resource.entries) {
            Ui.slot(g, x, cy + 12, NationMenu.resourceItem(r))
            ink(g, NationMenu.number(n.stock[r] ?: 0.0), x + 21, cy + 13)
            val inc = n.income[r] ?: 0.0
            ink(g, NationMenu.signed(inc), x + 21, cy + 22, if (inc >= 0) 0x2E7A2E else 0xA02A1A)
            x += step
        }
        val col2 = cx + cw / 2 + 4
        val colW = cw / 2 - 8
        fun stat(xx: Int, yy: Int, w: Int, icon: Item, label: String, value: String, fraction: Double, rgb: Int) {
            Ui.slot(g, xx, yy, icon)
            ink(g, label, xx + 22, yy)
            ink(g, value, xx + 22 + font.width(label) + 5, yy, 0x6B5A44)
            Ui.bar(g, xx + 22, yy + 10, w - 22, fraction, rgb)
        }
        stat(cx, cy + 40, colW, Items.CAKE, "인구", "${n.population.toInt()} / ${n.housing.toInt()}", n.population / n.housing.coerceAtLeast(1.0), 0x5AA0E0)
        stat(col2, cy + 40, colW - 74, Items.IRON_SWORD, "병력", "${n.military.toInt()}/${n.recruitCap.toInt()}", n.military / n.recruitCap.coerceAtLeast(1.0), 0xC04A3A)
        ink(g, "전투력 ${NationMenu.number(n.strength)}", col2 + 22, cy + 60, 0x6B5A44)
        stat(cx, cy + 70, colW, Items.BELL, "안정도", "${n.stability.toInt()}${if (n.wars > 0) " · 전쟁 ${n.wars}" else ""}", n.stability / 100,
            if (n.stability >= 60) 0x4AB04A else if (n.stability >= 30) 0xD8B030 else 0xC83A2A)
        stat(col2, cy + 70, colW, Items.ENCHANTED_BOOK, "연구", "${n.researchFocus.label} ${n.tech[n.researchFocus] ?: 0}→${(n.tech[n.researchFocus] ?: 0) + 1}",
            n.researchProgress / n.researchCost.coerceAtLeast(1.0), 0x8A5AD0)
        stat(cx, cy + 100, colW, Items.FILLED_MAP, "영토", "${n.claims} 청크 (과확장 ${n.claimLimit})", n.claims.toDouble() / n.claimLimit.coerceAtLeast(1), 0xC89A4A)
        ink(g, "자세 ${n.posture.label}", col2 + 126, cy + 104, 0x6B5A44)
        view.proposals.take(2).forEachIndexed { k, p ->
            val y = cy + 124 + k * 22
            g.item(NationMenu.bannerOf(view.nations.firstOrNull { it.name == p.from }?.dye ?: 0), col2, y + 2)
            val more = if (k == 1 && view.proposals.size > 2) " 외 ${view.proposals.size - 2}건" else ""
            ink(g, font.plainSubstrByWidth("${p.from}: ${p.label}", colW - 116 - font.width(more)) + more, col2 + 18, y + 6, 0x8A3A1A)
        }
        heading(g, "정책 · ${n.policy.label}", cx, cy + 134)
        if (!view.ruler) ink(g, "통치자만 조작할 수 있다", cx + 240, cy + 154, 0x9A2A1A)
    }

    private fun mapGeometry(): Triple<Int, Int, Int> {
        val cols = view.mapRadiusX * 2 + 1
        val rows = view.mapRadiusZ * 2 + 1
        val t = minOf(cw / cols, (ch - 16) / rows).coerceAtLeast(3)
        val ox = cx + (cw - cols * t) / 2
        val oy = cy + 2 + (ch - 16 - rows * t) / 2
        return Triple(t, ox, oy)
    }

    private fun mapTileAt(mx: Double, my: Double): com.grandstrategy.net.MapTile? {
        if (page != "map") return null
        val (t, ox, oy) = mapGeometry()
        val col = ((mx - ox) / t).toInt()
        val row = ((my - oy) / t).toInt()
        val cols = view.mapRadiusX * 2 + 1
        if (mx < ox || my < oy || col !in 0 until cols || row !in 0..view.mapRadiusZ * 2) return null
        return view.tiles.getOrNull(row * cols + col)
    }

    private fun claimable(tile: com.grandstrategy.net.MapTile): Boolean {
        val me = view.nation?.id ?: return false
        if (tile.owner != 0 || tile.terrain == Terrain.WATER.ordinal || tile.flags and MapFlags.TOWN != 0) return false
        val byKey = view.tiles.associateBy { it.key }
        return Chunks.neighbors(tile.key).any { byKey[it]?.owner == me }
    }

    private fun drawMap(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val (t, ox, oy) = mapGeometry()
        val cols = view.mapRadiusX * 2 + 1
        val colors = view.nations.associate { it.id to it.color }
        val names = view.nations.associate { it.id to it.name }
        val level = Minecraft.getInstance().level
        val byKey = view.tiles.associateBy { it.key }
        g.fill(ox - 2, oy - 2, ox + cols * t + 2, oy + (view.mapRadiusZ * 2 + 1) * t + 2, Ui.argb(0x2A1C10))
        view.tiles.forEachIndexed { k, tile ->
            val x = ox + (k % cols) * t
            val y = oy + (k / cols) * t
            var base = NationMenu.terrainColor.getOrElse(tile.terrain) { 0x7FB238 }
            if (level != null) {
                val bx = Chunks.x(tile.key) * 16 + 8
                val bz = Chunks.z(tile.key) * 16 + 8
                if (level.chunkSource.hasChunk(bx shr 4, bz shr 4)) {
                    val top = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1
                    val pos = BlockPos(bx, top, bz)
                    val col = level.getBlockState(pos).getMapColor(level, pos).col
                    if (col != 0) base = col
                }
            }
            g.fill(x, y, x + t, y + t, Ui.argb(base))
            val owner = tile.owner
            if (owner != 0) {
                val c = colors[owner] ?: 0xFFFFFF
                g.fill(x, y, x + t, y + t, Ui.argb(c, 110))
                val edge = Ui.argb(Ui.lighten(c, -60))
                if (byKey[Chunks.pack(Chunks.x(tile.key), Chunks.z(tile.key) - 1)]?.owner != owner) g.fill(x, y, x + t, y + 1, edge)
                if (byKey[Chunks.pack(Chunks.x(tile.key), Chunks.z(tile.key) + 1)]?.owner != owner) g.fill(x, y + t - 1, x + t, y + t, edge)
                if (byKey[Chunks.pack(Chunks.x(tile.key) - 1, Chunks.z(tile.key))]?.owner != owner) g.fill(x, y, x + 1, y + t, edge)
                if (byKey[Chunks.pack(Chunks.x(tile.key) + 1, Chunks.z(tile.key))]?.owner != owner) g.fill(x + t - 1, y, x + t, y + t, edge)
            } else if (tile.flags and MapFlags.TOWN != 0) {
                g.fill(x, y, x + t, y + t, Ui.argb(0x55FF55, 60))
            }
            if (tile.flags and MapFlags.BUILDING != 0 && t >= 6) g.fill(x + t / 2 - 1, y + t / 2 - 1, x + t / 2 + 1, y + t / 2 + 1, Ui.argb(0x6A4A2A))
        }
        view.tiles.forEachIndexed { k, tile ->
            val x = ox + (k % cols) * t
            val y = oy + (k / cols) * t
            if (tile.flags and MapFlags.CAPITAL != 0) drawIcon(g, NationMenu.bannerOf(view.nations.firstOrNull { it.id == tile.owner }?.dye ?: 0), x + t / 2, y + t / 2, t)
            if (tile.flags and MapFlags.TOWN_CENTER != 0) drawIcon(g, ItemStack(Items.BELL), x + t / 2, y + t / 2, t)
        }
        val center = view.tiles.indexOfFirst { it.key == view.here }
        if (center >= 0) {
            val x = ox + (center % cols) * t + t / 2
            val y = oy + (center / cols) * t + t / 2
            g.fill(x - 2, y - 2, x + 3, y + 3, Ui.argb(0x000000))
            g.fill(x - 1, y - 1, x + 2, y + 2, Ui.argb(0xFFFFFF))
        }
        val hover = mapTileAt(mouseX.toDouble(), mouseY.toDouble())
        if (hover != null) {
            val k = view.tiles.indexOf(hover)
            val x = ox + (k % cols) * t
            val y = oy + (k / cols) * t
            val canClaim = view.ruler && claimable(hover)
            g.fill(x, y, x + t, y + 1, Ui.argb(0xFFFFFF)); g.fill(x, y + t - 1, x + t, y + t, Ui.argb(0xFFFFFF))
            g.fill(x, y, x + 1, y + t, Ui.argb(0xFFFFFF)); g.fill(x + t - 1, y, x + t, y + t, Ui.argb(0xFFFFFF))
            val lines = mutableListOf<Component>(
                Component.literal("${Chunks.format(hover.key)} · ${Terrain.entries.getOrNull(hover.terrain)?.label ?: ""}"),
                Component.literal(names[hover.owner]?.let { "$it 영토" } ?: if (hover.flags and MapFlags.TOWN != 0) "자유 마을 땅" else "무주지")
            )
            if (hover.flags and MapFlags.CAPITAL != 0) lines += Component.literal("수도")
            if (canClaim) lines += Component.literal("클릭: 편입 (${costText(view.nation!!.claimCost)})")
            g.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY)
        }
        val ly = cy + ch - 12
        val legend = listOf(Items.BANNER.white() to "수도", Items.BELL to "마을", Items.FILLED_MAP to "흰 점: 현재 위치 · 클릭: 맞닿은 무주지 편입")
        var lx = cx
        for ((item, text) in legend) {
            drawIcon(g, ItemStack(item), lx + 4, ly + 4, 8)
            ink(g, text, lx + 11, ly, 0x5A4A34)
            lx += 18 + font.width(text)
        }
    }

    private fun drawIcon(g: GuiGraphicsExtractor, stack: ItemStack, centerX: Int, centerY: Int, size: Int) {
        val s = (size.coerceAtLeast(8) / 16f).coerceAtMost(1f)
        val pose = g.pose()
        pose.pushMatrix()
        pose.translate(centerX - 8 * s, centerY - 8 * s)
        pose.scale(s, s)
        g.item(stack, 0, 0)
        pose.popMatrix()
    }

    private fun drawDiplomacy(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val me = view.nation ?: return
        heading(g, if (showTowns) "자유 마을" else "다른 나라들", cx, cy)
        val rowH = 22
        val visible = (ch - 38) / rowH
        if (showTowns) {
            view.towns.drop(scroll).take(visible).forEachIndexed { k, s ->
                val y = cy + 14 + k * rowH
                val sel = selected == s.id
                g.fill(cx, y, cx + 208, y + rowH - 2, Ui.argb(if (sel) 0xE8C252 else 0x000000, if (sel) 110 else 25))
                g.item(ItemStack(if (s.owner.isEmpty()) Items.BELL else Items.BANNER.white()), cx + 2, y + 2)
                ink(g, s.name, cx + 20, y + 2)
                ink(g, if (s.owner.isEmpty()) "영향력 ${s.influence} · ${s.distance}청크" else "${s.owner} 소속", cx + 20, y + 11, 0x6B5A44)
                if (s.owner.isEmpty()) Ui.bar(g, cx + 150, y + 6, 54, s.influence / 100.0, 0x4AB04A)
            }
            val s = view.towns.firstOrNull { it.id == selected } ?: return ink(g, "마을을 고른다", cx + 214, cy + 20, 0x6B5A44)
            val dx = cx + 214
            heading(g, s.name, dx, cy + 14)
            ink(g, "인구 ${s.population}${if (s.observed) "" else " (추정)"} · 골렘 ${s.golems}", dx, cy + 30)
            ink(g, "방어력 ${s.defense} · 거리 ${s.distance}청크", dx, cy + 42)
            ink(g, if (s.owner.isEmpty()) "우리 영향력 ${s.influence}/100" else "${s.owner}의 도시", dx, cy + 54)
            ink(g, "우리 전투력 ${NationMenu.number(me.strength)}", dx, cy + 66, 0x6B5A44)
            return
        }
        val others = view.nations.filter { it.id != me.id }
        others.drop(scroll).take(visible).forEachIndexed { k, n ->
            val y = cy + 14 + k * rowH
            val sel = selected == n.id
            g.fill(cx, y, cx + 208, y + rowH - 2, Ui.argb(if (sel) 0xE8C252 else if (n.atWar) 0xC83A2A else 0x000000, if (sel) 110 else if (n.atWar) 70 else 25))
            g.item(NationMenu.bannerOf(n.dye), cx + 2, y + 2)
            g.text(font, Component.literal(n.name), cx + 20, y + 2, Ui.argb(Ui.readable(n.color)), false)
            opinionBar(g, cx + 20, y + 12, 90, n.opinion)
            var ix = cx + 116
            for (t in n.treaties.filter { it != Treaty.TRUCE }) {
                drawIcon(g, ItemStack(NationMenu.treatyIcon(t)), ix + 5, y + 10, 10)
                ix += 12
            }
            if (n.atWar) drawIcon(g, ItemStack(Items.IRON_SWORD), cx + 196, y + 10, 12)
        }
        val target = others.firstOrNull { it.id == selected } ?: return ink(g, "나라를 고른다", cx + 214, cy + 20, 0x6B5A44)
        val dx = cx + 214
        g.item(NationMenu.bannerOf(target.dye), dx, cy + 12)
        g.text(font, Component.literal(target.name), dx + 18, cy + 16, Ui.argb(Ui.readable(target.color)), false)
        ink(g, "통치자 ${target.ruler} · ${target.posture.label}", dx, cy + 32, 0x6B5A44)
        ink(g, "영토 ${target.claims} · 인구 ${target.population}", dx, cy + 44)
        ink(g, "병력 ${target.military} · 전투력 ${NationMenu.number(target.strength)}", dx, cy + 56)
        ink(g, "호감도 ${target.opinion.toInt()} · 국경 ${target.border}", dx, cy + 68)
        if (target.atWar) ink(g, "전쟁 중 · 점수 ${target.warScore.toInt()}", dx, cy + 80, 0xA02A1A)
    }

    private fun opinionBar(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, opinion: Double) {
        g.fill(x, y, x + w, y + 5, Ui.argb(0x2A1C10))
        val mid = x + w / 2
        val len = ((w / 2 - 1) * (opinion / 100.0).coerceIn(-1.0, 1.0)).roundToInt()
        if (len > 0) g.fill(mid, y + 1, mid + len, y + 4, Ui.argb(0x4AB04A)) else if (len < 0) g.fill(mid + len, y + 1, mid, y + 4, Ui.argb(0xC83A2A))
        g.fill(mid, y, mid + 1, y + 5, Ui.argb(0xE8C252))
    }

    private fun drawResearch(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val n = view.nation ?: return
        val (cardW, cardH) = (cw - 6) / 4 to RESEARCH_CARD_H
        TechBranch.entries.forEachIndexed { k, t ->
            val x = cx + (k % 4) * (cardW + 2)
            val y = cy + 4 + (k / 4) * (cardH + 4)
            val focus = n.researchFocus == t
            val hover = mouseX in x until x + cardW && mouseY in y until y + cardH
            g.fill(x, y, x + cardW, y + cardH, Ui.argb(if (focus) 0xE8C252 else 0x000000, if (focus) 90 else if (hover) 45 else 22))
            if (focus) {
                g.fill(x, y, x + cardW, y + 1, Ui.argb(0xB08A2A)); g.fill(x, y + cardH - 1, x + cardW, y + cardH, Ui.argb(0xB08A2A))
            }
            Ui.slot(g, x + 3, y + 3, NationMenu.techIcon(t), focus)
            ink(g, t.label, x + 24, y + 4)
            ink(g, "Lv ${n.tech[t] ?: 0}", x + 24, y + 14, 0x6B5A44)
            val effect = font.plainSubstrByWidth(t.effect, cardW - 6)
            ink(g, effect, x + 3, y + 24, 0x5A4A34)
            if (focus) Ui.bar(g, x + 3, y + cardH - 11, cardW - 6, n.researchProgress / n.researchCost.coerceAtLeast(1.0), 0x8A5AD0)
            if (hover && !focus) g.setTooltipForNextFrame(font, Component.literal("${t.label}: 레벨당 ${t.effect} — 클릭: 연구"), mouseX, mouseY)
        }
        val rows = (TechBranch.entries.size + 3) / 4
        heading(g, "연구 속도 ${NationMenu.number(n.science)}/턴 · 다음 단계까지 ${(n.researchCost - n.researchProgress).toInt()}", cx, cy + 6 + rows * (RESEARCH_CARD_H + 4))
        heading(g, "시설 (작업 지정 모드로 직접 지을 수도 있다)", cx, cy + ch - 34)
    }

    private fun drawLog(g: GuiGraphicsExtractor) {
        heading(g, "최근 사건", cx, cy)
        var y = cy + 14
        for (e in view.events.drop(scroll)) {
            if (y > cy + ch - 12) break
            val icon = when (e.kind) {
                EventKind.MAJOR -> Items.BOOK
                EventKind.WAR -> Items.IRON_SWORD
                EventKind.DIPLOMACY -> Items.WRITABLE_BOOK
                EventKind.ECONOMY -> Items.GOLD_NUGGET
                EventKind.INFO -> Items.PAPER
            }
            drawIcon(g, ItemStack(icon), cx + 6, y + 4, 10)
            ink(g, "${e.turn}", cx + 14, y, 0x8A6A3A)
            val lines = font.split(Component.literal(e.text), cw - 50)
            for (line in lines) {
                g.text(font, line, cx + 44, y, Ui.argb(if (e.kind == EventKind.MAJOR) 0x5A2A0A else Ui.INK), false)
                y += 10
            }
            y += 2
        }
    }
}
