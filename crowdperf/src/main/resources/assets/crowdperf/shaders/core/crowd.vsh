#version 330
#extension GL_ARB_separate_shader_objects : require

// CrowdPerf: one villager per instance. The mesh (binding 0) is the villager model baked in its rest pose; each
// instance (binding 1) carries where the villager was at the last two ticks, which way its body and head face, its
// walk cycle, its look in the atlas and its light. This shader interpolates between the ticks for the frame, turns the
// head, swings the legs and turns the body, the same way the entity renderer and VillagerModel do on the CPU.
// Its outputs match core/entity.vsh, so the vanilla entity fragment shader colours, lights and fogs it.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;
layout(location = 2) in vec3 Normal;
layout(location = 3) in vec3 Pivot;
layout(location = 4) in float Part;

layout(location = 5) in vec3 OldPos;   // relative to the batch origin
layout(location = 6) in vec3 NewPos;
layout(location = 7) in vec4 Turn;     // body yaw old, new; head yaw old, new (degrees)
layout(location = 8) in vec4 Pose;     // walk position old, new; walk speed old, new
layout(location = 9) in vec4 Look;     // atlas column, row; scale; head pitch (degrees)
layout(location = 10) in ivec2 UV2;

uniform sampler2D Sampler2;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
layout(location = 4) out vec4 lightMapColor;
layout(location = 6) out vec2 texCoord0;

mat3 rotX(float a) {
    float c = cos(a), s = sin(a);
    return mat3(1.0, 0.0, 0.0, 0.0, c, s, 0.0, -s, c);
}

mat3 rotY(float a) {
    float c = cos(a), s = sin(a);
    return mat3(c, 0.0, -s, 0.0, 1.0, 0.0, s, 0.0, c);
}

void main() {
    // The frame's partial tick and the atlas cell size ride in the texture matrix, which this pipeline does not use.
    float partialTick = TextureMat[3].x;
    float cell = TextureMat[3].y;

    vec3 p = Position;
    vec3 n = Normal;
    int part = int(Part + 0.5);
    float body = mix(Turn.x, Turn.y, partialTick);
    // The baked mesh is flipped (scale -1, -1, 1), so model rotations about X and Y run the other way here.
    if (part == 1) {
        float head = mix(Turn.z, Turn.w, partialTick) - body;
        mat3 r = rotY(radians(-head)) * rotX(radians(-Look.w));
        p = Pivot + r * (p - Pivot);
        n = r * n;
    } else if (part >= 2) {
        float walk = mix(Pose.x, Pose.y, partialTick);
        float speed = min(mix(Pose.z, Pose.w, partialTick), 1.0);
        float swing = cos(walk * 0.6662 + (part == 3 ? 3.14159265 : 0.0)) * 1.4 * speed * 0.5;
        mat3 r = rotX(-swing);
        p = Pivot + r * (p - Pivot);
        n = r * n;
    }

    mat3 turn = rotY(radians(180.0 - body));
    vec3 pos = mix(OldPos, NewPos, partialTick) + turn * (p * Look.z) + ModelOffset;
    vec3 normal = turn * n;

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);

    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, normal);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light, vec4(1.0));
    vertexPerFaceColorFront = minecraft_mix_light_separate(light, vec4(1.0));
    lightMapColor = sample_lightmap(Sampler2, UV2);
    texCoord0 = (Look.xy + UV0) * cell;
}
