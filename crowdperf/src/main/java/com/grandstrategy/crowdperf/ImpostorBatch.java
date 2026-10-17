package com.grandstrategy.crowdperf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fStack;
import org.lwjgl.system.MemoryUtil;

/**
 * One GPU-resident vertex buffer of batched villagers: filled on the CPU when rebuilt, uploaded once, then drawn every
 * frame until the next rebuild. Positions are relative to {@link #originX}/Y/Z (the camera's block when it was
 * built), so the floats stay precise; the draw moves that origin to where it is from the current camera.
 */
final class ImpostorBatch {
    /** ENTITY vertex format: position 3f, color 4ub, uv0 2f, overlay 2s, light 2s, normal 3b + pad. */
    static final int VERTEX_BYTES = 36;

    private final String name;
    private ByteBuffer staging;
    private final GpuBuffer[] buffers = new GpuBuffer[2];
    private int current;
    private int quads;
    private int written;
    double originX, originY, originZ;

    ImpostorBatch(String name) {
        this.name = name;
    }

    void begin(Vec3 cam) {
        originX = Math.floor(cam.x);
        originY = Math.floor(cam.y);
        originZ = Math.floor(cam.z);
        written = 0;
    }

    /** Address to write {@code vertices} more vertices at; call {@link #commit} after writing them. */
    long reserve(int vertices) {
        int need = (written + vertices) * VERTEX_BYTES;
        if (staging == null || staging.capacity() < need) {
            int size = Math.max(need + need / 2, 1 << 20);
            ByteBuffer bigger = MemoryUtil.memAlloc(size);
            if (staging != null) {
                MemoryUtil.memCopy(MemoryUtil.memAddress(staging), MemoryUtil.memAddress(bigger), (long) written * VERTEX_BYTES);
                MemoryUtil.memFree(staging);
            }
            staging = bigger;
        }
        return MemoryUtil.memAddress(staging) + (long) written * VERTEX_BYTES;
    }

    void commit(int vertices) {
        written += vertices;
    }

    /** Uploads what was written; two GPU buffers alternate so an upload never waits on a frame still drawing the other. */
    void finish() {
        quads = written / 4;
        if (quads == 0) return;
        int bytes = written * VERTEX_BYTES;
        current = 1 - current;
        GpuBuffer buffer = buffers[current];
        if (buffer == null || buffer.size() < bytes) {
            if (buffer != null) buffer.close();
            long size = Math.max((long) bytes + bytes / 2, 1L << 20);
            buffer = RenderSystem.getDevice().createBuffer(() -> "CrowdPerf " + name, GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, size);
            buffers[current] = buffer;
        }
        staging.limit(bytes).position(0);
        RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(0, bytes), staging);
        staging.clear();
        // Grow the shared quad index buffer now, outside any render pass.
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(quads * 6);
    }

    void clear() {
        quads = 0;
        written = 0;
    }

    int quads() {
        return quads;
    }

    /** Draws the batch inside the solid pass, with the vanilla entity cutout pipeline and the batch atlas. */
    void draw(RenderPass pass, Vec3 cam) {
        GpuBuffer buffer = buffers[current];
        if (quads == 0 || buffer == null) return;
        RenderType type = RenderTypes.entityCutout(ImpostorAtlas.ID, false);
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.translate((float) (originX - cam.x), (float) (originY - cam.y), (float) (originZ - cam.z));
        PreparedRenderType prepared = type.prepare();
        modelView.popMatrix();
        pass.pushDebugGroup(() -> "CrowdPerf " + name);
        pass.setPipeline(RenderSystem.getCompiledPipeline(prepared.pipeline()));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", prepared.dynamicTransforms());
        pass.setVertexBuffer(0, buffer.slice(0, (long) quads * 4 * VERTEX_BYTES));
        for (PreparedRenderType.Texture texture : prepared.textures()) pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        pass.setIndexBuffer(indices.getBuffer(), indices.type());
        pass.drawIndexed(quads * 6, 1, 0, 0, 0);
        pass.popDebugGroup();
    }
}
