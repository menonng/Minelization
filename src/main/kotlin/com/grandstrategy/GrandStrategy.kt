package com.grandstrategy

import com.grandstrategy.mc.BalanceFile
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
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> GsCommands.register(dispatcher) }

        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            val save = Storage.load(server)
            val rt = Runtime(server, save)
            runtime = rt
            LOGGER.info(
                "Loaded turn {}, {} nations, {} settlements",
                rt.state.turn, rt.state.alive().size, rt.state.settlements.size
            )
            rt.hooks.startScouting()
        }

        ServerTickEvents.END_SERVER_TICK.register { runtime?.tick() }

        ServerChunkEvents.CHUNK_LOAD.register { level, chunk, _ -> runtime?.hooks?.onChunkLoad(level, chunk) }

        ServerPlayConnectionEvents.JOIN.register { handler, _, _ ->
            val rt = runtime ?: return@register
            rt.notifyProposals()
            if (rt.citizenNation(handler.player) == null) {
                handler.player.sendSystemMessage(
                    com.grandstrategy.mc.Texts.prefix().append(
                        com.grandstrategy.mc.Texts.plain("이 세계에는 나라 ${rt.state.alive().size}개가 있다. /gs 로 나라를 고르거나 세운다.")
                    )
                )
            }
        }

        ServerPlayConnectionEvents.DISCONNECT.register { handler, _ -> runtime?.hooks?.forget(handler.player.uuid) }

        ServerLifecycleEvents.SERVER_STOPPING.register {
            runtime?.save()
            LOGGER.info("Saved nation data")
            runtime = null
        }
    }
}
