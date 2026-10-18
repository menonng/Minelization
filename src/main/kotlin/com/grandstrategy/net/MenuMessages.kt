package com.grandstrategy.net

import com.grandstrategy.sim.EventKind
import com.grandstrategy.sim.Facility
import com.grandstrategy.sim.Policy
import com.grandstrategy.sim.Posture
import com.grandstrategy.sim.Resource
import com.grandstrategy.sim.TechBranch
import com.grandstrategy.sim.Treaty
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NationCard(
    val id: Int,
    val name: String,
    val color: Int,
    val dye: Int,
    val ruler: String,
    val autoGovern: Boolean,
    val capital: Long,
    val claims: Int,
    val claimLimit: Int,
    val population: Double,
    val housing: Double,
    val military: Double,
    val recruitCap: Double,
    val strength: Double,
    val stability: Double,
    val policy: Policy,
    val posture: Posture,
    val researchFocus: TechBranch,
    val researchProgress: Double,
    val researchCost: Double,
    val science: Double,
    val tech: Map<TechBranch, Int>,
    val facilities: Map<Facility, Int>,
    val stock: Map<Resource, Double>,
    val income: Map<Resource, Double>,
    val storage: Double,
    val claimCost: Map<Resource, Double>,
    val recruitCost: Map<Resource, Double>,
    val wars: Int,
    val flag: com.grandstrategy.sim.Flag? = null,
    val era: String = "",
    val eraIndex: Int = 0,
    val eraCount: Int = 1,
    val techTotal: Int = 0,
    val nextEraAt: Int = 0,
    val weapon: String = "",
    val energy: String = "",
    val ideology: String = "",
    /** Administrative districts and how many chunks one can hold. */
    val districts: Int = 0,
    val districtLimit: Int = 0
)

@Serializable
data class EraRow(val id: String, val name: String, val techTotal: Int, val description: String)

/** One unlockable thing for the era page: kind is tech, weapon, wmd, energy or ideology. */
@Serializable
data class UnlockRow(val kind: String, val id: String, val name: String, val era: Int, val unlocked: Boolean, val value: String, val need: String, val detail: String)

@Serializable
data class ContentView(val eras: List<EraRow>, val eraIndex: Int, val techTotal: Int, val items: List<UnlockRow>, val ideology: String?)

@Serializable
data class NationRow(
    val id: Int,
    val name: String,
    val color: Int,
    val dye: Int,
    val claims: Int,
    val population: Int,
    val military: Int,
    val strength: Double,
    val ruler: String,
    val posture: Posture,
    val opinion: Double = 0.0,
    val treaties: List<Treaty> = emptyList(),
    val atWar: Boolean = false,
    val warScore: Double = 0.0,
    val exhaustion: Double = 0.0,
    val border: Int = 0,
    val flag: com.grandstrategy.sim.Flag? = null
)

@Serializable
data class TownRow(
    val id: Int,
    val name: String,
    val center: Long,
    val population: Int,
    val golems: Int,
    val defense: Int,
    val owner: String,
    val influence: Int,
    val distance: Int,
    val observed: Boolean
)

@Serializable
data class MapTile(val key: Long, val terrain: Int, val owner: Int, val flags: Int)

@Serializable
data class EventRow(val turn: Long, val kind: EventKind, val text: String)

@Serializable
data class ProposalRow(val id: Int, val from: String, val label: String)

@Serializable
@SerialName("menu_open")
data class MenuOpen(val page: String = "") : ToServer()

@Serializable
@SerialName("menu_action")
data class MenuAction(val action: String, val a: String = "", val b: String = "") : ToServer()

@Serializable
@SerialName("menu_view")
data class MenuView(
    val open: Boolean,
    val page: String,
    val turn: Long,
    val ruler: Boolean,
    val nation: NationCard?,
    val nations: List<NationRow>,
    val towns: List<TownRow>,
    val mapCenter: Long,
    val mapRadiusX: Int,
    val mapRadiusZ: Int,
    val tiles: List<MapTile>,
    val events: List<EventRow>,
    val proposals: List<ProposalRow>,
    val here: Long,
    val foundError: String?,
    val content: ContentView? = null
) : ToClient()

object MapFlags {
    const val CAPITAL = 1
    const val TOWN = 2
    const val TOWN_CENTER = 4
    const val BUILDING = 8
}

@Serializable
data class SetupTier(val size: Int, val nations: Int, val label: String, val chunks: Long)

@Serializable
@SerialName("setup_offer")
data class SetupOffer(val tiers: List<SetupTier>, val current: Int, val chunky: Boolean, val firstTime: Boolean, val god: Boolean) : ToClient()

@Serializable
@SerialName("setup_done")
data class SetupDone(val size: Int) : ToClient()

@Serializable
data class MapTileData(val key: Long, val version: Int, val pixels: String?, val owners: List<Int>, val flags: List<Int>, val districts: List<Int> = emptyList())

@Serializable
data class MapNation(val id: Int, val name: String, val color: Int, val dye: Int, val capital: Long, val claims: Int, val flag: com.grandstrategy.sim.Flag? = null)

@Serializable
data class MapTown(val id: Int, val name: String, val center: Long, val owner: Int)

/** An administrative district, for its name on the map. */
@Serializable
data class MapDistrict(val id: Int, val nation: Int, val name: String, val seat: Long, val size: Int)

@Serializable
data class MapPlayer(val name: String, val x: Double, val z: Double, val yaw: Float, val nation: Int, val self: Boolean)

@Serializable
@SerialName("map_open")
data class MapOpen(val open: Boolean) : ToServer()

@Serializable
@SerialName("map_request")
data class MapRequest(val keys: List<Long>, val versions: List<Int>) : ToServer()

@Serializable
@SerialName("map_tiles")
data class MapTiles(val tiles: List<MapTileData>) : ToClient()

@Serializable
@SerialName("map_info")
data class MapInfo(
    val open: Boolean,
    val borderX: Double,
    val borderZ: Double,
    val borderSize: Double,
    val nations: List<MapNation>,
    val towns: List<MapTown>,
    val players: List<MapPlayer>,
    val colored: Long,
    val districts: List<MapDistrict> = emptyList()
) : ToClient()
