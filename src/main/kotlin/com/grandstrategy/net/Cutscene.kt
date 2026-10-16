package com.grandstrategy.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.ByteBufCodecs
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier

enum class CutsceneKind(val seconds: Double) {
    FOUNDING(12.0), CAPITAL_FALL(11.0), DESTRUCTION(8.0)
}

@Serializable
data class CutsceneSpec(
    val id: Int,
    val kind: CutsceneKind,
    val nation: String,
    val color: Int,
    val dye: Int,
    val other: String = "",
    val otherColor: Int = 0xFFFFFF,
    val otherDye: Int = 0,
    val x: Double,
    val y: Double,
    val z: Double,
    val turn: Long,
    val subject: String = "",
    val claims: List<Long> = emptyList()
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        fun decode(text: String): CutsceneSpec = json.decodeFromString(serializer(), text)
    }
}

data class PlayCutscenePayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<PlayCutscenePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<PlayCutscenePayload>(Identifier.fromNamespaceAndPath("grandstrategy", "play_cutscene"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, PlayCutscenePayload> =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PlayCutscenePayload::json, ::PlayCutscenePayload)
    }
}

data class CutsceneDonePayload(val id: Int) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<CutsceneDonePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<CutsceneDonePayload>(Identifier.fromNamespaceAndPath("grandstrategy", "cutscene_done"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, CutsceneDonePayload> =
            StreamCodec.composite(ByteBufCodecs.VAR_INT, CutsceneDonePayload::id, ::CutsceneDonePayload)
    }
}

data class ClientMessagePayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ClientMessagePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ClientMessagePayload>(Identifier.fromNamespaceAndPath("grandstrategy", "client_message"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ClientMessagePayload> =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ClientMessagePayload::json, ::ClientMessagePayload)
    }
}

data class ServerMessagePayload(val json: String) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ServerMessagePayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ServerMessagePayload>(Identifier.fromNamespaceAndPath("grandstrategy", "server_message"))
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, ServerMessagePayload> =
            StreamCodec.composite(ByteBufCodecs.STRING_UTF8, ServerMessagePayload::json, ::ServerMessagePayload)
    }
}
