#version 330
#extension GL_ARB_separate_shader_objects : require

// CS2's death cam colour grading (lighting/postprocessing/effects/death_cam_phase1.vpost): a 32^3 colour lookup
// table laid out as 32 slices of 32 x 32 (red across, green down, blue picks the slice), blended in by Strength.

uniform sampler2D InSampler;
uniform sampler2D LutSampler;

layout(location = 0) in vec2 texCoord;

layout(std140) uniform DeathcamConfig {
    float Strength;
};

layout(location = 0) out vec4 fragColor;

vec3 lookup(vec3 c) {
    c = clamp(c, 0.0, 1.0) * 31.0;
    float b0 = floor(c.b);
    float b1 = min(b0 + 1.0, 31.0);
    vec2 uv0 = vec2((b0 * 32.0 + c.r + 0.5) / 1024.0, (c.g + 0.5) / 32.0);
    vec2 uv1 = vec2((b1 * 32.0 + c.r + 0.5) / 1024.0, (c.g + 0.5) / 32.0);
    return mix(texture(LutSampler, uv0).rgb, texture(LutSampler, uv1).rgb, c.b - b0);
}

void main() {
    vec4 col = texture(InSampler, texCoord);
    fragColor = vec4(mix(col.rgb, lookup(col.rgb), Strength), 1.0);
}
