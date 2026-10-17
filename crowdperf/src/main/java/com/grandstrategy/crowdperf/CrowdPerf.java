package com.grandstrategy.crowdperf;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CrowdPerf: makes thousands of villagers cheap to draw. See {@link Impostors} for the batch, {@link Detail} for the
 * level of detail of the villagers still drawn as models. Client only; works on any server.
 */
public final class CrowdPerf implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("CrowdPerf");
    private static CrowdConfig config = new CrowdConfig();
    /** With -Dcrowdperf.log=true the batch's numbers are logged every 10 seconds. */
    private static final boolean LOG = Boolean.getBoolean("crowdperf.log");
    private static int logTicks;

    public static CrowdConfig config() {
        return config;
    }

    @Override
    public void onInitializeClient() {
        config = CrowdConfig.load();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            Impostors.tick(mc);
            if (LOG && ++logTicks % 200 == 0) {
                LOGGER.info("[crowdperf] {}", Impostors.stats());
                Impostors.resetStats();
            }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> Impostors.reset());
        // Resource packs can change villager skins: compose the atlas again after a reload.
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(Identifier.fromNamespaceAndPath("crowdperf", "batch"),
            (ResourceManagerReloadListener) manager -> Impostors.reset());
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> dispatcher.register(
            ClientCommands.literal("crowdperf")
                .executes(c -> {
                    c.getSource().sendFeedback(Component.literal("CrowdPerf: batch " + (config.batch ? "on" : "off") + " past "
                        + config.batchDistance + " blocks; " + Impostors.stats()));
                    return 1;
                })
                .then(ClientCommands.literal("on").executes(c -> set(c.getSource(), true)))
                .then(ClientCommands.literal("off").executes(c -> set(c.getSource(), false)))
                .then(ClientCommands.literal("reload").executes(c -> {
                    config = CrowdConfig.load();
                    Impostors.reset();
                    c.getSource().sendFeedback(Component.literal("CrowdPerf: config and textures reloaded"));
                    return 1;
                }))
                .then(ClientCommands.literal("distance").then(ClientCommands.argument("blocks", DoubleArgumentType.doubleArg(4.0, 512.0))
                    .executes(c -> {
                        config.batchDistance = DoubleArgumentType.getDouble(c, "blocks");
                        config.save();
                        c.getSource().sendFeedback(Component.literal("CrowdPerf: batch past " + config.batchDistance + " blocks"));
                        return 1;
                    })))));
        LOGGER.info("CrowdPerf ready: batch {} past {} blocks", config.batch ? "on" : "off", config.batchDistance);
    }

    private static int set(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, boolean on) {
        config.batch = on;
        config.save();
        source.sendFeedback(Component.literal("CrowdPerf: batch " + (on ? "on" : "off")));
        return 1;
    }
}
