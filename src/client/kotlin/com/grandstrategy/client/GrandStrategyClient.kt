package com.grandstrategy.client

import com.grandstrategy.client.cutscene.CutscenePlayer
import com.grandstrategy.net.CutsceneSpec
import com.grandstrategy.net.PlayCutscenePayload
import net.fabricmc.api.ClientModInitializer
import net.minecraft.client.Screenshot
import com.grandstrategy.client.work.WorkMode
import com.grandstrategy.net.ServerMessagePayload
import com.grandstrategy.net.ToClient
import com.grandstrategy.net.WorkJson
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object GrandStrategyClient : ClientModInitializer {
    override fun onInitializeClient() {
        WorkMode.init()
        ClientPlayNetworking.registerGlobalReceiver(PlayCutscenePayload.TYPE) { payload, _ ->
            CutscenePlayer.start(CutsceneSpec.decode(payload.json))
        }
        val capture = System.getProperty("grandstrategy.capture") == "true"
        var ticks = 0
        var shots = 0
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            CutscenePlayer.tick()
            if (!CutscenePlayer.active) WorkMode.tick(mc)
            if (capture && mc.level != null && !CutscenePlayer.active && ++ticks % 200 == 0) {
                Screenshot.grab(mc.gameDirectory, "view_%03d.png".format(shots++), mc.gameRenderer.mainRenderTarget(), 1) { }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> CutscenePlayer.stop(false) }
        ClientPlayNetworking.registerGlobalReceiver(ServerMessagePayload.TYPE) { payload, _ ->
            runCatching { WorkJson.json.decodeFromString(ToClient.serializer(), payload.json) }.getOrNull()?.let { WorkMode.onMessage(it) }
        }
        ClientPreAttackCallback.EVENT.register { _, _, _ -> WorkMode.swallowAttack() }
    }
}
