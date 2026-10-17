package com.grandstrategy.mc.build

import com.grandstrategy.mc.Runtime
import com.grandstrategy.mc.Texts
import com.grandstrategy.net.BuildingView
import com.grandstrategy.net.ChunkView
import com.grandstrategy.net.OrderView
import com.grandstrategy.net.ServerMessagePayload
import com.grandstrategy.net.TemplateView
import com.grandstrategy.net.ToClient
import com.grandstrategy.net.ToServer
import com.grandstrategy.net.WorkJson
import com.grandstrategy.net.WorkTool
import com.grandstrategy.sim.Box
import com.grandstrategy.sim.Building
import com.grandstrategy.sim.Chunks
import com.grandstrategy.sim.Job
import com.grandstrategy.sim.Outcome
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerPlayer
import java.util.UUID

class WorkService(private val rt: Runtime, private val work: WorkManager) {
    private val active = HashSet<UUID>()
    private val sentTemplates = HashSet<UUID>()
    private var ticks = 0L

    fun handle(player: ServerPlayer, json: String) {
        val message = runCatching { WorkJson.json.decodeFromString(ToServer.serializer(), json) }.getOrNull() ?: return
        when (message) {
            is ToServer.Mode -> {
                if (message.on) active += player.uuid else active -= player.uuid
                if (message.on) sendInfo(player)
            }
            is ToServer.Order -> result(player, order(player, message))
            is ToServer.Select -> select(player, message)
            is ToServer.BuildingAction -> result(player, buildingAction(player, message))
            is ToServer.Cancel -> result(player, cancel(player, message.order))
            is com.grandstrategy.net.MenuOpen, is com.grandstrategy.net.MenuAction -> rt.menus.handle(player, message)
            is com.grandstrategy.net.MapOpen -> if (message.open) rt.map.open(player) else rt.map.close(player)
            is com.grandstrategy.net.MapRequest -> rt.map.request(player, message.keys, message.versions)
        }
    }

    private fun guard(player: ServerPlayer): Pair<com.grandstrategy.sim.Nation?, String?> {
        if (!player.isSpectator) return null to "작업 지정은 관전자 모드에서만 할 수 있다."
        val nation = rt.ruledNation(player) ?: return null to "다스리는 나라가 없다."
        return nation to null
    }

    private fun order(player: ServerPlayer, m: ToServer.Order): Outcome {
        val (nation, error) = guard(player)
        if (nation == null) return Outcome.fail(error!!)
        val box = m.box
        if (box.sizeX > 48 || box.sizeZ > 48) return Outcome.fail("한 번에 48×48까지 지정할 수 있다.")
        val by = player.uuid.toString()
        return when (m.tool) {
            WorkTool.BUILD -> {
                val info = work.templates.all.getOrNull(m.template) ?: return Outcome.fail("건물 종류를 고른다.")
                work.requestBuild(nation, info, m.rotation and 3, box, by, m.conscript)
            }
            WorkTool.WALLS -> work.requestWalls(nation, box, by, m.conscript)
            WorkTool.DEMOLISH -> work.requestDemolish(nation, box, by, m.conscript)
            WorkTool.FLATTEN -> work.requestFlatten(nation, box, by, m.conscript)
            WorkTool.ROAD -> if (m.remove) rt.roads.requestClear(nation, box, by, m.conscript) else rt.roads.requestManual(nation, box, by, m.conscript)
            WorkTool.SELECT -> Outcome.fail("선택 도구로는 작업을 지시할 수 없다.")
        }.also { if (it.ok) sendInfo(player) }
    }

    private fun select(player: ServerPlayer, m: ToServer.Select) {
        val level = rt.server.overworld()
        if (player.level() != level) return
        val known = work.buildingAt(m.x, m.y, m.z)
        val building = known ?: run {
            val box = Detector.detect(level, BlockPos(m.x, m.y, m.z)) ?: return send(player, ToClient.Selected(null, "건물이 아니다 (자연 지형)."))
            if (box.sizeX < 3 || box.sizeZ < 3 || box.sizeY < 2) return send(player, ToClient.Selected(null, "건물로 보기엔 너무 작다 (${box.sizeX}×${box.sizeY}×${box.sizeZ})."))
            work.registerDetected(box, rt.sim.ownerOf(Chunks.pack(m.x shr 4, m.z shr 4))?.id)
        }
        send(player, ToClient.Selected(view(building, player)))
    }

    private fun buildingAction(player: ServerPlayer, m: ToServer.BuildingAction): Outcome {
        val (nation, error) = guard(player)
        if (nation == null) return Outcome.fail(error!!)
        val building = rt.state.buildings[m.id] ?: return Outcome.fail("그런 건물이 없다.")
        if (building.nation == null && work.territoryError(nation, building.box) == null) building.nation = nation.id
        if (building.nation != nation.id) return Outcome.fail("우리 나라 건물이 아니다.")
        return when (m.action) {
            "demolish" -> work.requestDemolishBuilding(nation, building, player.uuid.toString(), m.conscript)
            "assign" -> work.assign(building, m.kind ?: return Outcome.fail("용도를 고른다."))
            "renovate" -> work.renovate(nation, building, m.kind ?: return Outcome.fail("용도를 고른다."), player.uuid.toString(), m.conscript)
            "forget" -> if (building.detected && rt.state.orders.values.none { it.building == building.id }) {
                building.kind.facility?.let { f -> nation.facilities[f] = (nation.count(f) - 1).coerceAtLeast(0) }
                rt.state.buildings.remove(building.id)
                Outcome.ok("건물 #${building.id} 인식을 해제했다 (블록은 그대로).")
            } else Outcome.fail("자동 인식한 건물만 해제할 수 있다.")
            else -> Outcome.fail("알 수 없는 작업")
        }.also { if (it.ok) sendInfo(player) }
    }

    private fun cancel(player: ServerPlayer, id: Int): Outcome {
        val (nation, error) = guard(player)
        if (nation == null) return Outcome.fail(error!!)
        val order = rt.state.orders[id] ?: return Outcome.fail("그런 작업이 없다.")
        if (order.nation != nation.id) return Outcome.fail("우리 작업이 아니다.")
        work.cancel(order)
        return Outcome.ok("작업 #$id 취소").also { sendInfo(player) }
    }

    fun tick() {
        ticks++
        if (ticks % 20 != 0L || active.isEmpty()) return
        active.retainAll(rt.server.playerList.players.map { it.uuid }.toSet())
        for (uuid in active) rt.server.playerList.getPlayer(uuid)?.let { sendInfo(it) }
    }

    fun forget(uuid: UUID) {
        active -= uuid
        sentTemplates -= uuid
    }

    private fun view(b: Building, player: ServerPlayer): BuildingView {
        val mine = rt.ruledNation(player)?.id ?: rt.citizenNation(player)?.id
        val theme = work.templates.styleAt(rt.server.overworld(), b.box.centerX.toInt(), b.box.centerZ.toInt())
        return BuildingView(b.id, b.box, b.kind, b.nation != null && b.nation == mine, b.complete, rt.state.nation(b.nation)?.name ?: "", b.detected, theme, work.facingRoad(b.box))
    }

    private fun sendInfo(player: ServerPlayer) {
        if (sentTemplates.add(player.uuid)) {
            send(player, ToClient.Templates(work.templates.all.map { TemplateView(it.index, it.name, it.style, it.kind, it.sizeX, it.sizeY, it.sizeZ) }))
        }
        val nation = rt.ruledNation(player) ?: rt.citizenNation(player)
        val center = player.chunkPosition()
        val chunks = ArrayList<ChunkView>()
        for (dx in -8..8) for (dz in -8..8) {
            val key = Chunks.pack(center.x + dx, center.z + dz)
            val owner = rt.sim.ownerOf(key)
            val town = rt.sim.settlementAt(key)
            val color = when {
                owner != null -> Texts.dye(owner).textColor
                town != null -> 0x55FF55
                else -> -1
            }
            if (color != -1) chunks += ChunkView(key, color, owner != null && owner.id == nation?.id)
        }
        val near = Box(player.blockX - 160, -64, player.blockZ - 160, player.blockX + 160, 320, player.blockZ + 160)
        val buildings = rt.state.buildings.values.filter { it.box.intersects(near) }.take(200).map { view(it, player) }
        val orders = rt.state.orders.values.filter { it.box.intersects(near) }.take(100).map {
            val present = work.workers(it, rt.state.nation(it.nation) ?: return@map null).toInt()
            OrderView(it.id, it.type, it.box, it.progress, it.total, maxOf(work.crewSize(it.id), present), present, it.kind)
        }.filterNotNull()
        val agents = rt.population.agents
        var builders = 0
        var idle = 0
        if (nation != null) {
            agents.forEachAlive { i ->
                if (agents.nation[i] != nation.id) return@forEachAlive
                val job = Job.entries[agents.job[i].toInt()]
                if (job == Job.BUILDER) builders++
                if (agents.task[i] == 0 && job != Job.SOLDIER && job != Job.CHILD) idle++
            }
        }
        send(player, ToClient.Info(
            nation = nation?.name ?: "",
            color = nation?.let { Texts.dye(it).textColor } ?: 0xFFFFFF,
            ruler = nation != null && nation.ruler == player.uuid.toString(),
            spectator = player.isSpectator,
            stock = nation?.stock?.mapKeys { it.key.label }?.mapValues { it.value.toInt() } ?: emptyMap(),
            chunks = chunks,
            buildings = buildings,
            orders = orders,
            builders = builders,
            idle = idle,
            roads = rt.roads.plannedNear(player.blockX, player.blockZ, 112),
            marine = (nation?.level(com.grandstrategy.sim.TechBranch.MARINE) ?: 0) >= 1
        ))
    }

    private fun result(player: ServerPlayer, outcome: Outcome) {
        if (player.uuid in active && ServerPlayNetworking.canSend(player, ServerMessagePayload.TYPE)) {
            send(player, ToClient.Result(outcome.ok, outcome.message))
        } else {
            player.sendSystemMessage(Texts.outcome(outcome))
        }
    }

    private fun send(player: ServerPlayer, message: ToClient) {
        if (!ServerPlayNetworking.canSend(player, ServerMessagePayload.TYPE)) return
        ServerPlayNetworking.send(player, ServerMessagePayload(WorkJson.json.encodeToString(ToClient.serializer(), message)))
    }
}
