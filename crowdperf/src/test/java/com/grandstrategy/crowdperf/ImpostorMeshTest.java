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
    void uvsStayInsideTheSkin() {
        float[] d = ImpostorMesh.bake(villager(), path -> true).full;
        for (int k = 0; k < d.length; k += ImpostorMesh.FLOATS) {
            assertTrue(d[k + 3] >= 0 && d[k + 3] <= 1 && d[k + 4] >= 0 && d[k + 4] <= 1, "uv " + d[k + 3] + "," + d[k + 4]);
        }
    }
}
