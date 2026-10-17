package com.grandstrategy.crowdperf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Settings in {@code config/crowdperf.json}; every field can also be set with {@code -Dcrowdperf.<field>=<value>}. */
public final class CrowdConfig {
    /** Draw distant villagers from the batch instead of one full model each per frame. */
    public boolean batch = true;
    /** Villagers farther than this (blocks) go into the batch. */
    public double batchDistance = 32.0;
    /** Each frame, only the chunks of the crowd the camera can see are sent to the GPU. */
    public boolean frustumCulling = true;
    /** Past this the batch uses a simpler mesh (no nose or hat). */
    public double lowDetailDistance = 64.0;
    /** Batched villagers skip the full client tick; their movement, head and walk cycle still update. */
    public boolean lightTick = true;
    /** Villagers ease towards each server update over the time until the next one, so rare updates still walk smoothly. */
    public boolean smoothMotion = true;
    /** Remember which precipitation falls on each column instead of looking up the biome for every column every frame. */
    public boolean weatherCache = true;
    /** Villagers drawn as full models skip their clothing layers, held item and shadow past this distance. */
    public double detailDistance = 48.0;
    /** Villagers stay visible as far as terrain instead of vanilla's ~64 block cutoff for small mobs. */
    public boolean renderToViewDistance = true;
    /** At most this many villagers go into the batch (nearest first don't matter: all far ones are equal). */
    public int maxBatched = 30000;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("crowdperf.json");
    }

    public static CrowdConfig load() {
        CrowdConfig config = new CrowdConfig();
        Path file = path();
        if (!Files.exists(file)) config.save();
        else {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                CrowdConfig read = GSON.fromJson(reader, CrowdConfig.class);
                if (read != null) config = read;
            } catch (IOException | RuntimeException e) {
                CrowdPerf.LOGGER.warn("Could not read {}, using defaults: {}", file, e.toString());
            }
        }
        // Overrides from the command line apply to this run only and are not written back.
        config.applySystemProperties();
        return config;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            CrowdPerf.LOGGER.warn("Could not write {}: {}", path(), e.toString());
        }
    }

    private void applySystemProperties() {
        for (var field : CrowdConfig.class.getFields()) {
            String value = System.getProperty("crowdperf." + field.getName());
            if (value == null) continue;
            try {
                Class<?> type = field.getType();
                if (type == boolean.class) field.setBoolean(this, Boolean.parseBoolean(value));
                else if (type == int.class) field.setInt(this, Integer.parseInt(value));
                else if (type == double.class) field.setDouble(this, Double.parseDouble(value));
            } catch (ReflectiveOperationException | NumberFormatException e) {
                CrowdPerf.LOGGER.warn("Bad value for crowdperf.{}: {}", field.getName(), value);
            }
        }
    }
}
