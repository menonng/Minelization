package com.grandstrategy.crowdperf;

import java.lang.reflect.Method;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Whether an Iris shader pack is on. A pack draws the world into its own buffers with its own programs; the crowd's
 * instanced pipeline is not one of them, so while a pack is on villagers go back to the normal entity renderer (which
 * the pack shades) and CrowdPerf keeps only the optimisations that do not draw anything itself.
 * Iris is looked up by reflection, so CrowdPerf neither needs it nor breaks without it.
 */
public final class ShaderPacks {
    private static final Object API;
    private static final Method IN_USE;

    static {
        Object api = null;
        Method inUse = null;
        if (FabricLoader.getInstance().isModLoaded("iris")) {
            try {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError e) {
                CrowdPerf.LOGGER.warn("Iris is installed but its API could not be reached; assuming no shader pack: {}", e.toString());
            }
        }
        API = api;
        IN_USE = inUse;
    }

    private ShaderPacks() {}

    public static boolean inUse() {
        if (IN_USE == null) return false;
        try {
            return (boolean) IN_USE.invoke(API);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
