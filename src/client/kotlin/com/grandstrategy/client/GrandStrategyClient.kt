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
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking

object GrandStrategyClient : ClientModInitializer {
    override fun onInitializeClient() {
        WorkMode.init()
        com.grandstrategy.client.ui.NationMenu.init()
        com.grandstrategy.client.ui.WorldMap.init()
        com.grandstrategy.client.ui.BuildingPreview.init()
        ClientPlayNetworking.registerGlobalReceiver(PlayCutscenePayload.TYPE) { payload, _ ->
            CutscenePlayer.start(CutsceneSpec.decode(payload.json))
        }
        val capture = System.getProperty("grandstrategy.capture") == "true"
        // Ticks between automatic screenshots in capture mode (short intervals show motion frame by frame).
        val captureTicks = Integer.getInteger("grandstrategy.captureTicks", 200).coerceAtLeast(1)
        // ...and at most this many per run, so a forgotten capture run cannot fill the disk.
        val captureLimit = Integer.getInteger("grandstrategy.captureLimit", 300)
        var ticks = 0
        var shots = 0
        var screenTicks = 0
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            CutscenePlayer.tick()
            if (!CutscenePlayer.active) {
                WorkMode.tick(mc)
                com.grandstrategy.client.ui.NationMenu.tick(mc)
                com.grandstrategy.client.ui.WorldMap.tick(mc)
            }
            // Automated capture runs unattended: a pause menu from losing window focus would hide every shot.
            if (capture && mc.gui.screen() is net.minecraft.client.gui.screens.PauseScreen) mc.gui.setScreen(null)
            // ...and a panel opened by a test command closes by itself after 15 seconds.
            if (capture) {
                if (mc.gui.screen() == null) screenTicks = 0 else if (++screenTicks > 300) mc.gui.setScreen(null)
            }
            if (capture && mc.level != null && !CutscenePlayer.active && ++ticks % captureTicks == 0) {
                if (shots < captureLimit) Screenshot.grab(mc.gameDirectory, "view_%03d.png".format(shots++), mc.gameRenderer.mainRenderTarget(), 1) { }
                // Performance probe for automated runs: frame rate against the size of the crowd in view.
                val level = mc.level
                if (level != null) {
                    var villagers = 0
                    var near = 0
                    val eye = mc.player?.position()
                    val types = HashMap<String, Int>()
                    for (e in level.entitiesForRendering()) {
                        types.merge(e.type.toShortString(), 1, Int::plus)
                        if (e is net.minecraft.world.entity.npc.villager.Villager) {
                            villagers++
                            if (eye != null && e.distanceToSqr(eye) < 40.0 * 40.0) near++
                        }
                    }
                    val top = types.entries.sortedByDescending { it.value }.take(5).joinToString { "${it.key} ${it.value}" }
                    com.grandstrategy.GrandStrategy.LOGGER.info("[perf] fps {} entities {} villagers {} (within 40: {}) · {}", mc.fps, level.entityCount, villagers, near, top)
                }
            }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            CutscenePlayer.stop(false)
            com.grandstrategy.client.ui.WorldMap.clear()
        }
        ClientPlayNetworking.registerGlobalReceiver(ServerMessagePayload.TYPE) { payload, _ ->
            val message = runCatching { WorkJson.json.decodeFromString(ToClient.serializer(), payload.json) }
                .onFailure { com.grandstrategy.GrandStrategy.LOGGER.warn("Could not read a server message ({} chars, {}): {}", payload.json.length, payload.json.take(40), it.toString()) }
                .getOrNull()
            when (message) {
                is com.grandstrategy.net.MenuView -> com.grandstrategy.client.ui.NationMenu.onView(message)
                is com.grandstrategy.net.MapInfo -> com.grandstrategy.client.ui.WorldMap.onInfo(message)
                is com.grandstrategy.net.MapTiles -> com.grandstrategy.client.ui.WorldMap.onTiles(message)
                is com.grandstrategy.net.SetupDone -> net.minecraft.client.Minecraft.getInstance().let { mc -> if (mc.gui.screen() is com.grandstrategy.client.ui.SetupScreen) mc.gui.setScreen(null) }
                is com.grandstrategy.net.SetupOffer -> net.minecraft.client.Minecraft.getInstance().gui.setScreen(com.grandstrategy.client.ui.SetupScreen(message))
                is ToClient.Result -> {
                    WorkMode.onMessage(message)
                    com.grandstrategy.client.ui.NationMenu.onResult(message)
                }
                null -> {}
                else -> WorkMode.onMessage(message)
            }
        }
        ClientPreAttackCallback.EVENT.register { _, _, _ -> WorkMode.swallowAttack() }
        ClientTickEvents.START_CLIENT_TICK.register { mc -> WorkMode.blockVanillaInput(mc) }
        ClientHotbarScrollEvents.ALLOW.register { _, _, _, _, _ -> !WorkMode.active }
    }
}
