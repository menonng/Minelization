package com.grandstrategy.crowdperf;

import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.metadata.animation.VillagerMetadataSection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.util.Mth;
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
 * The crowd. Every tick, the villagers past {@link CrowdConfig#batchDistance} become GPU instances: 76 bytes each
 * (where they were at the last two ticks, body and head yaw, walk cycle, look, light). The frame then draws them all
 * with a handful of instanced calls ({@link CrowdRenderer}); the vertex shader interpolates them to the frame, swings
 * their legs and turns their heads, so they move exactly as smoothly as normal villagers. Vanilla instead extracts a
 * render state, poses a model and builds every cube of every villager anew each frame.
 * <p>
 * Batched villagers are flagged ({@link CrowdEntity}) so the normal entity pass skips them, and they tick lightly
 * ({@code ClientLevelMixin}). Close villagers, and any villager that needs more than the model and walk cycle
 * (glowing, named, hurt, dying, sleeping, riding, leashed, invisible), stay ordinary entities.
 */
public final class Impostors {
    private static final Identifier VILLAGER_SKIN = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final Identifier BABY_SKIN = Identifier.withDefaultNamespace("textures/entity/villager/villager_baby.png");
    private static final Identifier TRADER_SKIN = Identifier.withDefaultNamespace("textures/entity/wandering_trader/wandering_trader.png");
    private static final String[] LEVELS = {"stone", "iron", "gold", "emerald", "diamond"};

    /** Mesh kinds; each has a full and a low-detail group. */
    private static final int VILLAGER = 0, BABY = 1, TRADER = 2, KINDS = 3;

    private static final ImpostorAtlas atlas = new ImpostorAtlas();
    private static final CrowdRenderer renderer = new CrowdRenderer();
    private static final CrowdRenderer.Group[] groups = new CrowdRenderer.Group[KINDS * 2];
    @SuppressWarnings("unchecked")
    private static final List<AbstractVillager>[] members = new List[KINDS * 2];
    private static ImpostorMesh[] meshes;
    private static int maxQuads;

    static {
        for (int i = 0; i < groups.length; i++) {
            groups[i] = new CrowdRenderer.Group();
            members[i] = new ArrayList<>();
        }
    }

    private static int ticks;
    /** An Iris shader pack is on: villagers are drawn normally (see {@link ShaderPacks}). */
    private static boolean shaderPack;
    private static boolean anyBatched;
    /** Bumped when the atlas is cleared, so cached slots are looked up again. */
    private static int generation;

    // Stats for /crowdperf.
    static int lastBatched;
    /** Villagers left to the normal renderer last tick: close, needing a special pose or look, or anything else. */
    static int lastNear, lastSpecial, lastOther;
    /** Batched but past the view distance, so not drawn. */
    static int lastHidden;
    static long buildNanos;
    static int builds;

    private Impostors() {}

    public static void tick(Minecraft mc) {
        ClientLevel level = mc.level;
        CrowdConfig config = CrowdPerf.config();
        if (level == null) {
            clearGroups();
            return;
        }
        shaderPack = ShaderPacks.inUse();
        if (!config.batch || renderer.broken() || shaderPack) {
            if (anyBatched) release(level);
            clearGroups();
            return;
        }
        ticks++;
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

    /** Forgets baked meshes, GPU buffers and the atlas (after a resource reload or on leaving a world). */
    public static void reset() {
        atlas.clear();
        generation++;
        meshes = null;
        renderer.close();
        clearGroups();
    }

    private static void clearGroups() {
        for (CrowdRenderer.Group g : groups) {
            g.count = 0;
            g.visible = 0;
        }
    }

    private static void rebuild(Minecraft mc, ClientLevel level, CrowdConfig config) {
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        double enter = (config.batchDistance + 1.0) * (config.batchDistance + 1.0);
        double leave = (config.batchDistance - 1.0) * (config.batchDistance - 1.0);
        double low = config.lowDetailDistance * config.lowDetailDistance;
        double view = mc.options.getEffectiveRenderDistance() * 16.0;
        double max = view * view;
        Entity camera = mc.getCameraEntity();
        if (meshes == null) bake(mc.getEntityModels());

        for (List<AbstractVillager> list : members) list.clear();
        int batched = 0;
        lastNear = lastSpecial = lastOther = lastHidden = 0;
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
            int kind = kindOf(villager);
            boolean near = d2 <= (crowd.crowdperf$batched() ? leave : enter);
            boolean special = !near && !plain(mc, villager);
            boolean want = kind >= 0 && !near && batched < config.maxBatched && entity != camera && !special
                && cachedSlot(villager, crowd) >= 0;
            crowd.crowdperf$setBatched(want);
            if (!want) {
                if (near) lastNear++;
                else if (special) lastSpecial++;
                else lastOther++;
                continue;
            }
            // Past the view distance (the corners of the loaded square) the fog hides them anyway: they stay
            // batched, so the normal renderer skips them and they tick lightly, but are not drawn.
            if (d2 > max) {
                lastHidden++;
                continue;
            }
            batched++;
            members[kind * 2 + (d2 > low ? 1 : 0)].add(villager);
        }
        anyBatched = batched > 0;
        lastBatched = batched;
        atlas.flush();

        renderer.begin(cam);
        for (int g = 0; g < groups.length; g++) {
            CrowdRenderer.Group group = groups[g];
            List<AbstractVillager> list = members[g];
            // Chunk by chunk, so each frame can drop whole chunks the camera does not see.
            long[] order = sortKeys(list);
            for (int k = 0; k < list.size(); k++) {
                AbstractVillager villager = list.get((int) (order[k] & 0xFFFFFFFFL));
                write(renderer.next(), villager, (CrowdEntity) villager, level);
                renderer.commit(g, order[k] >>> 32, villager.getX(), villager.getY(), villager.getZ(), 2.1 * villager.getScale());
            }
            group.count = list.size();
        }
        renderer.finish(maxQuads);
    }

    private static long[] sortKeys = new long[1024];

    /** (chunk x, chunk z, index) packed so that sorting groups the list by chunk. */
    private static long[] sortKeys(List<AbstractVillager> list) {
        int n = list.size();
        if (sortKeys.length < n) sortKeys = new long[n * 2];
        for (int i = 0; i < n; i++) {
            AbstractVillager v = list.get(i);
            long cx = (Mth.floor(v.getX()) >> 4) & 0xFFFF;
            long cz = (Mth.floor(v.getZ()) >> 4) & 0xFFFF;
            sortKeys[i] = cx << 48 | cz << 32 | i;
        }
        java.util.Arrays.sort(sortKeys, 0, n);
        return sortKeys;
    }

    /** Culls and uploads the crowd for this frame; called after the level is extracted, before the render passes. */
    public static void cull(net.minecraft.client.Camera camera) {
        if (!CrowdPerf.config().batch || shaderPack) return;
        Vec3 pos = camera.position();
        renderer.cull(camera.getCullFrustum(), groups, CrowdPerf.config().frustumCulling, pos.x, pos.y, pos.z, camera.yRot(), camera.xRot());
    }

    private static int kindOf(AbstractVillager villager) {
        if (villager instanceof WanderingTrader) return TRADER;
        if (villager instanceof Villager) return villager.isBaby() ? BABY : VILLAGER;
        return -1;
    }

    /**
     * One instance, in {@link CrowdRenderer#INSTANCE} layout. Rotations are unwrapped so the shader can interpolate
     * them linearly, like Mth.rotLerp does.
     */
    private static void write(long p, AbstractVillager v, CrowdEntity crowd, ClientLevel level) {
        float bodyOld = v.yBodyRotO;
        float bodyNew = bodyOld + Mth.wrapDegrees(v.yBodyRot - bodyOld);
        float headOld = bodyOld + Mth.wrapDegrees(v.yHeadRotO - bodyOld);
        float headNew = headOld + Mth.wrapDegrees(v.yHeadRot - headOld);
        MemoryUtil.memPutFloat(p, (float) (v.xo - renderer.originX));
        MemoryUtil.memPutFloat(p + 4, (float) (v.yo - renderer.originY));
        MemoryUtil.memPutFloat(p + 8, (float) (v.zo - renderer.originZ));
        MemoryUtil.memPutFloat(p + 12, (float) (v.getX() - renderer.originX));
        MemoryUtil.memPutFloat(p + 16, (float) (v.getY() - renderer.originY));
        MemoryUtil.memPutFloat(p + 20, (float) (v.getZ() - renderer.originZ));
        MemoryUtil.memPutFloat(p + 24, bodyOld);
        MemoryUtil.memPutFloat(p + 28, bodyNew);
        MemoryUtil.memPutFloat(p + 32, headOld);
        MemoryUtil.memPutFloat(p + 36, headNew);
        // WalkAnimationState: position(t) runs linearly from position(0) to position(1); speed(t) likewise.
        MemoryUtil.memPutFloat(p + 40, v.walkAnimation.position(0.0F));
        MemoryUtil.memPutFloat(p + 44, v.walkAnimation.position(1.0F));
        MemoryUtil.memPutFloat(p + 48, v.walkAnimation.speed(0.0F));
        MemoryUtil.memPutFloat(p + 52, v.walkAnimation.speed(1.0F));
        int slot = crowd.crowdperf$slot();
        MemoryUtil.memPutFloat(p + 56, slot % ImpostorAtlas.COLUMNS);
        MemoryUtil.memPutFloat(p + 60, slot / ImpostorAtlas.COLUMNS);
        MemoryUtil.memPutFloat(p + 64, v.getScale());
        MemoryUtil.memPutFloat(p + 68, v.getXRot());
        MemoryUtil.memPutInt(p + 72, light(level, v, crowd));
    }

    /**
     * The entity's atlas slot, cached on the entity: the look only changes when the villager data object is replaced
     * (new profession, level or biome) or the villager grows up, so the key is that object plus the atlas generation.
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

    /** Whether the villager is drawn by the model and walk cycle alone: anything special keeps it a normal entity. */
    private static boolean plain(Minecraft mc, AbstractVillager v) {
        return !v.isInvisible() && !v.isPassenger() && !v.isVehicle() && !v.isSleeping() && v.deathTime == 0 && v.hurtTime == 0
            && !v.isLeashed() && !(v.hasCustomName() && v.isCustomNameVisible()) && !mc.shouldEntityAppearGlowing(v);
    }

    private static void bake(EntityModelSet models) {
        // Far away the nose and the hat are a pixel or two; the low mesh leaves them out.
        ImpostorMesh villager = ImpostorMesh.bake(models.bakeLayer(ModelLayers.VILLAGER), path -> !path.contains("nose") && !path.contains("hat"));
        ImpostorMesh baby = ImpostorMesh.bake(models.bakeLayer(ModelLayers.VILLAGER_BABY), path -> !path.contains("nose") && !path.contains("hat"));
        ImpostorMesh trader = ImpostorMesh.bake(models.bakeLayer(ModelLayers.WANDERING_TRADER), path -> !path.contains("nose") && !path.contains("hat"));
        meshes = new ImpostorMesh[] {villager, baby, trader};
        long[] offsets = renderer.uploadMeshes(villager.full, villager.low, baby.full, baby.low, trader.full, trader.low);
        maxQuads = 0;
        for (int kind = 0; kind < KINDS; kind++) {
            for (int lod = 0; lod < 2; lod++) {
                CrowdRenderer.Group group = groups[kind * 2 + lod];
                group.meshOffset = offsets[kind * 2 + lod];
                group.quads = meshes[kind].quads(lod == 1);
                maxQuads = Math.max(maxQuads, group.quads);
            }
        }
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
        boolean badge = working && !profession.is(VillagerProfession.NITWIT);
        int level = Math.clamp(data.level(), 1, LEVELS.length);
        String look = (baby ? "baby|" : "adult|") + type + "|" + job + "|" + (badge ? level : 0);
        // Fast path: the look is usually already in the atlas.
        int known = atlas.slot(look, null, null);
        if (known >= 0 || type == null) return known;
        List<ImpostorAtlas.Layer> layers = new ArrayList<>(3);
        Identifier typeTexture = texture(baby ? "baby" : "type", type);
        Identifier jobTexture = job != null ? texture("profession", job) : null;
        var typeHat = atlas.hat(texture("type", type));
        var jobHat = job != null ? atlas.hat(jobTexture) : VillagerMetadataSection.Hat.NONE;
        boolean typeHatVisible = jobHat == VillagerMetadataSection.Hat.NONE
            || jobHat == VillagerMetadataSection.Hat.PARTIAL && typeHat != VillagerMetadataSection.Hat.FULL;
        layers.add(new ImpostorAtlas.Layer(typeTexture, !baby && !typeHatVisible));
        if (jobTexture != null) layers.add(new ImpostorAtlas.Layer(jobTexture, false));
        if (badge) layers.add(new ImpostorAtlas.Layer(texture("profession_level", Identifier.withDefaultNamespace(LEVELS[level - 1])), false));
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

    /** Draws the crowd; called inside the solid pass of the main render target. */
    public static void draw(RenderPass pass) {
        if (!CrowdPerf.config().batch || shaderPack) return;
        renderer.draw(pass, groups, ImpostorAtlas.ID, 1.0F / ImpostorAtlas.COLUMNS);
    }

    static String stats() {
        long avg = builds == 0 ? 0 : buildNanos / builds / 1000;
        int quads = 0;
        for (CrowdRenderer.Group g : groups) quads += g.visible * g.quads;
        return "batched " + lastBatched + " + hidden " + lastHidden + " (normal: near " + lastNear + ", special " + lastSpecial + ", other " + lastOther + "), in view " + renderer.lastVisible + " in " + renderer.lastCells + " cells, quads " + quads + ", build " + avg + " µs avg over " + builds + ", looks " + atlas.size()
            + (renderer.broken() ? ", SHADER FAILED" : "") + (shaderPack ? ", shader pack on: villagers drawn normally" : "");
    }

    static void resetStats() {
        buildNanos = 0;
        builds = 0;
    }
}
