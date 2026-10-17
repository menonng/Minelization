package com.grandstrategy.crowdperf;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.function.Predicate;
import net.minecraft.client.model.geom.ModelPart;
import org.joml.Vector3f;

/**
 * A model baked once in its rest pose into flat vertex data, in the space the living-entity renderer uses (feet at the
 * origin, before the body turn). Per vertex: x y z, u v, normal x y z, the animated part it belongs to and that part's
 * pivot. The vertex shader turns the head and swings the legs around their pivots, so one baked mesh serves every
 * villager in every pose. {@link #low} is the same mesh without the parts the low-detail filter rejects.
 */
final class ImpostorMesh {
    static final int FLOATS = 12;
    /** Animated parts, as the vertex shader numbers them. */
    static final int STILL = 0, HEAD = 1, RIGHT_LEG = 2, LEFT_LEG = 3;

    final float[] full;
    final float[] low;

    private ImpostorMesh(float[] full, float[] low) {
        this.full = full;
        this.low = low;
    }

    int quads(boolean lowDetail) {
        return (lowDetail ? low : full).length / FLOATS / 4;
    }

    static int partOf(String path) {
        if (path.startsWith("/head")) return HEAD;
        if (path.startsWith("/right_leg")) return RIGHT_LEG;
        if (path.startsWith("/left_leg")) return LEFT_LEG;
        return STILL;
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
        // Each animated part turns around the origin of its top-level model part (the neck, the hips).
        Vector3f[] pivots = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
        root.visit(pose, (part, path, index, cube) -> {
            int id = partOf(path);
            if (id != STILL && path.indexOf('/', 1) < 0) part.pose().transformPosition(0.0F, 0.0F, 0.0F, pivots[id]);
            Vector3f pivot = pivots[id];
            boolean keep = keepInLowDetail.test(path);
            for (ModelPart.Polygon polygon : cube.polygons) {
                part.transformNormal(polygon.normal(), normal);
                for (ModelPart.Vertex vertex : polygon.vertices()) {
                    part.pose().transformPosition(vertex.worldX(), vertex.worldY(), vertex.worldZ(), pos);
                    full.add(pos, vertex.u(), vertex.v(), normal, id, pivot);
                    if (keep) low.add(pos, vertex.u(), vertex.v(), normal, id, pivot);
                }
            }
        });
        return new ImpostorMesh(full.toArray(), low.toArray());
    }

    private static final class Floats {
        private float[] data = new float[2048];
        private int size;

        void add(Vector3f pos, float u, float v, Vector3f n, int part, Vector3f pivot) {
            if (size + FLOATS > data.length) data = java.util.Arrays.copyOf(data, data.length * 2);
            data[size++] = pos.x;
            data[size++] = pos.y;
            data[size++] = pos.z;
            data[size++] = u;
            data[size++] = v;
            data[size++] = n.x;
            data[size++] = n.y;
            data[size++] = n.z;
            data[size++] = part;
            data[size++] = pivot.x;
            data[size++] = pivot.y;
            data[size++] = pivot.z;
        }

        float[] toArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }
}
