#version 330
#extension GL_ARB_separate_shader_objects : require

// Counter-Craft 2: minecraft:core/entity, plus
// - NEAR_CLIP, your own first-person legs: CS2's first-person legs trick (r_csgo_firstpersonlegs_nearz_offset): nothing
//   closer to the camera than NEAR_CLIP blocks is drawn, so looking down shows your legs and feet, never the inside of
//   the body.
// - LENS, an aimed AUG / SG 553: CS2's scope lens shows the world, so any of the gun that lies behind the eyepiece glass
//   and is seen through it isn't drawn (in the skinned space: beyond the glass's plane, and the line from the eye to the
//   pixel crosses the glass's disc).

#ifdef GLINT
#include <minecraft:globals.glsl>
#endif
#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#ifdef DISSOLVE
uniform sampler2D DissolveMaskSampler;
#endif

#ifdef GLINT
uniform sampler2D GlintSampler;
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#ifdef PER_FACE_LIGHTING
layout(location = 2) in vec4 vertexPerFaceColorBack;
layout(location = 3) in vec4 vertexPerFaceColorFront;
#else
layout(location = 2) in vec4 vertexColor;
#endif

#ifndef EMISSIVE
layout(location = 4) in vec4 lightMapColor;
#endif

#ifndef NO_OVERLAY
layout(location = 5) in vec4 overlayColor;
#endif

layout(location = 6) in vec2 texCoord0;
#ifdef LENS
layout(location = 7) in vec3 skinnedPos;
layout(std140) uniform AgentSkin {
    mat4 PoseMat;
    ivec4 LightOverlay;
    vec4 LensEye;
    vec4 LensCenter;
    vec4 LensNormal;
    vec4 EnvUp;
    vec4 Bones[384];
};
#endif
#ifdef GLINT
layout(location = 7) in vec2 texCoordGlint;
#endif

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifndef NO_OVERLAY
    color.rgb = mix(overlayColor.rgb, color.rgb, overlayColor.a);
    #endif

    #ifndef EMISSIVE
    color *= lightMapColor;
    #endif

    #ifdef GLINT
    vec4 glintColor = GlintAlpha * texture(GlintSampler, texCoordGlint);
    // Matches BlendFuntion.GLINT
    color.rgb += glintColor.rgb * glintColor.rgb;
    #endif

    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif

    return apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
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

    vec4 color = texture(Sampler0, texCoord0);

    #ifdef OIT_ADDITIVE
    color.a = min(0.99, color.a);
    #endif

    #ifdef ALPHA_CUTOUT
    if (color.a < ALPHA_CUTOUT) {
        discard;
    }
    #endif

    #ifdef PER_FACE_LIGHTING
    vec4 faceVertexColor = gl_FrontFacing ? vertexPerFaceColorFront : vertexPerFaceColorBack;
    #else
    vec4 faceVertexColor = vertexColor;
    #endif

    #ifdef DISSOLVE
    if (faceVertexColor.a < texture(DissolveMaskSampler, texCoord0).a) {
        discard;
    }
    // The dissolve effect entirely replaces translucency
    faceVertexColor.a = 1.0;
    #endif

    color *= faceVertexColor * ColorModulator;

    #ifdef GLINT
    color.a = max(color.a, GlintAlpha);
    #endif

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
