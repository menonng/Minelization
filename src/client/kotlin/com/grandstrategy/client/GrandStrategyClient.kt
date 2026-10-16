package com.grandstrategy.client

import com.grandstrategy.client.cutscene.CutscenePlayer
import com.grandstrategy.net.CutsceneSpec
import com.grandstrategy.net.PlayCutscenePayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object GrandStrategyClient : ClientModInitializer {
    override fun onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(PlayCutscenePayload.TYPE) { payload, _ ->
            CutscenePlayer.start(CutsceneSpec.decode(payload.json))
        }
        ClientTickEvents.END_CLIENT_TICK.register { CutscenePlayer.tick() }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> CutscenePlayer.stop(false) }
    }
}
