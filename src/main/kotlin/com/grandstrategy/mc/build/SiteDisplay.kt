package com.grandstrategy.mc.build

import com.grandstrategy.GrandStrategy
import com.grandstrategy.mc.Runtime
import com.grandstrategy.sim.BuildingKind
import com.grandstrategy.sim.WorkOrder
import com.grandstrategy.sim.WorkType
import it.unimi.dsi.fastutil.ints.IntArrayList
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.TagParser
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.UUID

/**
 * Marks every building site with a construction set made of display entities: timber posts at the corners, a frame
 * of beams, striped hazard tape at waist height, traffic cones, and a floating label with the building and progress.
 * Nothing is solid and nothing is a real entity: the parts exist only as packets sent to players within [RANGE], so a
 * hundred sites cost the server nothing and leave no leftovers in saved chunks.
 */
class SiteDisplay(private val rt: Runtime) {
    private class Part(val type: EntityType<*>, val x: Double, val y: Double, val z: Double, val data: List<SynchedEntityData.DataValue<*>>)

    private class Site(val parts: List<Part>, val ids: IntArray, val uuids: Array<UUID>, val label: Int, var percent: Int)

    private val sites = HashMap<Int, Site>()
    private val shown = HashMap<UUID, HashSet<Int>>()
    private var ticks = 0L
    private val level: ServerLevel get() = rt.server.overworld()

    fun tick() {
        ticks++
        if (ticks % 10 != 0L) return
        val orders = rt.state.orders
        for (id in sites.keys.toList()) {
            val o = orders[id]
            if (o == null || o.type != WorkType.BUILD) drop(id)
        }
        val players = rt.server.playerList.players.filter { it.level() == level }
        shown.keys.retainAll(players.map { it.uuid }.toSet())
        for (p in players) {
            val have = shown.getOrPut(p.uuid) { HashSet() }
            val want = HashSet<Int>()
            for (o in orders.values) {
                if (o.type != WorkType.BUILD) continue
                val dx = o.box.centerX - p.x
                val dz = o.box.centerZ - p.z
                if (dx * dx + dz * dz <= RANGE * RANGE) want += o.id
            }
            for (id in have.toList()) if (id !in want) {
                sites[id]?.let { p.connection.send(ClientboundRemoveEntitiesPacket(IntArrayList(it.ids))) }
                have -= id
            }
            for (id in want) {
                if (id in have) continue
                val site = sites[id] ?: build(orders[id] ?: continue)?.also { sites[id] = it } ?: continue
                spawn(p, site)
                have += id
            }
        }
        // Progress labels: re-send only when the percentage moved.
        for ((id, site) in sites) {
            val o = orders[id] ?: continue
            val pct = percent(o)
            if (pct == site.percent) continue
            site.percent = pct
            val data = label(o, pct)
            for (p in players) if (shown[p.uuid]?.contains(id) == true) p.connection.send(ClientboundSetEntityDataPacket(site.ids[site.label], data))
        }
    }

    private fun drop(id: Int) {
        val site = sites.remove(id) ?: return
        for (p in rt.server.playerList.players) {
            if (shown[p.uuid]?.remove(id) == true) p.connection.send(ClientboundRemoveEntitiesPacket(IntArrayList(site.ids)))
        }
    }

    private fun spawn(p: ServerPlayer, site: Site) {
        site.parts.forEachIndexed { k, part ->
            p.connection.send(ClientboundAddEntityPacket(site.ids[k], site.uuids[k], part.x, part.y, part.z, 0f, 0f, part.type, 0, Vec3.ZERO, 0.0))
            if (part.data.isNotEmpty()) p.connection.send(ClientboundSetEntityDataPacket(site.ids[k], part.data))
        }
    }

    private fun percent(o: WorkOrder): Int = if (o.total > 0) (o.progress * 100 / o.total).toInt().coerceIn(0, 100) else 0

    private fun build(o: WorkOrder): Site? {
        val b = o.box
        if (!BlockClass.loaded(level, b)) return null
        val y0 = (if (o.baseY != Int.MIN_VALUE) o.baseY else BlockClass.medianGround(level, b.minX, b.minZ, b.maxX, b.maxZ)).toDouble()
        val height = (if (o.kind == BuildingKind.WALLS) 4 else b.sizeY).coerceIn(3, 14).toDouble()
        val x0 = b.minX - 0.3
        val x1 = b.maxX + 1.3
        val z0 = b.minZ - 0.3
        val z1 = b.maxZ + 1.3
        val parts = ArrayList<Part>()
        return try {
            // Corner posts.
            for (x in listOf(x0, x1)) for (z in listOf(z0, z1)) parts += block(LOG_Y, x - 0.1, y0, z - 0.1, 0.2, height + 0.4, 0.2)
            // Frame beams at mid height and on top.
            for (y in listOf(y0 + height / 2, y0 + height + 0.2)) {
                for (z in listOf(z0, z1)) parts += block(LOG_X, x0, y, z - 0.08, x1 - x0, 0.16, 0.16)
                for (x in listOf(x0, x1)) parts += block(LOG_Z, x - 0.08, y, z0, 0.16, 0.16, z1 - z0)
            }
            // Hazard tape: a yellow band with black stripes laid over it, just outside the posts.
            val ty = y0 + 0.95
            for ((z, out) in listOf(z0 - 0.14 to -1, z1 + 0.11 to 1)) {
                parts += block(YELLOW, x0, ty, z, x1 - x0, 0.15, 0.03, lit = true)
                stripes(x1 - x0) { s, len -> parts += block(BLACK, x0 + s, ty - 0.005, z + (if (out < 0) -0.006 else 0.0), len, 0.16, 0.042, lit = true) }
            }
            for ((x, out) in listOf(x0 - 0.14 to -1, x1 + 0.11 to 1)) {
                parts += block(YELLOW, x, ty, z0, 0.03, 0.15, z1 - z0, lit = true)
                stripes(z1 - z0) { s, len -> parts += block(BLACK, x + (if (out < 0) -0.006 else 0.0), ty - 0.005, z0 + s, 0.042, 0.16, len, lit = true) }
            }
            // Traffic cones just outside each corner.
            for (x in listOf(x0 - 0.7, x1 + 0.35)) for (z in listOf(z0 - 0.7, z1 + 0.35)) {
                parts += block(ORANGE, x, y0, z, 0.34, 0.55, 0.34, lit = true)
                parts += block(WHITE, x - 0.01, y0 + 0.3, z - 0.01, 0.36, 0.09, 0.36, lit = true)
            }
            val label = parts.size
            parts += Part(EntityTypes.TEXT_DISPLAY, b.centerX, y0 + height + 0.9, b.centerZ, label(o, percent(o)))
            val ids = IntArray(parts.size) { level.nextEntityId }
            Site(parts, ids, Array(parts.size) { UUID.randomUUID() }, label, percent(o))
        } catch (e: Exception) {
            GrandStrategy.LOGGER.warn("Construction display for order #{} failed", o.id, e)
            null
        }
    }

    /** Black stripes every ~0.9 blocks, never more than [MAX_STRIPES] per side. */
    private inline fun stripes(length: Double, add: (Double, Double) -> Unit) {
        val step = maxOf(0.9, length / MAX_STRIPES)
        var s = 0.2
        while (s + 0.4 < length) {
            add(s, 0.4)
            s += step
        }
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.3ff", v)

    private fun transform(sx: Double, sy: Double, sz: Double, tx: Double = 0.0, ty: Double = 0.0, tz: Double = 0.0) =
        "transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[${f(tx)},${f(ty)},${f(tz)}],scale:[${f(sx)},${f(sy)},${f(sz)}]}"

    /**
     * A box of [item]'s block model spanning (x, y, z) to (x + sx, y + sy, z + sz). Drawn as an item display rather than a
     * block display: both are vanilla, but the 26.3 client does not draw block displays, while item displays of block
     * items render the same cube. Item models are centred on the entity, hence the half-size offset.
     */
    private fun block(item: String, x: Double, y: Double, z: Double, sx: Double, sy: Double, sz: Double, lit: Boolean = false): Part {
        val light = if (lit) ",brightness:{sky:15,block:15}" else ""
        val tag = TagParser.parseCompoundFully(
            "{item:{id:\"$item\",count:1},item_display:\"none\",${transform(sx, sy, sz, sx / 2, sy / 2, sz / 2)},view_range:1.5f$light}"
        )
        val e = Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level)
        e.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag))
        return Part(EntityTypes.ITEM_DISPLAY, x, y, z, e.entityData.nonDefaultValues ?: emptyList())
    }

    private fun label(o: WorkOrder, pct: Int): List<SynchedEntityData.DataValue<*>> {
        val name = o.kind?.label ?: "건물"
        val filled = pct / 10
        val text = Component.literal("⚒ 공사 중\n").withStyle { it.withColor(0xFFC83C).withBold(true) }
            .append(Component.literal("$name  $pct%\n").withStyle { it.withColor(0xFFFFFF).withBold(false) })
            .append(Component.literal("■".repeat(filled)).withStyle { it.withColor(0x5AE66E).withBold(false) })
            .append(Component.literal("■".repeat(10 - filled)).withStyle { it.withColor(0x505050).withBold(false) })
        val tag: CompoundTag = TagParser.parseCompoundFully("{billboard:\"center\",background:-1728053248,line_width:200,view_range:1.5f,${transform(1.2, 1.2, 1.2)},brightness:{sky:15,block:15}}")
        tag.put("text", ComponentSerialization.CODEC.encodeStart(level.registryAccess().createSerializationContext(NbtOps.INSTANCE), text).getOrThrow())
        val e = Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level)
        e.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag))
        return e.entityData.nonDefaultValues ?: emptyList()
    }

    fun status(): String = "공사 표시 ${sites.size}곳 · 부품 ${sites.values.sumOf { it.parts.size }}개 · 보는 플레이어 ${shown.count { it.value.isNotEmpty() }}명"

    private companion object {
        const val RANGE = 80.0
        const val MAX_STRIPES = 18
        // Stripped wood has bark texture on every side, so beams need no rotation whichever way they run.
        const val LOG_Y = "minecraft:stripped_spruce_wood"
        const val LOG_X = "minecraft:stripped_spruce_wood"
        const val LOG_Z = "minecraft:stripped_spruce_wood"
        const val YELLOW = "minecraft:yellow_concrete"
        const val BLACK = "minecraft:black_concrete"
        const val ORANGE = "minecraft:orange_concrete"
        const val WHITE = "minecraft:white_concrete"
    }
}
