#version 330
#extension GL_ARB_separate_shader_objects : require

// Counter-Craft 2: Video > Anti-Aliasing > TAA, on the world before the viewmodel draws. The world was drawn with a
// sub-pixel jitter; each pixel finds where it was last frame (its depth back to a camera-relative position, the
// camera's move added, through last frame's view-projection), takes the history there clamped to this frame's 3x3
// neighbourhood (so what moved or appeared doesn't smear), and blends this frame in.

uniform sampler2D CurrentSampler;
uniform sampler2D HistorySampler;
uniform sampler2D DepthSampler;

layout(std140) uniform TaaInfo {
    mat4 InvCurrent;    // this frame's jittered projection x view rotation, inverted: NDC to camera-relative
    mat4 PrevViewProj;  // last frame's (unjittered) projection x view rotation
    vec4 CamDelta;      // xyz: how far the camera moved since last frame; w 1: no history (start over)
    vec4 Params;        // x 1: depth is 0..1 in NDC (else -1..1); w: this frame's weight
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// the history resampled with a Catmull-Rom filter (9 bilinear taps): bilinear blurred the image a little more every
// frame it was reprojected
vec3 history(vec2 uv) {
    vec2 size = vec2(textureSize(HistorySampler, 0));
    vec2 pos = uv * size;
    vec2 t1 = floor(pos - 0.5) + 0.5;
    vec2 f = pos - t1;
    vec2 w0 = f * (-0.5 + f * (1.0 - 0.5 * f));
    vec2 w1 = 1.0 + f * f * (-2.5 + 1.5 * f);
    vec2 w2 = f * (0.5 + f * (2.0 - 1.5 * f));
    vec2 w3 = f * f * (-0.5 + 0.5 * f);
    vec2 w12 = w1 + w2;
    vec2 p0 = (t1 - 1.0) / size;
    vec2 p3 = (t1 + 2.0) / size;
    vec2 p12 = (t1 + w2 / w12) / size;
    vec3 c = texture(HistorySampler, vec2(p0.x, p0.y)).rgb * w0.x * w0.y
           + texture(HistorySampler, vec2(p12.x, p0.y)).rgb * w12.x * w0.y
           + texture(HistorySampler, vec2(p3.x, p0.y)).rgb * w3.x * w0.y
           + texture(HistorySampler, vec2(p0.x, p12.y)).rgb * w0.x * w12.y
           + texture(HistorySampler, vec2(p12.x, p12.y)).rgb * w12.x * w12.y
           + texture(HistorySampler, vec2(p3.x, p12.y)).rgb * w3.x * w12.y
           + texture(HistorySampler, vec2(p0.x, p3.y)).rgb * w0.x * w3.y
           + texture(HistorySampler, vec2(p12.x, p3.y)).rgb * w12.x * w3.y
           + texture(HistorySampler, vec2(p3.x, p3.y)).rgb * w3.x * w3.y;
    return max(c, vec3(0.0));
}

void main() {
    vec3 cur = texture(CurrentSampler, texCoord).rgb;
    if (CamDelta.w > 0.5) {
        fragColor = vec4(cur, 1.0);
        return;
    }
    float d = texture(DepthSampler, texCoord).r;
    vec4 rel = InvCurrent * vec4(texCoord * 2.0 - 1.0, Params.x > 0.5 ? d : d * 2.0 - 1.0, 1.0);
    rel /= rel.w;
    vec4 prev = PrevViewProj * vec4(rel.xyz + CamDelta.xyz, 1.0);
    vec2 uv = prev.xy / prev.w * 0.5 + 0.5;
    if (prev.w <= 0.0 || any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragColor = vec4(cur, 1.0);
        return;
    }
    vec2 px = 1.0 / vec2(textureSize(CurrentSampler, 0));
    vec3 lo = cur;
    vec3 hi = cur;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec3 s = texture(CurrentSampler, texCoord + vec2(x, y) * px).rgb;
            lo = min(lo, s);
            hi = max(hi, s);
        }
    }
    vec3 hist = clamp(history(uv), lo, hi);
    fragColor = vec4(mix(hist, cur, Params.w), 1.0);
}
