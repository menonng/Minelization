package com.grandstrategy.crowdperf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshTransformer;
import net.minecraft.client.model.npc.VillagerModel;
import org.junit.jupiter.api.Test;

class ImpostorMeshTest {
    /** The vanilla villager layer, as LayerDefinitions builds it. */
    private static ModelPart villager() {
        return LayerDefinition.create(VillagerModel.createBodyModel(), 64, 64).apply(MeshTransformer.scaling(0.9375F)).bakeRoot();
    }

    private static int quads(float[] mesh) {
        return mesh.length / ImpostorMesh.FLOATS / 4;
    }

    @Test
    void bakesEveryCubeFaceAndTheLowMeshDropsNoseAndHat() {
        ImpostorMesh mesh = ImpostorMesh.bake(villager(), path -> !path.contains("nose") && !path.contains("hat"));
        // head, hat, hat rim, nose, body, jacket, three arm boxes, two legs: 11 cubes of 6 faces
        assertEquals(66, quads(mesh.full));
        assertEquals(66 - 3 * 6, quads(mesh.low));
        assertEquals(66, mesh.quads(false));
        assertEquals(48, mesh.quads(true));
    }

    @Test
    void standsOnTheGroundFacingLikeTheEntityRenderer() {
        ImpostorMesh mesh = ImpostorMesh.bake(villager(), path -> true);
        float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        float[] d = mesh.full;
        for (int k = 0; k < d.length; k += ImpostorMesh.FLOATS) {
            minY = Math.min(minY, d[k + 1]);
            maxY = Math.max(maxY, d[k + 1]);
        }
        // Feet on the ground (vanilla's 1.501 offset leaves them a hair below), head about two blocks up.
        assertEquals(0.0F, minY, 0.01F);
        assertTrue(maxY > 1.9F && maxY < 2.1F, "top at " + maxY);
        // Normals stay unit length after the flip.
        for (int k = 0; k < d.length; k += ImpostorMesh.FLOATS) {
            float n = d[k + 5] * d[k + 5] + d[k + 6] * d[k + 6] + d[k + 7] * d[k + 7];
            assertEquals(1.0F, n, 1e-3F);
        }
        // The nose points to -Z before the body turn; the (180 - yaw) turn then faces yaw 0 south, as vanilla does.
        float[] nose = ImpostorMesh.bake(villager(), path -> path.endsWith("nose")).low;
        assertEquals(6, quads(nose));
        for (int k = 0; k < nose.length; k += ImpostorMesh.FLOATS) assertTrue(nose[k + 2] < -0.2F, "nose z " + nose[k + 2]);
    }

    @Test
    void headAndLegsKnowTheirPartAndPivot() {
        float[] d = ImpostorMesh.bake(villager(), path -> true).full;
        int head = 0, legs = 0;
        for (int k = 0; k < d.length; k += ImpostorMesh.FLOATS) {
            int part = (int) d[k + 8];
            float px = d[k + 9], py = d[k + 10], pz = d[k + 11];
            if (part == ImpostorMesh.HEAD) {
                head++;
                // The neck: on the axis, at the top of the body (about 1.5 blocks up), and the head sits above it.
                assertEquals(0.0F, px, 1e-4F);
                assertEquals(0.0F, pz, 1e-4F);
                assertTrue(py > 1.3F && py < 1.6F, "neck at " + py);
                assertTrue(d[k + 1] >= py - 0.07F, "head vertex below the neck (the nose reaches one pixel down): " + d[k + 1]);
            } else if (part == ImpostorMesh.RIGHT_LEG || part == ImpostorMesh.LEFT_LEG) {
                legs++;
                // The hips: about 0.7 blocks up, a little to each side (the model is mirrored on X).
                assertTrue(py > 0.6F && py < 0.8F, "hip at " + py);
                assertTrue(Math.abs(px) > 0.05F && Math.abs(px) < 0.2F, "hip x " + px);
                assertTrue(d[k + 1] <= py + 0.01F, "leg vertex above the hip: " + d[k + 1]);
            }
        }
        // Head, hat, rim and nose turn with the head; both legs swing.
        assertEquals(4 * 6 * 4, head);
        assertEquals(2 * 6 * 4, legs);
    }

    @Test
    void uvsStayInsideTheSkin() {
        float[] d = ImpostorMesh.bake(villager(), path -> true).full;
        for (int k = 0; k < d.length; k += ImpostorMesh.FLOATS) {
            assertTrue(d[k + 3] >= 0 && d[k + 3] <= 1 && d[k + 4] >= 0 && d[k + 4] <= 1, "uv " + d[k + 3] + "," + d[k + 4]);
        }
    }

    @Test
    void instanceAndMeshLayoutsMatchTheShader() {
        // crowd.vsh reads these offsets; the Java writers in Impostors and CrowdRenderer must agree.
        assertEquals(40, CrowdRenderer.MESH_BYTES);
        assertEquals(76, CrowdRenderer.INSTANCE_BYTES);
    }
}
