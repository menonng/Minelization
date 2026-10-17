package com.grandstrategy.crowdperf;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.function.Predicate;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;

/**
 * A model baked once in its rest pose into flat vertex data, in the same space the living-entity renderer uses
 * (feet at the origin, before the body turn): per vertex x y z u v nx ny nz, four vertices per quad.
 * {@link #low} is the same mesh without the parts the low-detail filter rejects.
 */
final class ImpostorMesh {
    static final int FLOATS = 8;

    final float[] full;
    final float[] low;

    private ImpostorMesh(float[] full, float[] low) {
        this.full = full;
        this.low = low;
    }

    static ImpostorMesh bake(ModelPart root, Predicate<String> keepInLowDetail) {
        PoseStack pose = new PoseStack();
        // As LivingEntityRenderer.submit: flip the model upright and stand it on the ground.
        pose.scale(-1.0F, -1.0F, 1.0F);
        pose.translate(0.0F, -1.501F, 0.0F);
        Floats full = new Floats();
        Floats low = new Floats();
        Vector3f normal = new Vector3f();
        Vector3f pos = new Vector3f();
        root.visit(pose, (part, path, index, cube) -> {
            boolean keep = keepInLowDetail.test(path);
            for (ModelPart.Polygon polygon : cube.polygons) {
                part.transformNormal(polygon.normal(), normal);
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    part.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), pos);
                    full.add(pos, vertex.u(), vertex.v(), normal);
                    if (keep) low.add(pos, vertex.u(), vertex.v(), normal);
                }
            }
        });
        return new ImpostorMesh(full.toArray(), low.toArray());
    }

    private static final class Floats {
        private float[] data = new float[1024];
        private int size;

        void add(Vector3f pos, float u, float v, Vector3f n) {
            if (size + FLOATS > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = pos.x;
            data[size++] = pos.y;
            data[size++] = pos.z;
            data[size++] = u;
            data[size++] = v;
            data[size++] = n.x;
            data[size++] = n.y;
            data[size++] = n.z;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
