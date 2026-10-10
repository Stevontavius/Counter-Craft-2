#version 330
#extension GL_ARB_separate_shader_objects : require

// Counter-Craft 2: the first-person viewmodel, lit the CS:GO way (cheap, per pixel): its albedo; the CS2 material's
// roughness / metalness / AO map (SpecSampler: R roughness, G metalness, B AO) and normal map (NormalSampler, tangent
// space, a frame from screen derivatives: no tangents needed); one key light (Minecraft's first entity light, fixed in
// the world) over a sky / ground ambient for the diffuse, and its Blinn-Phong
// highlight; and an analytic environment (sky above, ground below, a bright horizon, blurred by roughness) for the
// reflections that make metal read as metal. Metal reflects in its own colour (a finish tints the blade it sits on);
// paint and rubber get a faint clear-coat. Everything is scaled by the block / sky light, so it's dark in the dark.
// Agents and their guns use it too (Agent Shaders). LENS: an aimed scope's window; NEAR_CLIP: your own first-person
// legs (both as in agent_legs.fsh).

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:light.glsl>

uniform sampler2D Sampler0;
uniform sampler2D SpecSampler;
uniform sampler2D NormalSampler;

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
layout(location = 4) in vec4 lightMapColor;
layout(location = 5) in vec4 overlayColor;
layout(location = 6) in vec2 texCoord0;
layout(location = 7) in vec3 skinnedPos;
layout(location = 8) in vec3 litPos;
layout(location = 9) in vec3 litNormal;

layout(std140) uniform AgentSkin {
    mat4 PoseMat;
    ivec4 LightOverlay;
    vec4 LensEye;
    vec4 LensCenter;
    vec4 LensNormal;
    vec4 EnvUp;
    vec4 Bones[384];
};

layout(location = 0) out vec4 fragColor;

// sky above, ground below, a bright band at the horizon; rougher surfaces see a blurrier (flatter) world
vec3 environment(vec3 r, float rough) {
    float up = dot(r, EnvUp.xyz);
    vec3 sky = mix(vec3(0.95, 0.97, 1.0), vec3(0.55, 0.68, 0.95), clamp(up, 0.0, 1.0));
    vec3 ground = mix(vec3(0.42, 0.40, 0.36), vec3(0.16, 0.15, 0.14), clamp(-up * 2.0, 0.0, 1.0));
    float blur = 0.05 + rough * 0.6;
    vec3 env = mix(ground, sky, smoothstep(-blur, blur, up));
    env += vec3(0.35) * exp(-abs(up) * (24.0 / (0.25 + rough * 4.0)));
    return mix(env, vec3(0.55, 0.57, 0.6), rough * rough * 0.7);
}

void main() {
    #ifdef NEAR_CLIP
    if (sphericalVertexDistance < NEAR_CLIP) {
        discard;
    }
    #endif

    #ifdef LENS
    if (LensNormal.w > 0.5) {
        vec3 w = skinnedPos - LensEye.xyz;
        float den = dot(w, LensNormal.xyz);
        float num = dot(LensCenter.xyz - LensEye.xyz, LensNormal.xyz);
        if (num > 0.0 && den > num) {
            vec3 q = LensEye.xyz + w * (num / den) - LensCenter.xyz;
            if (dot(q, q) < LensCenter.w * LensCenter.w) {
                discard;
            }
        }
    }
    #endif

    vec4 albedo = texture(Sampler0, texCoord0);
    if (albedo.a < 0.1) {
        discard;
    }
    vec4 spa = texture(SpecSampler, texCoord0);
    vec3 sp = spa.rgb;
    float rough = clamp(sp.r, 0.04, 1.0);
    float metal = sp.g;
    float ao = sp.b;

    // the normal: the mesh's, bent by the normal map in a frame built from screen derivatives. A mirrored pose
    // (cl_righthand 0) reverses the winding, so the face toward the camera reads as a back face: flip the test back,
    // and the mirrored model is lit by the same light, not as a mirror image of the right hand's shading
    bool mirrored = determinant(mat3(PoseMat)) < 0.0;
    vec3 n = normalize(gl_FrontFacing != mirrored ? litNormal : -litNormal);
    vec3 dp1 = dFdx(litPos);
    vec3 dp2 = dFdy(litPos);
    vec2 duv1 = dFdx(texCoord0);
    vec2 duv2 = dFdy(texCoord0);
    vec3 dp2perp = cross(dp2, n);
    vec3 dp1perp = cross(n, dp1);
    vec3 t = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 b = dp2perp * duv1.y + dp1perp * duv2.y;
    float invmax = inversesqrt(max(dot(t, t), dot(b, b)) + 1e-12);
    vec3 tn = texture(NormalSampler, texCoord0).xyz * 2.0 - 1.0;
    vec3 N = normalize(mat3(t * invmax, -b * invmax, n) * tn);
    if (dot(N, n) < 0.2) {
        N = n; // a degenerate frame (UV seams): the mesh normal
    }

    vec3 V = normalize(-litPos);
    vec3 L0 = normalize(Light0_Direction);
    vec3 light = lightMapColor.rgb;

    // diffuse: one key light from a fixed world direction over a sky / ground ambient, like CS:GO's single world light.
    // Minecraft's two entity lights are each other turned 180 degrees about up: summed, each hand was lit on its outer
    // side and the left hand looked like a mirror image of the right. Metal has almost none.
    float diffuse = min(1.0, 0.45 + 0.15 * dot(N, EnvUp.xyz) + 0.6 * max(dot(L0, N), 0.0));
    vec3 colour = albedo.rgb * (1.0 - metal * 0.85) * diffuse;

    // reflections: metal in its own colour, everything else a faint clear coat (Schlick's Fresnel)
    vec3 f0 = mix(vec3(0.04), albedo.rgb, metal);
    float nv = max(dot(N, V), 0.0);
    vec3 fresnel = f0 + (max(vec3(1.0 - rough), f0) - f0) * pow(1.0 - nv, 5.0);
    vec3 R = reflect(-V, N);
    colour += fresnel * environment(R, rough) * mix(0.35, 1.0, metal);

    // the lights' highlights (normalized Blinn-Phong with the GGX-equivalent exponent: skin / leather at roughness
    // ~0.65 get a broad dim sheen, polished steel a tight bright glint)
    float a = max(rough * rough, 0.03);
    float shin = min(2.0 / (a * a) - 2.0, 2048.0);
    float norm = (shin + 8.0) / 25.0;
    vec3 h0 = normalize(L0 + V);
    float hl = pow(max(dot(N, h0), 0.0), shin) * max(dot(N, L0), 0.0);
    colour += f0 * hl * norm;

    // pearlescence (Printstream): a finish's spec map carries 1 - CS2's pearlescence mask x g_flPearlescentScale / 25 in
    // alpha (every other map: 1, none), EnvUp.w the player's Pearlescence slider. A pastel rainbow tint that shifts with
    // the viewing angle and the surface's direction, strongest at grazing angles. APPROXIMATION of CS2's sheen: the
    // strength and mask are CS2's data, the colour shift is not (CS2's formula is in its shader code)
    float pearl = (1.0 - spa.a) * EnvUp.w;
    if (pearl > 0.001) {
        float t = (1.0 - nv) * 1.6 + dot(N, EnvUp.xyz) * 0.35 + dot(N, V) * 0.15;
        vec3 rainbow = 0.5 + 0.5 * cos(6.2831853 * (t + vec3(0.0, 0.33, 0.67)));
        // pastel and as bright as the surface was: a hue, not a darker colour
        vec3 pastel = mix(vec3(1.0), rainbow / max(dot(rainbow, vec3(0.299, 0.587, 0.114)), 0.25), 0.15);
        colour = mix(colour, colour * pastel, clamp(min(pearl, 2.0) * (0.2 + 0.6 * (1.0 - nv)), 0.0, 1.0));
    }

    vec4 color = vec4(colour * ao * light, albedo.a) * ColorModulator;
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    fragColor = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd,
            FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
