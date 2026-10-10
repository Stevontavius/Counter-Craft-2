#version 330
#extension GL_ARB_separate_shader_objects : require

// Counter-Craft 2: an agent (or a gun) skinned on the GPU. The mesh is uploaded once; per draw only the bone matrices,
// the placement and the light / overlay change (AgentSkin). The outputs match minecraft:core/entity (its fragment
// shader draws it), as the ENTITY_CUTOUT pipeline with PER_FACE_LIGHTING, so it lights, fogs and flashes red alike.

#include <minecraft:light.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:sample_lightmap.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;  // bone weights
layout(location = 2) in vec2 UV0;
layout(location = 3) in ivec2 UV1;   // bone indices: x = b0 | b1 << 8, y = b2 | b3 << 8
layout(location = 4) in ivec2 UV2;   // unused (the light comes per draw)
layout(location = 5) in vec3 Normal;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

layout(std140) uniform AgentSkin {
    mat4 PoseMat;          // mesh space -> camera-relative world (the entity's pose stack)
    ivec4 LightOverlay;    // xy: light coords (block, sky), zw: overlay coords
    vec4 LensEye;          // a scope's lens window (agent_legs.fsh, LENS), in skinned space: the eye
    vec4 LensCenter;       // the window's centre, w its radius
    vec4 LensNormal;       // its axis away from the eye, w 1 when there's a window
    vec4 EnvUp;            // the world's up in this space (the viewmodel's reflections, agent_vm.fsh)
    vec4 Bones[384];       // up to 128 bones, each a 3x4 row-major skinning matrix
};

layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexPerFaceColorBack;
layout(location = 3) out vec4 vertexPerFaceColorFront;
layout(location = 4) out vec4 lightMapColor;
layout(location = 5) out vec4 overlayColor;
layout(location = 6) out vec2 texCoord0;
layout(location = 7) out vec3 skinnedPos;  // for the lens window (unused by core/entity)
layout(location = 8) out vec3 litPos;      // camera-relative position and normal: the viewmodel's lighting
layout(location = 9) out vec3 litNormal;

void main() {
    ivec4 bone = ivec4(UV1.x & 255, (UV1.x >> 8) & 255, UV1.y & 255, (UV1.y >> 8) & 255);
    vec4 position = vec4(Position, 1.0);
    vec3 skinned = vec3(0.0);
    vec3 normal = vec3(0.0);
    float total = 0.0;
    for (int i = 0; i < 4; i++) {
        float w = Color[i];
        if (w > 0.0) {
            int b = bone[i] * 3;
            vec4 r0 = Bones[b];
            vec4 r1 = Bones[b + 1];
            vec4 r2 = Bones[b + 2];
            skinned += w * vec3(dot(r0, position), dot(r1, position), dot(r2, position));
            normal += w * vec3(dot(r0.xyz, Normal), dot(r1.xyz, Normal), dot(r2.xyz, Normal));
            total += w;
        }
    }
    if (total > 0.0) {
        skinned /= total;
    } else {
        skinned = Position;
        normal = Normal;
    }
    vec4 world = PoseMat * vec4(skinned, 1.0);
    vec3 n = mat3(PoseMat) * normal;
    float len = length(n);
    n = len > 1e-6 ? n / len : vec3(0.0, 1.0, 0.0);

    gl_Position = ProjMat * ModelViewMat * world;
    sphericalVertexDistance = fog_spherical_distance(world.xyz);
    cylindricalVertexDistance = fog_cylindrical_distance(world.xyz);

    // a mirrored pose (cl_righthand 0's viewmodel) reverses the triangles' winding: the side facing the camera is then
    // the "back" face, so front / back swap (else every visible face was lit as if it faced the other way)
    float mirror = determinant(mat3(PoseMat)) < 0.0 ? -1.0 : 1.0;
    vec2 light = minecraft_compute_light(Light0_Direction, Light1_Direction, n);
    vertexPerFaceColorBack = minecraft_mix_light_separate(-light * mirror, vec4(1.0));
    vertexPerFaceColorFront = minecraft_mix_light_separate(light * mirror, vec4(1.0));

    lightMapColor = sample_lightmap(Sampler2, LightOverlay.xy);
    overlayColor = texelFetch(Sampler1, LightOverlay.zw, 0);
    texCoord0 = UV0;
    skinnedPos = skinned;
    litPos = world.xyz;
    litNormal = n;
}
