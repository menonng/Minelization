package com.grandstrategy.mc.gui

import com.grandstrategy.mc.Deposit
import com.grandstrategy.mc.Found
import com.grandstrategy.mc.Runtime
import com.grandstrategy.mc.Texts
import com.grandstrategy.sim.Action
import com.grandstrategy.sim.Actions
import com.grandstrategy.sim.Balance
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Economy
import com.grandstrategy.sim.EventKind
import com.grandstrategy.sim.Facility
import com.grandstrategy.sim.Names
import com.grandstrategy.sim.Nation
import com.grandstrategy.sim.Outcome
import com.grandstrategy.sim.Policy
import com.grandstrategy.sim.Resource
import com.grandstrategy.sim.Settlements
import com.grandstrategy.sim.TechBranch
import com.grandstrategy.sim.Terrain
import com.grandstrategy.sim.Treaty
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.Item
import net.minecraft.world.item.Items

object Icons {
    fun resource(r: Resource): Item = when (r) {
        Resource.FOOD -> Items.WHEAT
        Resource.WOOD -> Items.OAK_LOG
        Resource.STONE -> Items.COBBLESTONE
        Resource.IRON -> Items.IRON_INGOT
        Resource.GOLD -> Items.GOLD_INGOT
    }

    fun facility(f: Facility): Item = when (f) {
        Facility.FARM -> Items.COMPOSTER
        Facility.LUMBER_CAMP -> Items.IRON_AXE
        Facility.QUARRY -> Items.STONECUTTER
        Facility.MINE -> Items.BLAST_FURNACE
        Facility.MARKET -> Items.EMERALD
        Facility.LIBRARY -> Items.LECTERN
        Facility.BARRACKS -> Items.SHIELD
        Facility.WALLS -> Items.STONE_BRICK_WALL
    }

    fun tech(t: TechBranch): Item = when (t) {
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

    fun policy(p: Policy): Item = when (p) {
        Policy.BALANCED -> Items.COMPASS
        Policy.ECONOMY -> Items.EMERALD
        Policy.MILITARY -> Items.CROSSBOW
        Policy.SCIENCE -> Items.ENCHANTING_TABLE
        Policy.EXPANSION -> Items.OAK_SAPLING
    }

    fun terrain(t: Terrain): Item = when (t) {
        Terrain.PLAINS -> Items.GRASS_BLOCK
        Terrain.FOREST -> Items.OAK_SAPLING
        Terrain.TAIGA -> Items.SPRUCE_SAPLING
        Terrain.JUNGLE -> Items.JUNGLE_SAPLING
        Terrain.SAVANNA -> Items.ACACIA_SAPLING
        Terrain.DESERT -> Items.SAND
        Terrain.BADLANDS -> Items.RED_SAND
        Terrain.HILLS -> Items.ANDESITE
        Terrain.MOUNTAIN -> Items.STONE
        Terrain.SNOWY -> Items.SNOW_BLOCK
        Terrain.RIVER -> Items.KELP
        Terrain.COAST -> Items.SANDSTONE
        Terrain.WATER -> Items.WATER_BUCKET
    }

    fun wool(nation: Nation): Item = Items.WOOL.pick(Texts.dye(nation))
    fun glass(nation: Nation): Item = Items.STAINED_GLASS_PANE.pick(Texts.dye(nation))
    fun banner(nation: Nation): Item = Items.BANNER.pick(Texts.dye(nation))
}

private fun feedback(player: ServerPlayer, outcome: Outcome) {
    player.sendSystemMessage(Texts.outcome(outcome))
}

private fun costLines(cost: Map<Resource, Double>, nation: Nation): List<Component> = cost.map { (r, v) ->
    val enough = nation.has(r) >= v
    Screen.line("  ${r.label} ${"%.0f".format(v)} (보유 ${Texts.number(nation.has(r))})", if (enough) ChatFormatting.GRAY else ChatFormatting.RED)
}

class NationScreen(player: ServerPlayer, private val rt: Runtime) : Screen(player, 6) {
    override fun title(): Component = rt.citizenNation(player)?.let { Component.literal(it.name) } ?: Component.literal("국가")

    override fun build() {
        val nation = rt.citizenNation(player) ?: return buildStateless()
        val ruler = nation.ruler == player.uuid.toString()
        val sim = rt.sim

        set(4, icon(Icons.banner(nation), Texts.nation(nation), listOf(
            line("통치자: ${nation.rulerName ?: "AI"}${if (nation.autoGovern) " (자동 통치)" else ""}"),
            line("수도: ${Chunks.format(nation.capital)}"),
            line("영토: ${nation.claims.size} 청크 (과확장 한도 ${Balance.overextensionLimit(nation)})"),
            line("자세: ${nation.posture.label}"),
            line("건국: ${nation.foundedTurn}턴 · 현재 ${rt.state.turn}턴", ChatFormatting.DARK_GRAY)
        )))

        val storage = Balance.storageCap(nation)
        Resource.entries.forEachIndexed { i, r ->
            val net = nation.lastIncome[r] ?: 0.0
            set(10 + i, icon(Icons.resource(r), Texts.plain(r.label, ChatFormatting.WHITE), listOf(
                line("비축 ${Texts.number(nation.has(r))} / ${Texts.number(storage)}"),
                line("턴당 ${Texts.signed(net)}", if (net >= 0) ChatFormatting.GREEN else ChatFormatting.RED),
                line("손에 들고 납부하기: /gs deposit", ChatFormatting.DARK_GRAY)
            ), count = (nation.has(r) / 10).toInt()))
        }

        val housing = Economy.housing(sim, nation)
        set(16, icon(Items.BED.red(), Texts.plain("인구", ChatFormatting.WHITE), listOf(
            line("${Texts.number(nation.population)} / 주거 ${Texts.number(housing)}"),
            line("식량 소비 턴당 ${Texts.number(nation.population * Balance.FOOD_PER_POP)}")
        )))

        val recruitCost = Balance.recruitCost(nation, 5)
        set(19, icon(Items.IRON_SWORD, Texts.plain("군대", ChatFormatting.WHITE), buildList {
            add(line("병력 ${nation.military.toInt()} / 한도 ${Balance.recruitCap(nation).toInt()}"))
            add(line("전투력 ${Texts.number(sim.strength(nation))}"))
            val wars = rt.state.warsOf(nation.id)
            if (wars.isNotEmpty()) add(line("전쟁 ${wars.size}건 진행 중", ChatFormatting.RED))
            if (ruler) {
                add(line(""))
                add(line("클릭: 5명 징집", ChatFormatting.YELLOW))
                addAll(costLines(recruitCost, nation))
            }
        })) { if (ruler) { feedback(player, Actions.execute(sim, nation, Action.Recruit(5))); refresh() } }

        val stabilityColor = when {
            nation.stability >= 60 -> ChatFormatting.GREEN
            nation.stability >= 30 -> ChatFormatting.YELLOW
            else -> ChatFormatting.RED
        }
        set(21, icon(Items.BELL, Texts.plain("안정도", ChatFormatting.WHITE), listOf(
            line("${nation.stability.toInt()} / 100", stabilityColor),
            line("25 미만: 생산 -25%, 12 미만: 내전 위험", ChatFormatting.DARK_GRAY)
        )))

        set(23, icon(Icons.policy(nation.policy), Texts.plain("정책: ${nation.policy.label}", ChatFormatting.WHITE), listOf(
            line(nation.policy.description),
            line(if (ruler) "클릭: 다음 정책" else "", ChatFormatting.YELLOW)
        ))) {
            if (ruler) {
                val next = Policy.entries[(nation.policy.ordinal + 1) % Policy.entries.size]
                feedback(player, Actions.execute(sim, nation, Action.SetPolicy(next)))
                refresh()
            }
        }

        val cost = Balance.techCost(nation.level(nation.researchFocus))
        set(25, icon(Items.ENCHANTED_BOOK, Texts.plain("연구: ${nation.researchFocus.label}", ChatFormatting.WHITE), listOf(
            line("${nation.level(nation.researchFocus)}단계 → ${nation.level(nation.researchFocus) + 1}단계"),
            line("진행 ${nation.researchProgress.toInt()} / ${cost.toInt()} (턴당 ${Texts.number(nation.lastScience)})")
        )))

        set(38, icon(Items.MAP, Texts.plain("지도", ChatFormatting.AQUA), listOf(line("주변 청크와 국경")))) { navigate(MapScreen(player, rt)) }
        set(39, icon(Items.BOOKSHELF, Texts.plain("기술 연구", ChatFormatting.AQUA))) { navigate(ResearchScreen(player, rt)) }
        set(40, icon(Items.CRAFTING_TABLE, Texts.plain("건설", ChatFormatting.AQUA), listOf(line("시설 ${nation.totalFacilities()} / ${nation.claims.size}")))) { navigate(BuildScreen(player, rt)) }
        set(41, icon(Items.BANNER.white(), Texts.plain("외교", ChatFormatting.AQUA))) { navigate(DiplomacyScreen(player, rt)) }
        set(42, icon(Items.PAPER, Texts.plain("사건 기록", ChatFormatting.AQUA))) { navigate(LogScreen(player, rt, nation.id)) }

        if (ruler) {
            val here = player.chunkPosition().pack()
            val error = Actions.claimError(sim, nation, here)
            set(48, icon(Items.GRASS_BLOCK, Texts.plain("지금 선 청크 편입", ChatFormatting.YELLOW), buildList {
                add(line("${Chunks.format(here)} · ${sim.terrain(here).label}"))
                if (error != null) add(line(error, ChatFormatting.RED)) else addAll(costLines(Balance.claimCost(nation), nation))
            })) { feedback(player, Actions.execute(sim, nation, Action.Claim(here))); refresh() }
            set(50, icon(Items.COMPARATOR, Texts.plain(if (nation.autoGovern) "자동 통치 끄기" else "자동 통치 켜기", ChatFormatting.YELLOW), listOf(
                line("켜면 AI가 대신 나라를 운영한다")
            ))) { nation.autoGovern = !nation.autoGovern; refresh() }
        }
        set(49, icon(Items.HOPPER, Texts.plain("손에 든 아이템 납부", ChatFormatting.YELLOW), listOf(
            line("밀·빵·감자 → 식량, 원목·판자 → 목재"),
            line("조약돌·돌 → 석재, 철 → 철, 금·에메랄드 → 금")
        ))) { feedback(player, Deposit.fromHand(rt, player, nation)); refresh() }
    }

    private fun buildStateless() {
        set(4, icon(Items.BANNER.white(), Texts.plain("소속 국가 없음", ChatFormatting.WHITE), listOf(
            line("나라를 고르거나 직접 세운다")
        )))
        rt.state.alive().take(36).forEachIndexed { i, n ->
            set(9 + i, icon(Icons.wool(n), Texts.nation(n), listOf(
                line("영토 ${n.claims.size} · 인구 ${n.population.toInt()} · 병력 ${n.military.toInt()}"),
                line("통치자: ${n.rulerName ?: "없음 (AI)"}"),
                line(""),
                line(if (n.ruler == null) "좌클릭: 통치자가 된다" else "좌클릭: 국민으로 합류", ChatFormatting.YELLOW),
                line("우클릭: 국민으로 합류", ChatFormatting.YELLOW)
            ))) { click ->
                val uuid = player.uuid.toString()
                rt.state.alive().forEach { it.citizens.remove(uuid) }
                n.citizens += uuid
                if (click == Click.LEFT && n.ruler == null) {
                    n.ruler = uuid
                    n.rulerName = player.gameProfile.name()
                    n.autoGovern = false
                    rt.sim.log(EventKind.MAJOR, "${player.gameProfile.name()} 님이 ${n.name}의 통치자가 되었다", n.id)
                } else {
                    feedback(player, Outcome.ok("${n.name}의 국민이 되었다."))
                }
                refresh()
            }
        }
        val here = player.chunkPosition().pack()
        val error = rt.sim.foundingError(here)
        set(49, icon(Items.GRASS_BLOCK, Texts.plain("여기에 건국", ChatFormatting.YELLOW), listOf(
            line("${Chunks.format(here)} · ${rt.sim.terrain(here).label}"),
            line(error ?: "무작위 이름으로 세운다. 이름은 /gs rename 으로 바꾼다", if (error == null) ChatFormatting.GRAY else ChatFormatting.RED)
        ))) {
            if (error == null) {
                Found.found(rt, player, Names.nation(rt.sim.rng))
            } else {
                feedback(player, Outcome.fail(error))
            }
            refresh()
        }
    }
}

class MapScreen(player: ServerPlayer, private val rt: Runtime) : Screen(player, 6) {
    override fun title(): Component = Component.literal("지도")

    override fun build() {
        val sim = rt.sim
        val me = rt.citizenNation(player)
        val ruler = me != null && me.ruler == player.uuid.toString()
        val here = player.chunkPosition()
        for (row in 0 until 5) for (col in 0 until 9) {
            val chunk = Chunks.pack(here.x + col - 4, here.z + row - 2)
            val owner = sim.ownerOf(chunk)
            val settlement = sim.settlementAt(chunk)
            val terrain = sim.terrain(chunk)
            val center = row == 2 && col == 4
            val item = when {
                settlement != null && settlement.center == chunk -> Items.BELL
                owner != null && owner.capital == chunk -> Icons.banner(owner)
                owner != null -> Icons.glass(owner)
                settlement != null -> Items.STAINED_GLASS_PANE.lime()
                else -> Icons.terrain(terrain)
            }
            val title = when {
                owner != null -> Texts.nation(owner)
                settlement != null -> Texts.plain("자유 마을 ${settlement.name}", ChatFormatting.GREEN)
                else -> Texts.plain("무주지", ChatFormatting.GRAY)
            }
            val lore = buildList {
                add(line("${Chunks.format(chunk)} · ${terrain.label}"))
                if (settlement != null) add(line("마을 ${settlement.name} · 인구 ${settlement.population.toInt()} · 방어 ${settlement.defense.toInt()}"))
                if (center) add(line("▶ 현재 위치", ChatFormatting.YELLOW))
                if (ruler && me != null) {
                    val err = Actions.claimError(sim, me, chunk)
                    if (err == null) {
                        add(line("클릭: 이 청크 편입", ChatFormatting.YELLOW))
                        addAll(costLines(Balance.claimCost(me), me))
                    }
                }
            }
            set(row * 9 + col, icon(item, title, lore, glint = center)) {
                if (ruler && me != null) {
                    feedback(player, Actions.execute(sim, me, Action.Claim(chunk)))
                    refresh()
                }
            }
        }
        set(45, icon(Items.ARROW, Texts.plain("돌아가기", ChatFormatting.WHITE))) { navigate(NationScreen(player, rt)) }
        set(49, icon(Items.COMPASS, Texts.plain("범례", ChatFormatting.WHITE), listOf(
            line("색유리: 국가 영토, 깃발: 수도"),
            line("종: 마을 중심, 연두 유리: 자유 마을 땅"),
            line("그 밖: 무주지 지형")
        )))
    }
}

class ResearchScreen(player: ServerPlayer, private val rt: Runtime) : Screen(player, 3) {
    override fun title(): Component = Component.literal("기술 연구")

    override fun build() {
        val nation = rt.citizenNation(player) ?: return
        val ruler = nation.ruler == player.uuid.toString()
        TechBranch.entries.forEachIndexed { i, t ->
            val focus = nation.researchFocus == t
            val level = nation.level(t)
            set(9 + i, icon(Icons.tech(t), Texts.plain("${t.label} ${level}단계", if (focus) ChatFormatting.GOLD else ChatFormatting.WHITE), buildList {
                add(line("레벨당 ${t.effect}"))
                add(line("다음 단계 ${Balance.techCost(level).toInt()} 연구점"))
                if (focus) add(line("연구 중: ${nation.researchProgress.toInt()}", ChatFormatting.GOLD))
                else if (ruler) add(line("클릭: 이 분야 연구", ChatFormatting.YELLOW))
            }, glint = focus, count = level.coerceAtLeast(1))) {
                if (ruler) { feedback(player, Actions.execute(rt.sim, nation, Action.Research(t))); refresh() }
            }
        }
        set(18, icon(Items.ARROW, Texts.plain("돌아가기", ChatFormatting.WHITE))) { navigate(NationScreen(player, rt)) }
    }
}

class BuildScreen(player: ServerPlayer, private val rt: Runtime) : Screen(player, 4) {
    override fun title(): Component = Component.literal("건설과 정책")

    override fun build() {
        val nation = rt.citizenNation(player) ?: return
        val ruler = nation.ruler == player.uuid.toString()
        Facility.entries.forEachIndexed { i, f ->
            val effect = when (f) {
                Facility.LIBRARY -> "연구 +${Balance.SCIENCE_PER_LIBRARY.toInt()}/턴"
                Facility.BARRACKS -> "전투력 +5%, 징병 비용 -5%"
                Facility.WALLS -> "방어 시 전투력 +10%"
                else -> "${f.boosts?.label} 생산 +${(Balance.FACILITY_BONUS * 100).toInt()}%"
            }
            set(9 + i, icon(Icons.facility(f), Texts.plain("${f.label} ×${nation.count(f)}", ChatFormatting.WHITE), buildList {
                add(line(effect))
                if (ruler) {
                    add(line("클릭: 건설", ChatFormatting.YELLOW))
                    addAll(costLines(Balance.facilityCost(nation, f), nation))
                }
            }, count = nation.count(f).coerceAtLeast(1))) {
                if (ruler) { feedback(player, Actions.execute(rt.sim, nation, Action.Build(f))); refresh() }
            }
        }
        Policy.entries.forEachIndexed { i, p ->
            val active = nation.policy == p
            set(20 + i, icon(Icons.policy(p), Texts.plain("정책: ${p.label}", if (active) ChatFormatting.GOLD else ChatFormatting.WHITE), listOf(
                line(p.description),
                line(if (active) "시행 중" else if (ruler) "클릭: 시행" else "", if (active) ChatFormatting.GOLD else ChatFormatting.YELLOW)
            ), glint = active)) {
                if (ruler) { feedback(player, Actions.execute(rt.sim, nation, Action.SetPolicy(p))); refresh() }
            }
        }
        set(27, icon(Items.ARROW, Texts.plain("돌아가기", ChatFormatting.WHITE))) { navigate(NationScreen(player, rt)) }
    }
}

class DiplomacyScreen(player: ServerPlayer, private val rt: Runtime) : Screen(player, 6) {
    override fun title(): Component = Component.literal("외교")

    override fun build() {
        val me = rt.citizenNation(player) ?: return
        val state = rt.state
        state.alive().filter { it.id != me.id }.take(36).forEachIndexed { i, n ->
            val r = state.relation(me.id, n.id)
            val war = state.warBetween(me.id, n.id)
            val treaties = r.treaties.keys.filter { r.has(it, state.turn) }.joinToString(", ") { it.label }
            set(i, icon(Icons.wool(n), Texts.nation(n), buildList {
                add(line("호감도 ${r.opinion.toInt()}", if (r.opinion >= 0) ChatFormatting.GREEN else ChatFormatting.RED))
                add(line("조약: ${treaties.ifEmpty { "없음" }}"))
                if (war != null) add(line("전쟁 중 · 우리 점수 ${war.scoreFor(me.id).toInt()}", ChatFormatting.RED))
                add(line("영토 ${n.claims.size} · 전투력 ${Texts.number(rt.sim.strength(n))} · 국경 ${rt.sim.borderLength(me.id, n.id)}"))
                add(line("통치자: ${n.rulerName ?: "AI"} · 자세 ${n.posture.label}"))
                add(line("클릭: 외교 행동", ChatFormatting.YELLOW))
            }, glint = war != null)) { navigate(RelationScreen(player, rt, n.id)) }
        }
        val towns = state.settlements.values.count { it.owner == null && Settlements.inRange(me, it) }
        set(49, icon(Items.BELL, Texts.plain("자유 마을", ChatFormatting.GREEN), listOf(line("교역 범위 안 ${towns}곳")))) { navigate(SettlementScreen(player, rt)) }
        set(45, icon(Items.ARROW, Texts.plain("돌아가기", ChatFormatting.WHITE))) { navigate(NationScreen(player, rt)) }
    }
}

class RelationScreen(player: ServerPlayer, private val rt: Runtime, private val targetId: Int) : Screen(player, 3) {
    override fun title(): Component = rt.state.nation(targetId)?.let { Component.literal(it.name) } ?: Component.literal("외교")

    override fun build() {
        val me = rt.citizenNation(player) ?: return
        val target = rt.state.nation(targetId) ?: return
        val ruler = me.ruler == player.uuid.toString()
        val r = rt.state.relation(me.id, target.id)
        set(4, icon(Icons.banner(target), Texts.nation(target), listOf(line("호감도 ${r.opinion.toInt()}"))))
        val treatyIcons = mapOf(Treaty.NON_AGGRESSION to Items.SHIELD, Treaty.TRADE to Items.EMERALD, Treaty.ALLIANCE to Items.GOLDEN_APPLE)
        var slot = 10
        for ((treaty, item) in treatyIcons) {
            val has = r.has(treaty, rt.state.turn)
            set(slot, icon(item, Texts.plain("${treaty.label} 조약", if (has) ChatFormatting.GOLD else ChatFormatting.WHITE), listOf(
                line(if (has) "체결됨 · 우클릭: 파기" else "좌클릭: 제안", ChatFormatting.YELLOW)
            ), glint = has)) { click ->
                if (!ruler) return@set
                val action = if (has && click == Click.RIGHT) Action.CancelTreaty(target.id, treaty) else Action.ProposeTreaty(target.id, treaty)
                feedback(player, Actions.execute(rt.sim, me, action))
                rt.notifyProposals()
                refresh()
            }
            slot++
        }
        val war = rt.state.warBetween(me.id, target.id)
        if (war == null) {
            set(15, icon(Items.TNT, Texts.plain("선전포고", ChatFormatting.RED), listOf(
                line("국경이 맞닿아 있어야 한다"),
                line("불가침을 깨면 모든 나라의 호감도 -15", ChatFormatting.DARK_GRAY),
                line("쉬프트 클릭: 선전포고", ChatFormatting.YELLOW)
            ))) { click ->
                if (ruler && click == Click.SHIFT) { feedback(player, Actions.execute(rt.sim, me, Action.DeclareWar(target.id))); refresh() }
            }
        } else {
            set(15, icon(Items.BANNER.white(), Texts.plain("평화 제안", ChatFormatting.WHITE), listOf(
                line("우리 점수 ${war.scoreFor(me.id).toInt()} · 피로도 ${war.exhaustionOf(me.id).toInt()}"),
                line("클릭: 평화 제안", ChatFormatting.YELLOW)
            ))) {
                if (ruler) { feedback(player, Actions.execute(rt.sim, me, Action.ProposePeace(target.id))); rt.notifyProposals(); refresh() }
            }
        }
        set(18, icon(Items.ARROW, Texts.plain("돌아가기", ChatFormatting.WHITE))) { navigate(DiplomacyScreen(player, rt)) }
    }
}

class SettlementScreen(player: ServerPlayer, private val rt: Runtime) : Screen(player, 6) {
    override fun title(): Component = Component.literal("자유 마을")

    override fun build() {
        val me = rt.citizenNation(player) ?: return
        val ruler = me.ruler == player.uuid.toString()
        val towns = rt.state.settlements.values.sortedBy { Settlements.distanceTo(me, it) }.take(45)
        towns.forEachIndexed { i, s ->
            val owner = rt.state.nation(s.owner)
            val influence = s.influence[me.id] ?: 0.0
            set(i, icon(if (owner == null) Items.BELL else Icons.banner(owner), Texts.plain(s.name, if (owner == null) ChatFormatting.GREEN else ChatFormatting.WHITE), buildList {
                add(line("${Chunks.format(s.center)} · 거리 ${Settlements.distanceTo(me, s)}청크"))
                add(line("인구 ${s.population.toInt()} · 골렘 ${s.golems} · 방어 ${s.defense.toInt()}${if (s.observed) "" else " (추정)"}"))
                if (owner != null) {
                    add(line("${owner.name}의 도시 · 충성도 ${s.loyalty.toInt()}"))
                } else {
                    add(line("우리 영향력 ${influence.toInt()} / 100"))
                    if (ruler) {
                        add(line("좌클릭: 사절 (금 ${Balance.ENVOY_COST.toInt()}, 영향력 +${Balance.ENVOY_INFLUENCE.toInt()})", ChatFormatting.YELLOW))
                        add(line("쉬프트 클릭: 무력 정복 (전투력 ${(s.defense * 2).toInt()} 필요)", ChatFormatting.RED))
                    }
                }
            })) { click ->
                if (!ruler || owner != null) return@set
                val action = if (click == Click.SHIFT) Action.Conquer(s.id) else Action.Envoy(s.id)
                feedback(player, Actions.execute(rt.sim, me, action))
                refresh()
            }
        }
        set(45, icon(Items.ARROW, Texts.plain("돌아가기", ChatFormatting.WHITE))) { navigate(DiplomacyScreen(player, rt)) }
    }
}

class LogScreen(player: ServerPlayer, private val rt: Runtime, private val nationId: Int?) : Screen(player, 6) {
    override fun title(): Component = Component.literal("사건 기록")

    override fun build() {
        val events = rt.state.events.asReversed()
            .filter { nationId == null || nationId in it.nations || it.kind == EventKind.MAJOR }
            .take(45)
        events.forEachIndexed { i, e ->
            val item = when (e.kind) {
                EventKind.MAJOR -> Items.BOOK
                EventKind.WAR -> Items.IRON_SWORD
                EventKind.DIPLOMACY -> Items.WRITABLE_BOOK
                EventKind.ECONOMY -> Items.GOLD_NUGGET
                EventKind.INFO -> Items.PAPER
            }
            val wrapped = e.text.chunked(28).map { line(it, ChatFormatting.WHITE) }
            set(i, icon(item, Texts.plain("${e.turn}턴", ChatFormatting.YELLOW), wrapped))
        }
        set(45, icon(Items.ARROW, Texts.plain("돌아가기", ChatFormatting.WHITE))) { navigate(NationScreen(player, rt)) }
        set(53, icon(Items.SPYGLASS, Texts.plain(if (nationId == null) "우리 나라만 보기" else "세계 전체 보기", ChatFormatting.AQUA))) {
            val mine = rt.citizenNation(player)?.id
            navigate(LogScreen(player, rt, if (nationId == null) mine else null))
        }
    }
}
