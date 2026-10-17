package com.grandstrategy

import com.grandstrategy.mc.BalanceFile
import com.grandstrategy.mc.CutsceneGuard
import com.grandstrategy.net.ClientMessagePayload
import com.grandstrategy.net.CutsceneDonePayload
import com.grandstrategy.net.ServerMessagePayload
import com.grandstrategy.net.PlayCutscenePayload
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import com.grandstrategy.mc.GsCommands
import com.grandstrategy.mc.Runtime
import com.grandstrategy.mc.Storage
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object GrandStrategy : ModInitializer {
    const val MOD_ID = "grandstrategy"
    val LOGGER: Logger = LoggerFactory.getLogger("Grand Strategy")

    var runtime: Runtime? = null
        private set

    override fun onInitialize() {
        BalanceFile.loadAtStartup()
        PayloadTypeRegistry.clientboundPlay().register(PlayCutscenePayload.TYPE, PlayCutscenePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(CutsceneDonePayload.TYPE, CutsceneDonePayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(ClientMessagePayload.TYPE, ClientMessagePayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ServerMessagePayload.TYPE, ServerMessagePayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(ClientMessagePayload.TYPE) { payload, context ->
            runtime?.workService?.handle(context.player(), payload.json)
        }
        ServerPlayNetworking.registerGlobalReceiver(CutsceneDonePayload.TYPE) { payload, context ->
            val player = context.player()
            CutsceneGuard.finish(player.level().server, player, payload.id)
        }
        ServerLivingEntityEvents.ALLOW_DAMAGE.register { entity, _, _ -> !CutsceneGuard.isGuarded(entity.uuid) && !com.grandstrategy.mc.GodMode.protects(entity) }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> GsCommands.register(dispatcher) }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val save = Storage.load(server)
            val rt = Runtime(server, save)
            runtime = rt
            LOGGER.info(
                "Loaded turn {}, {} nations, {} settlements",
                rt.state.turn, rt.state.alive().size, rt.state.settlements.size
            )
            rt.setup.start()
        }

        ServerTickEvents.END_SERVER_TICK.register { runtime?.tick() }

        ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ -> runtime?.hooks?.onChunkLoad(level, chunk) }

        ServerPlayConnectionEvents.JOIN.register { handler, _, server ->
            val rt = runtime ?: return@register
            CutsceneGuard.restoreIfPending(server, handler.player)
            rt.setup.applyMode(handler.player)
            if (rt.setup.needed()) rt.setup.offer(handler.player)
            rt.notifyProposals()
            if (rt.citizenNation(handler.player) == null) {
                handler.player.sendSystemMessage(
                    com.grandstrategy.mc.Texts.prefix().append(
                        com.grandstrategy.mc.Texts.plain("이 세계에는 나라 ${rt.state.alive().size}개가 있다. /gs 로 나라를 고르거나 세운다.")
                    )
                )
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, server ->
            CutsceneGuard.release(server, handler.player)
            runtime?.hooks?.forget(handler.player.uuid)
            runtime?.agentDisplay?.removePlayer(handler.player.uuid)
            runtime?.workService?.forget(handler.player.uuid)
            runtime?.menus?.forget(handler.player.uuid)
        }

        ServerLifecycleEvents.SERVER_STOPPING.register { server ->
            server.playerList.players.forEach { CutsceneGuard.release(server, it) }
            runtime?.save()
            LOGGER.info("Saved nation data")
            runtime = null
        }
    }
}
