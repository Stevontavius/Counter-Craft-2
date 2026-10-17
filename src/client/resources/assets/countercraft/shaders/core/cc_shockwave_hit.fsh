#version 330
#extension GL_ARB_separate_shader_objects : require

// Counter-Craft 2: struck by a C4's shockwave. The screen shakes, a jolt that rattles and settles, and blurs with it:
// the frame smeared along the shake's own path over the last moment, ghosting like a long exposure of a shaken camera.
// Both ease in fast and out more slowly, scaled by how hard it struck.

uniform sampler2D InSampler;

layout(std140) uniform HitInfo {
    vec4 Hit;    // x: seconds since it struck, y: strength 0..1, z: seed, w: width / height
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// a few out-of-step wobbles (about -1..1 each way)
vec2 shake(float t, float seed) {
    return vec2(sin(t * 61.0 + seed) + 0.6 * sin(t * 37.0 + seed * 2.3),
                cos(t * 53.0 + seed * 1.7) + 0.6 * sin(t * 29.0 + seed * 0.7)) / 1.6;
}

void main() {
    float t = Hit.x;
    float e = smoothstep(0.0, 0.06, t) * (1.0 - smoothstep(0.06, 0.75, t));
    float amp = Hit.y * e * 0.018;
    vec2 aspect = vec2(1.0 / Hit.w, 1.0);
    vec2 now = shake(t, Hit.z);
    vec2 base = texCoord + now * amp * aspect;
    vec3 col = vec3(0.0);
    float sum = 0.0;
    for (int i = 0; i < 14; i++) {
        float k = float(i) / 13.0;
        vec2 trail = (shake(t - k * 0.05, Hit.z) - now) * amp * 1.6;
        vec2 soft = vec2(cos(k * 12.566), sin(k * 12.566)) * amp * 0.25;
        float w = 1.0 - 0.5 * k;
        col += texture(InSampler, base + (trail + soft) * aspect).rgb * w;
        sum += w;
    }
    fragColor = vec4(col / sum, 1.0);
}
