package com.grandstrategy.crowdperf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import java.nio.ByteBuffer;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU side of the crowd (GPU instancing as in Flywheel, or "skinned instancing" from GPU Gems 3). The baked meshes live
 * in one static vertex buffer. Every tick the villagers are written as instances (76 bytes each) into a CPU buffer,
 * grouped by mesh and, within a mesh, by chunk ("cells", each with a bounding box). Every frame, before the passes,
 * the cells outside the camera's view are dropped, the rest are copied into one GPU instance buffer, and each mesh
 * group is drawn with a single instanced call; the vertex shader interpolates and animates them.
 */
final class CrowdRenderer {
    /** Mesh vertex: position, uv, normal, pivot, part. */
    static final VertexFormat MESH = VertexFormat.builder(0)
        .addAttribute("Position", GpuFormat.RGB32_FLOAT)
        .addAttribute("UV0", GpuFormat.RG32_FLOAT)
        .addAttribute("Normal", GpuFormat.RGBA8_SNORM)
        .addAttribute("Pivot", GpuFormat.RGB32_FLOAT)
        .addAttribute("Part", GpuFormat.R32_FLOAT)
        .build();
    /** One villager: positions at the last two ticks, body/head yaw, walk cycle, look, light. */
    static final VertexFormat INSTANCE = VertexFormat.builder(1)
        .addAttribute("OldPos", GpuFormat.RGB32_FLOAT)
        .addAttribute("NewPos", GpuFormat.RGB32_FLOAT)
        .addAttribute("Turn", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Pose", GpuFormat.RGBA32_FLOAT)
        .addAttribute("Look", GpuFormat.RGBA32_FLOAT)
        .addAttribute("UV2", GpuFormat.RG16_SINT)
        .build();
    static final int MESH_BYTES = MESH.getVertexSize();
    static final int INSTANCE_BYTES = INSTANCE.getVertexSize();
    /** GPU instance buffers rotate per frame, so a frame's upload never touches a buffer still being drawn. */
    private static final int RING = 4;
    /** Debug: cull and upload every frame even when nothing moved, as a camera turned by the mouse would. */
    private static final boolean RECULL_EVERY_FRAME = Boolean.getBoolean("crowdperf.debugRecull");

    /** Vanilla entity look (cutout, per-face lighting, lightmap, fog) with the crowd vertex shader in front. */
    static final RenderPipeline PIPELINE = RenderPipeline.builder(RenderPipelines.MATRICES_FOG_LIGHT_DIR_SNIPPET)
        .withLocation(Identifier.fromNamespaceAndPath("crowdperf", "pipeline/crowd"))
        .withVertexShader(Identifier.fromNamespaceAndPath("crowdperf", "core/crowd"))
        .withFragmentShader("core/entity")
        .withBindGroupLayout(BindGroupLayouts.SAMPLER0)
        .withBindGroupLayout(BindGroupLayouts.SAMPLER2)
        .withVertexBinding(0, MESH)
        .withVertexBinding(1, INSTANCE)
        .withPrimitiveTopology(PrimitiveTopology.QUADS)
        .withDepthStencilState(DepthStencilState.DEFAULT)
        .withColorTargetState(ColorTargetState.DEFAULT)
        .withCull(false)
        .withShaderDefine("ALPHA_CUTOUT", 0.1F)
        .withShaderDefine("PER_FACE_LIGHTING")
        .withShaderDefine("NO_OVERLAY")
        .build();

    /** A draw: a range of the mesh buffer, and the range of this frame's instance buffer it uses. */
    static final class Group {
        long meshOffset;
        int quads;
        /** Instances this tick (all), and in view this frame. */
        int count;
        int first;
        int visible;
    }

    private GpuBuffer meshes;
    private ByteBuffer tickData;
    private ByteBuffer frameData;
    private final GpuBuffer[] instances = new GpuBuffer[RING];
    private int frame;
    private boolean uploaded;
    private int written;
    private boolean broken;
    double originX, originY, originZ;

    // Cells: runs of instances of one group within one chunk, with their bounding box (world coordinates).
    private int cells;
    private int[] cellGroup = new int[256];
    private int[] cellFirst = new int[256];
    private int[] cellCount = new int[256];
    private double[] cellBox = new double[256 * 6];
    // Stats.
    int lastVisible;
    int lastCells;

    boolean broken() {
        return broken;
    }

    /** Uploads the baked meshes once; returns each mesh's byte offset in the mesh buffer, in the order given. */
    long[] uploadMeshes(float[]... meshes) {
        long[] offsets = new long[meshes.length];
        int vertices = 0;
        for (float[] mesh : meshes) vertices += mesh.length / ImpostorMesh.FLOATS;
        ByteBuffer data = MemoryUtil.memAlloc(vertices * MESH_BYTES);
        try {
            long pointer = MemoryUtil.memAddress(data);
            long offset = 0;
            for (int m = 0; m < meshes.length; m++) {
                offsets[m] = offset;
                float[] d = meshes[m];
                for (int k = 0; k < d.length; k += ImpostorMesh.FLOATS, offset += MESH_BYTES) {
                    long p = pointer + offset;
                    MemoryUtil.memPutFloat(p, d[k]);
                    MemoryUtil.memPutFloat(p + 4, d[k + 1]);
                    MemoryUtil.memPutFloat(p + 8, d[k + 2]);
                    MemoryUtil.memPutFloat(p + 12, d[k + 3]);
                    MemoryUtil.memPutFloat(p + 16, d[k + 4]);
                    MemoryUtil.memPutByte(p + 20, snorm(d[k + 5]));
                    MemoryUtil.memPutByte(p + 21, snorm(d[k + 6]));
                    MemoryUtil.memPutByte(p + 22, snorm(d[k + 7]));
                    MemoryUtil.memPutByte(p + 23, (byte) 0);
                    MemoryUtil.memPutFloat(p + 24, d[k + 9]);
                    MemoryUtil.memPutFloat(p + 28, d[k + 10]);
                    MemoryUtil.memPutFloat(p + 32, d[k + 11]);
                    MemoryUtil.memPutFloat(p + 36, d[k + 8]);
                }
            }
            if (this.meshes != null) this.meshes.close();
            this.meshes = RenderSystem.getDevice().createBuffer(() -> "CrowdPerf meshes", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, data);
        } finally {
            MemoryUtil.memFree(data);
        }
        return offsets;
    }

    private static byte snorm(float c) {
        return (byte) Math.round(Math.clamp(c, -1.0F, 1.0F) * 127.0F);
    }

    // ---- Tick side ----

    void begin(Vec3 cam) {
        originX = Math.floor(cam.x);
        originY = Math.floor(cam.y);
        originZ = Math.floor(cam.z);
        written = 0;
        cells = 0;
    }

    /** Address of the next instance; call {@link #commit} after writing it. */
    long next() {
        tickData = ensure(tickData, (written + 1) * INSTANCE_BYTES, (long) written * INSTANCE_BYTES);
        return MemoryUtil.memAddress(tickData) + (long) written * INSTANCE_BYTES;
    }

    /** Records the instance just written as part of {@code group}, standing at world (x, y, z) in chunk {@code chunk}. */
    void commit(int group, long chunk, double x, double y, double z, double height) {
        int c = cells - 1;
        if (c < 0 || cellGroup[c] != group || cellKey != chunk) {
            c = cells++;
            if (c == cellGroup.length) {
                cellGroup = Arrays.copyOf(cellGroup, c * 2);
                cellFirst = Arrays.copyOf(cellFirst, c * 2);
                cellCount = Arrays.copyOf(cellCount, c * 2);
                cellBox = Arrays.copyOf(cellBox, c * 12);
            }
            cellGroup[c] = group;
            cellFirst[c] = written;
            cellCount[c] = 0;
            cellKey = chunk;
            double[] b = cellBox;
            b[c * 6] = b[c * 6 + 3] = x;
            b[c * 6 + 1] = b[c * 6 + 4] = y;
            b[c * 6 + 2] = b[c * 6 + 5] = z;
        }
        cellCount[c]++;
        double[] b = cellBox;
        int o = c * 6;
        b[o] = Math.min(b[o], x);
        b[o + 1] = Math.min(b[o + 1], y);
        b[o + 2] = Math.min(b[o + 2], z);
        b[o + 3] = Math.max(b[o + 3], x);
        b[o + 4] = Math.max(b[o + 4], y + height);
        b[o + 5] = Math.max(b[o + 5], z);
        written++;
    }

    private long cellKey;
    private double lastCamX, lastCamY, lastCamZ;
    private float lastYaw, lastPitch;

    int written() {
        return written;
    }

    /** The tick's instances are complete; the next frame culls and uploads them. */
    void finish(int maxQuads) {
        uploaded = false;
        // Grow the shared quad index buffer now, outside any render pass.
        if (written > 0) RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(maxQuads * 6);
    }

    // ---- Frame side ----

    /**
     * Keeps the cells the camera can see and uploads their instances, group by group. Runs once per frame after the
     * level is extracted and before any render pass (uploads are not allowed inside one).
     */
    void cull(Frustum frustum, Group[] groups, boolean enabled, double camX, double camY, double camZ, float yaw, float pitch) {
        // Most frames see the same tick from the same spot: the last upload still holds.
        if (uploaded && !RECULL_EVERY_FRAME && camX == lastCamX && camY == lastCamY && camZ == lastCamZ && yaw == lastYaw && pitch == lastPitch) return;
        lastCamX = camX;
        lastCamY = camY;
        lastCamZ = camZ;
        lastYaw = yaw;
        lastPitch = pitch;
        for (Group g : groups) g.visible = 0;
        if (broken || written == 0) {
            lastVisible = 0;
            return;
        }
        frameData = ensure(frameData, written * INSTANCE_BYTES, 0);
        long src = MemoryUtil.memAddress(tickData);
        long dst = MemoryUtil.memAddress(frameData);
        int out = 0;
        int c = 0;
        // Cells are in group order, so each group's visible instances end up contiguous.
        for (int g = 0; g < groups.length; g++) {
            Group group = groups[g];
            group.first = out;
            for (; c < cells && cellGroup[c] == g; c++) {
                int o = c * 6;
                // A villager is about 0.6 wide; a tick's walk is well under a block.
                if (enabled && !frustum.isVisible(new AABB(cellBox[o] - 1.0, cellBox[o + 1] - 0.5, cellBox[o + 2] - 1.0,
                    cellBox[o + 3] + 1.0, cellBox[o + 4] + 0.5, cellBox[o + 5] + 1.0))) continue;
                MemoryUtil.memCopy(src + (long) cellFirst[c] * INSTANCE_BYTES, dst + (long) out * INSTANCE_BYTES, (long) cellCount[c] * INSTANCE_BYTES);
                out += cellCount[c];
            }
            group.visible = out - group.first;
        }
        lastVisible = out;
        lastCells = cells;
        if (out == 0) return;
        int bytes = out * INSTANCE_BYTES;
        frame = (frame + 1) % RING;
        GpuBuffer buffer = instances[frame];
        if (buffer == null || buffer.size() < bytes) {
            if (buffer != null) buffer.close();
            buffer = RenderSystem.getDevice().createBuffer(() -> "CrowdPerf instances", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
                Math.max((long) bytes * 2, 64 * 1024));
            instances[frame] = buffer;
        }
        frameData.limit(bytes).position(0);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, bytes), frameData);
        frameData.clear();
        uploaded = true;
    }

    /** Draws the visible groups inside the solid pass of the main render target. */
    void draw(RenderPass pass, Group[] groups, Identifier atlas, float cell) {
        GpuBuffer buffer = instances[frame];
        if (broken || meshes == null || buffer == null || !uploaded) return;
        boolean any = false;
        for (Group g : groups) any |= g.visible > 0;
        if (!any) return;
        CompiledRenderPipeline compiled;
        try {
            compiled = RenderSystem.getCompiledPipeline(PIPELINE);
        } catch (RuntimeException e) {
            broken = true;
            CrowdPerf.LOGGER.error("CrowdPerf: the crowd shader did not compile; villagers are drawn normally", e);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.mainCamera().position();
        float partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(true);
        AbstractTexture texture = mc.getTextureManager().getTexture(atlas);
        var transforms = RenderSystem.getDynamicUniforms().writeTransform(
            RenderSystem.getModelViewMatrixCopy(),
            new Vector4f(1.0F, 1.0F, 1.0F, 1.0F),
            new Vector3f((float) (originX - cam.x), (float) (originY - cam.y), (float) (originZ - cam.z)),
            new Matrix4f().translation(partialTick, cell, 0.0F));
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.pushDebugGroup(() -> "CrowdPerf crowd");
        pass.setPipeline(compiled);
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", transforms);
        pass.setUniform("Sampler0", texture.getTextureView(), texture.getSampler());
        pass.setUniform("Sampler2", mc.gameRenderer.lightmap(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
        pass.setIndexBuffer(indices.getBuffer(), indices.type());
        for (Group g : groups) {
            if (g.visible == 0) continue;
            pass.setVertexBuffer(0, meshes.slice(g.meshOffset, (long) g.quads * 4 * MESH_BYTES));
            pass.setVertexBuffer(1, buffer.slice((long) g.first * INSTANCE_BYTES, (long) g.visible * INSTANCE_BYTES));
            pass.drawIndexed(g.quads * 6, g.visible, 0, 0, 0);
        }
        pass.popDebugGroup();
    }

    /** Grows a native buffer to at least {@code need} bytes, keeping its first {@code keep} bytes. */
    private static ByteBuffer ensure(ByteBuffer buffer, int need, long keep) {
        if (buffer != null && buffer.capacity() >= need) return buffer;
        ByteBuffer bigger = MemoryUtil.memAlloc(Math.max(need * 2, 64 * 1024));
        if (buffer != null) {
            if (keep > 0) MemoryUtil.memCopy(MemoryUtil.memAddress(buffer), MemoryUtil.memAddress(bigger), keep);
            MemoryUtil.memFree(buffer);
        }
        return bigger;
    }

    void close() {
        if (meshes != null) meshes.close();
        meshes = null;
        for (int i = 0; i < instances.length; i++) {
            if (instances[i] != null) instances[i].close();
            instances[i] = null;
        }
        written = 0;
        cells = 0;
        uploaded = false;
    }
}
