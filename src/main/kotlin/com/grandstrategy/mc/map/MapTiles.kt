package com.grandstrategy.mc.map

import com.grandstrategy.GrandStrategy
import com.grandstrategy.mc.Runtime
import com.grandstrategy.mc.Texts
import com.grandstrategy.net.MapDistrict
import com.grandstrategy.net.MapInfo
import com.grandstrategy.net.MapNation
import com.grandstrategy.net.MapPlayer
import com.grandstrategy.net.MapTileData
import com.grandstrategy.net.MapTiles
import com.grandstrategy.net.MapTown
import com.grandstrategy.net.ServerMessagePayload
import com.grandstrategy.net.ToClient
import com.grandstrategy.net.WorkJson
import com.grandstrategy.sim.Chunks
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.core.BlockPos
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class MapTileStore(private val rt: Runtime) {
    private class Tile(val pixels: ByteArray, var version: Int, var dirty: Boolean) {
        var mask = 0L
    }

    private val tiles = HashMap<Long, Tile>()
    private val missing = HashSet<Long>()
    private val viewers = HashMap<UUID, Long>()
    private var ticks = 0L
    private var indexed = false
    /** Distinct chunks with map colors, counted from per-tile 8x8 chunk masks. */
    var chunksColored = 0L
        private set

    private val dir: Path get() = rt.server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("grandstrategy_map")

    fun onChunk(level: ServerLevel, chunk: LevelChunk) {
        if (level != rt.server.overworld()) return
        val cx = chunk.pos.x
        val cz = chunk.pos.z
        val tile = tile(Chunks.pack(cx shr 3, cz shr 3), create = true) ?: return
        val ox = (cx and 7) * 16
        val oz = (cz and 7) * 16
        val pos = BlockPos.MutableBlockPos()
        val heights = IntArray(16)
        for (lx in 0 until 16) heights[lx] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, 0)
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz)
                var y = top
                var state = chunk.getBlockState(pos.set(cx * 16 + lx, y, cz * 16 + lz))
                var color = state.getMapColor(chunk, pos)
                while (color == MapColor.NONE && y > level.minY) {
                    y--
                    state = chunk.getBlockState(pos.set(cx * 16 + lx, y, cz * 16 + lz))
                    color = state.getMapColor(chunk, pos)
                }
                val packed = if (!state.fluidState.isEmpty) {
                    var depth = 0
                    var dy = y - 1
                    while (dy > level.minY && depth < 12 && !chunk.getBlockState(pos.set(cx * 16 + lx, dy, cz * 16 + lz)).fluidState.isEmpty) {
                        depth++
                        dy--
                    }
                    val shade = when {
                        depth < 2 -> MapColor.Brightness.HIGH
                        depth < 5 -> if ((lx + lz) and 1 == 0) MapColor.Brightness.HIGH else MapColor.Brightness.NORMAL
                        depth < 9 -> MapColor.Brightness.NORMAL
                        else -> MapColor.Brightness.LOW
                    }
                    color.id * 4 + shade.id
                } else {
                    val north = heights[lx]
                    val shade = when {
                        top > north -> MapColor.Brightness.HIGH
                        top < north -> MapColor.Brightness.LOW
                        else -> MapColor.Brightness.NORMAL
                    }
                    color.id * 4 + shade.id
                }
                heights[lx] = top
                tile.pixels[(oz + lz) * 128 + ox + lx] = packed.toByte()
            }
        }
        tile.version++
        tile.dirty = true
        val bit = 1L shl ((cz and 7) * 8 + (cx and 7))
        if (tile.mask and bit == 0L) {
            tile.mask = tile.mask or bit
            chunksColored++
        }
    }

    private fun maskOf(pixels: ByteArray): Long {
        var mask = 0L
        for (c in 0 until 64) {
            val ox = (c and 7) * 16
            val oz = (c shr 3) * 16
            // Sample the chunk's diagonal; a rendered chunk never has all of these unset.
            for (k in 0 until 16) if (pixels[(oz + k) * 128 + ox + k].toInt() and 0xFC != 0) {
                mask = mask or (1L shl c)
                break
            }
        }
        return mask
    }

    /** Loads every saved tile once so the colored-chunk count covers the whole world, not just tiles touched this session. */
    private fun index() {
        indexed = true
        if (!Files.isDirectory(dir)) return
        runCatching {
            Files.list(dir).use { files ->
                files.forEach { f ->
                    val name = f.fileName.toString().removeSuffix(".bin").split('_')
                    val x = name.getOrNull(0)?.toIntOrNull() ?: return@forEach
                    val z = name.getOrNull(1)?.toIntOrNull() ?: return@forEach
                    tile(Chunks.pack(x, z), create = false)
                }
            }
        }.onFailure { GrandStrategy.LOGGER.warn("Map tile index failed", it) }
    }

    private fun tile(key: Long, create: Boolean): Tile? {
        tiles[key]?.let { return it }
        if (key !in missing) {
            val file = dir.resolve("${Chunks.x(key)}_${Chunks.z(key)}.bin")
            if (Files.exists(file)) {
                val loaded = runCatching { GZIPInputStream(Files.newInputStream(file)).use { it.readBytes() } }.getOrNull()
                if (loaded != null && loaded.size == 128 * 128 + 4) {
                    val version = (loaded[0].toInt() and 0xFF shl 24) or (loaded[1].toInt() and 0xFF shl 16) or (loaded[2].toInt() and 0xFF shl 8) or (loaded[3].toInt() and 0xFF)
                    val t = Tile(loaded.copyOfRange(4, loaded.size), version, false)
                    t.mask = maskOf(t.pixels)
                    chunksColored += java.lang.Long.bitCount(t.mask)
                    tiles[key] = t
                    return t
                }
            }
            missing += key
        }
        if (!create) return null
        missing -= key
        val t = Tile(ByteArray(128 * 128), 1, true)
        tiles[key] = t
        return t
    }

    fun tick() {
        ticks++
        if (!indexed) index()
        if (ticks % 600 == 0L) save()
        if (ticks % 40 == 0L && viewers.isNotEmpty()) {
            viewers.keys.retainAll(rt.server.playerList.players.map { it.uuid }.toSet())
            for (uuid in viewers.keys) rt.server.playerList.getPlayer(uuid)?.let { sendInfo(it) }
        }
    }

    fun save() {
        val dirty = tiles.filterValues { it.dirty }
        if (dirty.isEmpty()) return
        try {
            Files.createDirectories(dir)
            for ((key, t) in dirty) {
                val file = dir.resolve("${Chunks.x(key)}_${Chunks.z(key)}.bin")
                GZIPOutputStream(Files.newOutputStream(file)).use { out ->
                    out.write(byteArrayOf((t.version ushr 24).toByte(), (t.version ushr 16).toByte(), (t.version ushr 8).toByte(), t.version.toByte()))
                    out.write(t.pixels)
                }
                t.dirty = false
            }
        } catch (e: Exception) {
            GrandStrategy.LOGGER.error("Failed to save map tiles", e)
        }
    }

    fun open(player: ServerPlayer) {
        viewers[player.uuid] = 0L
        sendInfo(player, open = true)
    }

    fun close(player: ServerPlayer) {
        viewers.remove(player.uuid)
    }

    fun request(player: ServerPlayer, keys: List<Long>, versions: List<Int>) {
        val out = ArrayList<MapTileData>()
        var budget = 0
        keys.take(48).forEachIndexed { k, key ->
            val known = versions.getOrElse(k) { -1 }
            val t = tile(key, create = false)
            val owners = IntArray(64)
            val flags = IntArray(64)
            val districts = IntArray(64)
            for (dz in 0 until 8) for (dx in 0 until 8) {
                val chunk = Chunks.pack(Chunks.x(key) * 8 + dx, Chunks.z(key) * 8 + dz)
                owners[dz * 8 + dx] = rt.state.owners[chunk] ?: 0
                flags[dz * 8 + dx] = if (rt.state.settlementLand.containsKey(chunk)) 1 else 0
                districts[dz * 8 + dx] = rt.state.districtOf[chunk] ?: 0
            }
            val pixels = if (t != null && t.version != known && budget < 24) {
                budget++
                Base64.getEncoder().encodeToString(t.pixels)
            } else {
                null
            }
            out += MapTileData(key, t?.version ?: 0, pixels, owners.toList(), flags.toList(), districts.toList())
        }
        send(player, MapTiles(out))
    }

    fun sendInfo(player: ServerPlayer, open: Boolean = false) {
        val level = rt.server.overworld()
        val border = level.worldBorder
        val nations = rt.state.alive().map { n ->
            MapNation(n.id, n.name, Texts.dye(n).textColor, n.color, n.capital, n.claims.size, n.flag)
        }
        val towns = rt.state.settlements.values.map { MapTown(it.id, it.name, it.center, it.owner ?: 0) }
        val players = rt.server.playerList.players.filter { it.level() == level }.map {
            MapPlayer(it.gameProfile.name(), it.x, it.z, it.yRot, rt.citizenNation(it)?.id ?: 0, it.uuid == player.uuid)
        }
        val districts = rt.state.districts.values.map { MapDistrict(it.id, it.nation, it.name, it.seat, it.size) }
        send(player, MapInfo(open, border.centerX, border.centerZ, border.size, nations, towns, players, chunksColored, districts))
    }

    private fun send(player: ServerPlayer, message: ToClient) {
        if (!ServerPlayNetworking.canSend(player, ServerMessagePayload.TYPE)) return
        ServerPlayNetworking.send(player, ServerMessagePayload(WorkJson.json.encodeToString(ToClient.serializer(), message)))
    }
}
