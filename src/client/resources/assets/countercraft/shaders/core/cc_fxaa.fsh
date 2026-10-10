#version 330
#extension GL_ARB_separate_shader_objects : require

// Counter-Craft 2: Video > Anti-Aliasing > FXAA, on the finished 3D frame (Timothy Lottes' FXAA, the compact
// "console" form): find the edge direction from the luma of the 2x2 diagonal neighbours, blur along it (two taps, then
// four if they stay inside the local luma range).

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

const float REDUCE_MIN = 1.0 / 128.0;
const float REDUCE_MUL = 1.0 / 8.0;
const float SPAN_MAX = 8.0;
const vec3 LUMA = vec3(0.299, 0.587, 0.114);

void main() {
    vec2 px = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 rgbM = texture(InSampler, texCoord).rgb;
    float lumaNW = dot(texture(InSampler, texCoord + vec2(-1.0, -1.0) * px).rgb, LUMA);
    float lumaNE = dot(texture(InSampler, texCoord + vec2(1.0, -1.0) * px).rgb, LUMA);
    float lumaSW = dot(texture(InSampler, texCoord + vec2(-1.0, 1.0) * px).rgb, LUMA);
    float lumaSE = dot(texture(InSampler, texCoord + vec2(1.0, 1.0) * px).rgb, LUMA);
    float lumaM = dot(rgbM, LUMA);
    float lumaMin = min(lumaM, min(min(lumaNW, lumaNE), min(lumaSW, lumaSE)));
    float lumaMax = max(lumaM, max(max(lumaNW, lumaNE), max(lumaSW, lumaSE)));

    vec2 dir = vec2(-((lumaNW + lumaNE) - (lumaSW + lumaSE)), (lumaNW + lumaSW) - (lumaNE + lumaSE));
    float reduce = max((lumaNW + lumaNE + lumaSW + lumaSE) * (0.25 * REDUCE_MUL), REDUCE_MIN);
    float scale = 1.0 / (min(abs(dir.x), abs(dir.y)) + reduce);
    dir = clamp(dir * scale, vec2(-SPAN_MAX), vec2(SPAN_MAX)) * px;

    vec3 rgbA = 0.5 * (texture(InSampler, texCoord + dir * (1.0 / 3.0 - 0.5)).rgb + texture(InSampler, texCoord + dir * (2.0 / 3.0 - 0.5)).rgb);
    vec3 rgbB = rgbA * 0.5 + 0.25 * (texture(InSampler, texCoord + dir * -0.5).rgb + texture(InSampler, texCoord + dir * 0.5).rgb);
    float lumaB = dot(rgbB, LUMA);
    fragColor = vec4(lumaB < lumaMin || lumaB > lumaMax ? rgbA : rgbB, 1.0);
}
