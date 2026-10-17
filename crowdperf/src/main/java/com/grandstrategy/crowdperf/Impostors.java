package com.grandstrategy.crowdperf;

import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.client.resources.metadata.animation.VillagerMetadataSection;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.system.MemoryUtil;

/**
 * The crowd batch. Every tick, all villagers past {@link CrowdConfig#batchDistance} are written into one vertex buffer
 * on the GPU (their baked rest-pose mesh, turned to face where they walk, textured from {@link ImpostorAtlas}); each
 * frame that buffer is drawn with a single call. Vanilla instead extracts a render state, poses a model and builds
 * every cube of every villager anew each frame, which is what made a few thousand villagers slow.
 * <p>
 * Batched villagers are flagged ({@link CrowdEntity}) so the normal entity pass skips them, and they only tick lightly
 * ({@code ClientLevelMixin}). Close villagers, and any villager that needs more than its rest pose (glowing, named,
 * hurt, dying, sleeping, riding, leashed, invisible), stay ordinary entities.
 */
public final class Impostors {
    private static final int VERTEX_BYTES = ImpostorBatch.VERTEX_BYTES;
    private static final int WHITE = 0xFFFFFFFF;
    private static final Identifier VILLAGER_SKIN = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final Identifier BABY_SKIN = Identifier.withDefaultNamespace("textures/entity/villager/villager_baby.png");
    private static final Identifier TRADER_SKIN = Identifier.withDefaultNamespace("textures/entity/wandering_trader/wandering_trader.png");

    private static final ImpostorAtlas atlas = new ImpostorAtlas();
    private static ImpostorMesh villagerMesh;
    private static ImpostorMesh babyMesh;
    private static ImpostorMesh traderMesh;

    /** Villagers between the batch distance and the far distance, rebuilt every tick. */
    private static final ImpostorBatch near = new ImpostorBatch("near batch");
    /** Villagers past the far distance: the server updates them rarely, so this is rebuilt every few ticks. */
    private static final ImpostorBatch far = new ImpostorBatch("far batch");
    private static int ticks;
    private static boolean anyBatched;
    /** Bumped when the atlas is cleared, so cached slots are looked up again. */
    private static int generation;

    // Stats for /crowdperf stats.
    static int lastBatched;
    static int lastQuads;
    static long buildNanos;
    static int builds;

    private Impostors() {}

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        CrowdConfig config = CrowdPerf.config();
        if (level == null) {
            clearBatches();
            return;
        }
        if (!config.batch) {
            if (anyBatched) release(level);
            clearBatches();
            return;
        }
        if (++ticks % Math.max(1, config.rebuildTicks) != 0) return;
        long start = System.nanoTime();
        rebuild(mc, level, config);
        buildNanos += System.nanoTime() - start;
        builds++;
    }

    /** Puts every batched entity back to normal rendering. */
    static void release(ClientLevel level) {
        for (Entity entity : level.entitiesForRendering()) ((CrowdEntity) entity).crowdperf$setBatched(false);
        anyBatched = false;
        lastBatched = 0;
    }

    /** Forgets baked meshes and the atlas (after a resource reload or on leaving a world). */
    public static void reset() {
        atlas.clear();
        generation++;
        villagerMesh = babyMesh = traderMesh = null;
        clearBatches();
    }

    private static void clearBatches() {
        near.clear();
        far.clear();
    }

    private static void rebuild(Minecraft mc, ClientLevel level, CrowdConfig config) {
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double enter = (config.batchDistance + 1.0) * (config.batchDistance + 1.0);
        double leave = (config.batchDistance - 1.0) * (config.batchDistance - 1.0);
        double low = config.lowDetailDistance * config.lowDetailDistance;
        double farFrom = config.farDistance * config.farDistance;
        double view = mc.options.getEffectiveRenderDistance() * 16.0;
        double max = view * view;
        Entity camera = mc.getCameraEntity();
        if (villagerMesh == null) bake(mc.getEntityModels());
        // The far batch is rebuilt every few ticks; in between, villagers it holds stay in it (and are skipped here),
        // so none is drawn twice or vanishes while crossing over.
        boolean farTurn = ticks % Math.max(1, config.farRebuildTicks) == 0;
        near.begin(cam);
        if (farTurn) far.begin(cam);

        int batched = 0;
        for (Entity entity : level.entitiesForRendering()) {
            CrowdEntity crowd = (CrowdEntity) entity;
            if (!(entity instanceof AbstractVillager villager)) {
                if (crowd.crowdperf$batched()) crowd.crowdperf$setBatched(false);
                continue;
            }
            double dx = entity.getX() - cam.x;
            double dy = entity.getY() - cam.y;
            double dz = entity.getZ() - cam.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            boolean want = d2 > (crowd.crowdperf$batched() ? leave : enter) && batched < config.maxBatched && entity != camera
                && plain(mc, villager);
            ImpostorMesh mesh = want ? meshOf(villager) : null;
            int slot = mesh != null ? cachedSlot(villager, crowd) : -1;
            want = slot >= 0 && d2 <= max;
            boolean wasFar = crowd.crowdperf$batched() && crowd.crowdperf$far();
            crowd.crowdperf$setBatched(want);
            if (!want) continue;
            batched++;
            ImpostorBatch batch;
            if (farTurn) {
                boolean isFar = d2 > farFrom;
                crowd.crowdperf$setFar(isFar);
                batch = isFar ? far : near;
            } else if (wasFar) {
                continue;
            } else {
                crowd.crowdperf$setFar(false);
                batch = near;
            }
            float[] data = d2 > low ? mesh.low : mesh.full;
            int vertices = data.length / ImpostorMesh.FLOATS;
            write(batch, batch.reserve(vertices), data, vertices, villager, slot, light(level, entity, crowd));
            batch.commit(vertices);
        }
        anyBatched = batched > 0;
        lastBatched = batched;
        atlas.flush();
        near.finish();
        if (farTurn) far.finish();
        lastQuads = near.quads() + far.quads();
    }

    /**
     * The entity's atlas slot, cached on the entity: the look only changes when the villager data object is replaced
     * (new profession or biome) or the villager grows up, so the key is that object plus the atlas generation.
     */
    private static int cachedSlot(AbstractVillager villager, CrowdEntity crowd) {
        Object data = villager instanceof Villager v ? v.getVillagerData() : villager.getType();
        Object key = crowd.crowdperf$lookKey();
        if (key instanceof LookKey k && k.data == data && k.baby == villager.isBaby() && k.generation == generation) return crowd.crowdperf$slot();
        int slot = slotOf(villager);
        crowd.crowdperf$setSlot(slot, new LookKey(data, villager.isBaby(), generation));
        return slot;
    }

    private record LookKey(Object data, boolean baby, int generation) {}

    /** Whether the villager looks like its rest pose: anything special keeps it a normal entity. */
    private static boolean plain(Minecraft mc, AbstractVillager v) {
        return !v.isInvisible() && !v.isPassenger() && !v.isVehicle() && !v.isSleeping() && v.deathTime == 0 && v.hurtTime == 0
            && !v.isLeashed() && !(v.hasCustomName() && v.isCustomNameVisible()) && !mc.shouldEntityAppearGlowing(v);
    }

    private static void bake(EntityModelSet models) {
        // Far away the nose and the hat are a pixel or two; the low mesh leaves them out.
        villagerMesh = ImpostorMesh.bake(models.bakeLayer(ModelLayers.VILLAGER), path -> !path.contains("nose") && !path.contains("hat"));
        babyMesh = ImpostorMesh.bake(models.bakeLayer(ModelLayers.VILLAGER_BABY), path -> !path.contains("nose") && !path.contains("hat"));
        traderMesh = ImpostorMesh.bake(models.bakeLayer(ModelLayers.WANDERING_TRADER), path -> !path.contains("nose") && !path.contains("hat"));
    }

    private static ImpostorMesh meshOf(AbstractVillager villager) {
        if (villager instanceof WanderingTrader) return traderMesh;
        if (villager instanceof Villager) return villager.isBaby() ? babyMesh : villagerMesh;
        return null;
    }

    /** The atlas slot for the villager's look, built like VillagerProfessionLayer stacks its layers. */
    private static int slotOf(AbstractVillager villager) {
        if (villager instanceof WanderingTrader) return atlas.slot("trader", TRADER_SKIN, List.of());
        if (!(villager instanceof Villager v)) return -1;
        VillagerData data = v.getVillagerData();
        boolean baby = v.isBaby();
        Identifier type = key(data.type());
        Holder<VillagerProfession> profession = data.profession();
        boolean working = !baby && !profession.is(VillagerProfession.NONE);
        Identifier job = working ? key(profession) : null;
        String look = (baby ? "baby|" : "adult|") + type + "|" + job;
        // Fast path: the look is usually already in the atlas.
        int known = atlas.slot(look, null, null);
        if (known >= 0 || type == null) return known;
        List<ImpostorAtlas.Layer> layers = new ArrayList<>(2);
        Identifier typeTexture = texture(baby ? "baby" : "type", type);
        Identifier jobTexture = job != null ? texture("profession", job) : null;
        var typeHat = atlas.hat(texture("type", type));
        var jobHat = job != null ? atlas.hat(jobTexture) : VillagerMetadataSection.Hat.NONE;
        boolean typeHatVisible = jobHat == VillagerMetadataSection.Hat.NONE
            || jobHat == VillagerMetadataSection.Hat.PARTIAL
                && typeHat != VillagerMetadataSection.Hat.FULL;
        layers.add(new ImpostorAtlas.Layer(typeTexture, !baby && !typeHatVisible));
        if (jobTexture != null) layers.add(new ImpostorAtlas.Layer(jobTexture, false));
        return atlas.slot(look, baby ? BABY_SKIN : VILLAGER_SKIN, layers);
    }

    private static Identifier key(Holder<?> holder) {
        return holder.unwrapKey().map(k -> k.identifier()).orElse(null);
    }

    private static Identifier texture(String folder, Identifier key) {
        return key.withPath(path -> "textures/entity/villager/" + folder + "/" + path + ".png");
    }

    private static final BlockPos.MutableBlockPos LIGHT_POS = new BlockPos.MutableBlockPos();

    /** Light at the villager's eyes, as the entity renderer samples it; looked up again when it moves to another block. */
    private static int light(ClientLevel level, Entity entity, CrowdEntity crowd) {
        LIGHT_POS.set(entity.getX(), entity.getEyeY(), entity.getZ());
        long key = LIGHT_POS.asLong() ^ ((long) (ticks / 100) << 58);
        if (crowd.crowdperf$lightKey() == key) return crowd.crowdperf$light();
        int block = entity.isOnFire() ? 15 : level.getBrightness(LightLayer.BLOCK, LIGHT_POS);
        int light = LightCoordsUtil.pack(block, level.getBrightness(LightLayer.SKY, LIGHT_POS));
        crowd.crowdperf$setLight(light, key);
        return light;
    }

    private static void write(ImpostorBatch batch, long pointer, float[] data, int vertices, AbstractVillager v, int slot, int light) {
        // As LivingEntityRenderer.setupRotations: turn the body by (180 - bodyRot) around Y.
        float angle = (float) Math.toRadians(180.0F - v.yBodyRot);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        float scale = v.getScale();
        float px = (float) (v.getX() - batch.originX);
        float py = (float) (v.getY() - batch.originY);
        float pz = (float) (v.getZ() - batch.originZ);
        float su = slot % ImpostorAtlas.COLUMNS;
        float sv = slot / ImpostorAtlas.COLUMNS;
        float cu = 1.0F / ImpostorAtlas.COLUMNS;
        float cv = 1.0F / ImpostorAtlas.ROWS;
        int overlay = OverlayTexture.NO_OVERLAY;
        for (int i = 0, k = 0; i < vertices; i++, k += ImpostorMesh.FLOATS, pointer += VERTEX_BYTES) {
            float x = data[k] * scale;
            float y = data[k + 1] * scale;
            float z = data[k + 2] * scale;
            float nx = data[k + 5];
            float nz = data[k + 7];
            MemoryUtil.memPutFloat(pointer, px + x * cos + z * sin);
            MemoryUtil.memPutFloat(pointer + 4, py + y);
            MemoryUtil.memPutFloat(pointer + 8, pz - x * sin + z * cos);
            MemoryUtil.memPutInt(pointer + 12, WHITE);
            MemoryUtil.memPutFloat(pointer + 16, (su + data[k + 3]) * cu);
            MemoryUtil.memPutFloat(pointer + 20, (sv + data[k + 4]) * cv);
            MemoryUtil.memPutInt(pointer + 24, overlay);
            MemoryUtil.memPutInt(pointer + 28, light);
            MemoryUtil.memPutByte(pointer + 32, normal(nx * cos + nz * sin));
            MemoryUtil.memPutByte(pointer + 33, normal(data[k + 6]));
            MemoryUtil.memPutByte(pointer + 34, normal(-nx * sin + nz * cos));
            MemoryUtil.memPutByte(pointer + 35, (byte) 0);
        }
    }

    private static byte normal(float c) {
        return (byte) ((int) (Math.clamp(c, -1.0F, 1.0F) * 127.0F) & 0xFF);
    }

    /** Draws both batches; called inside the solid pass of the main render target. */
    public static void draw(RenderPass pass) {
        if (!CrowdPerf.config().batch) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.mainCamera().position();
        near.draw(pass, cam);
        far.draw(pass, cam);
    }

    static String stats() {
        long avg = builds == 0 ? 0 : buildNanos / builds / 1000;
        return "batched " + lastBatched + ", quads " + lastQuads + ", build " + avg + " µs avg over " + builds + ", looks " + atlas.size();
    }

    static void resetStats() {
        buildNanos = 0;
        builds = 0;
    }
}
