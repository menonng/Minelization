package com.grandstrategy.mc.ui

import com.grandstrategy.mc.Found
import com.grandstrategy.mc.Runtime
import com.grandstrategy.mc.Texts
import com.grandstrategy.net.EventRow
import com.grandstrategy.net.MapFlags
import com.grandstrategy.net.MapTile
import com.grandstrategy.net.MenuAction
import com.grandstrategy.net.MenuOpen
import com.grandstrategy.net.MenuView
import com.grandstrategy.net.NationCard
import com.grandstrategy.net.NationRow
import com.grandstrategy.net.ProposalRow
import com.grandstrategy.net.ServerMessagePayload
import com.grandstrategy.net.ToClient
import com.grandstrategy.net.ToServer
import com.grandstrategy.net.TownRow
import com.grandstrategy.net.WorkJson
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
import com.grandstrategy.sim.Settlements
import com.grandstrategy.sim.TechBranch
import com.grandstrategy.sim.Treaty
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class MenuService(private val rt: Runtime) {
    private val open = HashMap<UUID, String>()
    private var ticks = 0L

    fun supports(player: ServerPlayer): Boolean = ServerPlayNetworking.canSend(player, ServerMessagePayload.TYPE)

    fun openFor(player: ServerPlayer, page: String = "overview"): Boolean {
        if (!supports(player)) return false
        open[player.uuid] = page
        send(player, view(player, page, true))
        return true
    }

    fun handle(player: ServerPlayer, message: ToServer) {
        when (message) {
            is MenuOpen -> openFor(player, message.page.ifEmpty { "overview" })
            is MenuAction -> {
                if (message.action == "close") {
                    open.remove(player.uuid)
                    return
                }
                if (message.action == "page") {
                    open[player.uuid] = message.a
                    return
                }
                val outcome = act(player, message)
                send(player, ToClient.Result(outcome.ok, outcome.message))
                rt.notifyProposals()
                send(player, view(player, open[player.uuid] ?: "overview", false))
            }
            else -> {}
        }
    }

    fun tick() {
        ticks++
        if (ticks % 40 != 0L || open.isEmpty()) return
        open.keys.retainAll(rt.server.playerList.players.map { it.uuid }.toSet())
        for ((uuid, page) in open) rt.server.playerList.getPlayer(uuid)?.let { send(it, view(it, page, false)) }
    }

    fun forget(uuid: UUID) {
        open.remove(uuid)
    }

    private fun act(player: ServerPlayer, m: MenuAction): Outcome {
        val sim = rt.sim
        val state = rt.state
        val uuid = player.uuid.toString()
        when (m.action) {
            "join" -> {
                val n = state.nation(m.a.toIntOrNull()) ?: return Outcome.fail("그런 나라가 없다.")
                if (state.rulerNation(uuid) != null) return Outcome.fail("통치자는 다른 나라에 합류할 수 없다. 먼저 물러난다.")
                state.alive().forEach { it.citizens.remove(uuid) }
                n.citizens += uuid
                return Outcome.ok("${n.name}의 국민이 되었다.")
            }
            "pick" -> {
                val n = state.nation(m.a.toIntOrNull()) ?: return Outcome.fail("그런 나라가 없다.")
                if (state.rulerNation(uuid) != null) return Outcome.fail("이미 나라를 다스리고 있다.")
                if (n.ruler != null) return Outcome.fail("${n.rulerName} 님이 다스리는 나라다.")
                state.alive().forEach { it.citizens.remove(uuid) }
                n.ruler = uuid
                n.rulerName = player.gameProfile.name()
                n.autoGovern = false
                n.citizens += uuid
                sim.log(EventKind.MAJOR, "${player.gameProfile.name()} 님이 ${n.name}의 통치자가 되었다", n.id)
                return Outcome.ok("${n.name}을(를) 맡았다.")
            }
            "found" -> return Found.found(rt, player, m.a.ifBlank { Names.nation(sim.rng) }.take(32))
            "mode" -> return rt.setup.setMode(player, m.a == "adventure")
            "setup" -> {
                if (!net.minecraft.commands.Commands.LEVEL_GAMEMASTERS.check(player.permissions())) return Outcome.fail("관리자만 정할 수 있다.")
                val tier = com.grandstrategy.mc.WorldTier.of(m.a.toIntOrNull() ?: 0) ?: return Outcome.fail("크기 오류")
                val flags = m.b.split(',')
                return rt.setup.apply(tier, "pregen" in flags, "god" in flags, player.gameProfile.name())
            }
            "leave" -> {
                state.rulerNation(uuid)?.let { n ->
                    n.ruler = null
                    n.rulerName = null
                    n.autoGovern = false
                    sim.log(EventKind.MAJOR, "${player.gameProfile.name()} 님이 ${n.name}의 통치에서 물러났다", n.id)
                }
                state.alive().forEach { it.citizens.remove(uuid) }
                return Outcome.ok("소속 국가를 떠났다.")
            }
        }
        val nation = rt.ruledNation(player) ?: return Outcome.fail("다스리는 나라가 없다.")
        return when (m.action) {
            "recruit" -> Actions.execute(sim, nation, Action.Recruit(m.a.toIntOrNull() ?: 5))
            "policy" -> enumOf<Policy>(m.a)?.let { Actions.execute(sim, nation, Action.SetPolicy(it)) } ?: Outcome.fail("정책 이름 오류")
            "research" -> enumOf<TechBranch>(m.a)?.let { Actions.execute(sim, nation, Action.Research(it)) } ?: Outcome.fail("분야 이름 오류")
            "build" -> enumOf<Facility>(m.a)?.let { Actions.execute(sim, nation, Action.Build(it)) } ?: Outcome.fail("시설 이름 오류")
            "auto" -> {
                nation.autoGovern = !nation.autoGovern
                Outcome.ok("자동 통치 ${if (nation.autoGovern) "켬" else "끔"}")
            }
            "claim" -> Actions.execute(sim, nation, Action.Claim(m.a.toLongOrNull() ?: return Outcome.fail("청크 오류")))
            "treaty" -> withTarget(m) { t -> enumOf<Treaty>(m.b)?.let { Actions.execute(sim, nation, Action.ProposeTreaty(t.id, it)) } ?: Outcome.fail("조약 오류") }
            "untreaty" -> withTarget(m) { t -> enumOf<Treaty>(m.b)?.let { Actions.execute(sim, nation, Action.CancelTreaty(t.id, it)) } ?: Outcome.fail("조약 오류") }
            "war" -> withTarget(m) { Actions.execute(sim, nation, Action.DeclareWar(it.id)) }
            "peace" -> withTarget(m) { Actions.execute(sim, nation, Action.ProposePeace(it.id)) }
            "envoy" -> Actions.execute(sim, nation, Action.Envoy(m.a.toIntOrNull() ?: -1))
            "conquer" -> Actions.execute(sim, nation, Action.Conquer(m.a.toIntOrNull() ?: -1))
            "accept" -> Actions.answer(sim, m.a.toIntOrNull() ?: -1, nation, true)
            "decline" -> Actions.answer(sim, m.a.toIntOrNull() ?: -1, nation, false)
            else -> Outcome.fail("알 수 없는 명령: ${m.action}")
        }
    }

    private inline fun withTarget(m: MenuAction, block: (Nation) -> Outcome): Outcome =
        rt.state.nation(m.a.toIntOrNull())?.let(block) ?: Outcome.fail("그런 나라가 없다.")

    private inline fun <reified E : Enum<E>> enumOf(name: String): E? = enumValues<E>().firstOrNull { it.name == name }

    fun view(player: ServerPlayer, page: String, opening: Boolean): MenuView {
        val sim = rt.sim
        val state = rt.state
        val mine = rt.citizenNation(player)
        val ruler = mine != null && mine.ruler == player.uuid.toString()
        val card = mine?.let { n ->
            NationCard(
                id = n.id, name = n.name, color = Texts.dye(n).textColor, dye = n.color, ruler = n.rulerName ?: "AI",
                autoGovern = n.autoGovern, capital = n.capital, claims = n.claims.size, claimLimit = Balance.overextensionLimit(n),
                population = n.population, housing = Economy.housing(sim, n), military = n.military, recruitCap = Balance.recruitCap(n),
                strength = sim.strength(n), stability = n.stability, policy = n.policy, posture = n.posture,
                researchFocus = n.researchFocus, researchProgress = n.researchProgress, researchCost = Balance.techCost(n.level(n.researchFocus)),
                science = n.lastScience, tech = n.tech.toMap(), facilities = n.facilities.toMap(), stock = n.stock.toMap(),
                income = n.lastIncome.toMap(), storage = Balance.storageCap(n), claimCost = Balance.claimCost(n),
                recruitCost = Balance.recruitCost(n, 5), wars = state.warsOf(n.id).size
            )
        }
        val nations = state.alive().filter { it.id != mine?.id }.map { n ->
            val r = mine?.let { state.relation(it.id, n.id) }
            val war = mine?.let { state.warBetween(it.id, n.id) }
            NationRow(
                id = n.id, name = n.name, color = Texts.dye(n).textColor, dye = n.color, claims = n.claims.size,
                population = n.population.toInt(), military = n.military.toInt(), strength = sim.strength(n), ruler = n.rulerName ?: "AI",
                posture = n.posture, opinion = r?.opinion ?: 0.0, treaties = r?.treaties?.keys?.filter { r.has(it, state.turn) } ?: emptyList(),
                atWar = war != null, warScore = mine?.let { war?.scoreFor(it.id) } ?: 0.0, exhaustion = mine?.let { war?.exhaustionOf(it.id) } ?: 0.0,
                border = mine?.let { sim.borderLength(it.id, n.id) } ?: 0
            )
        }.sortedWith(compareByDescending<NationRow> { it.atWar }.thenByDescending { it.border }.thenByDescending { it.claims })
        val towns = mine?.let { n ->
            state.settlements.values.sortedBy { Settlements.distanceTo(n, it) }.take(24).map { s ->
                TownRow(s.id, s.name, s.center, s.population.toInt(), s.golems, s.defense.toInt(), state.nation(s.owner)?.name ?: "",
                    (s.influence[n.id] ?: 0.0).toInt(), Settlements.distanceTo(n, s), s.observed)
            }
        } ?: emptyList()
        val here = player.chunkPosition().pack()
        val rx = 14
        val rz = 9
        val tiles = ArrayList<MapTile>()
        val buildingChunks = HashSet<Long>()
        for (b in state.buildings.values) for (c in b.box.chunks()) buildingChunks += c
        for (dz in -rz..rz) for (dx in -rx..rx) {
            val key = Chunks.pack(Chunks.x(here) + dx, Chunks.z(here) + dz)
            val owner = sim.ownerOf(key)
            val town = sim.settlementAt(key)
            var flags = 0
            if (owner != null && owner.capital == key) flags = flags or MapFlags.CAPITAL
            if (town != null) flags = flags or MapFlags.TOWN
            if (town != null && town.center == key) flags = flags or MapFlags.TOWN_CENTER
            if (key in buildingChunks) flags = flags or MapFlags.BUILDING
            tiles += MapTile(key, sim.terrain(key).ordinal, owner?.id ?: 0, flags)
        }
        val ownerRows = (nations + listOfNotNull(card?.let {
            NationRow(it.id, it.name, it.color, it.dye, it.claims, it.population.toInt(), it.military.toInt(), it.strength, it.ruler, it.posture)
        }))
        val events = state.events.asReversed().asSequence()
            .filter { mine == null || mine.id in it.nations || it.kind == EventKind.MAJOR }
            .take(40).map { EventRow(it.turn, it.kind, it.text) }.toList()
        val proposals = mine?.let { n ->
            state.proposals.values.filter { it.to == n.id }.mapNotNull { p ->
                val from = state.nation(p.from) ?: return@mapNotNull null
                val label = if (p.type == "peace") "평화" else (runCatching { Treaty.valueOf(p.type.removePrefix("treaty:")).label }.getOrDefault(p.type) + " 조약")
                ProposalRow(p.id, from.name, label)
            }
        } ?: emptyList()
        return MenuView(
            open = opening, page = page, turn = state.turn, ruler = ruler, nation = card, nations = ownerRows.filter { it.id != card?.id } + listOfNotNull(ownerRows.firstOrNull { it.id == card?.id }),
            towns = towns, mapCenter = here, mapRadiusX = rx, mapRadiusZ = rz, tiles = tiles, events = events, proposals = proposals,
            here = here, foundError = if (mine == null) sim.foundingError(here) else null
        )
    }

    private fun send(player: ServerPlayer, message: ToClient) {
        if (!supports(player)) return
        ServerPlayNetworking.send(player, ServerMessagePayload(WorkJson.json.encodeToString(ToClient.serializer(), message)))
    }
}
