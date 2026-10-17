#version 330
#extension GL_ARB_separate_shader_objects : require

// Counter-Craft 2: a C4's shockwave spreading through the world, drawn over the 3D world before the viewmodel. It's a
// sphere around the bomb growing as the front travels (its radius from the client's clock, CS2's July 2026 wave). Each
// pixel finds where its line of sight crosses that sphere in front of what it sees (the depth buffer): the shell bends
// the light there, most where it's seen edge-on (its rim); and where the front meets the world it bends the light of
// every surface it's passing over, in a rippling band. A faint pale shimmer rides on both. It fades as it spreads. And it
// leaves the air it went through a little foggier: a grey haze in the sphere it has swept, by how far each line of sight
// runs through it (so it's hazier looking across it than near you), settling over the next half minute.

uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(std140) uniform WaveInfo {
    mat4 InvViewProj;   // NDC to a camera-relative position
    vec4 Centre;        // xyz: the bomb, camera-relative; w: the front's radius (blocks)
    vec4 Params;        // x: strength (fading as it spreads), y: 1 when depth runs 0..1, z: seconds since it went off, w: band width
    vec4 Screen;        // xy: the bomb on screen (texture coordinates; the middle when it's behind you), z: width / height
    vec4 Fog;           // x: the dust's density (per block), y: the radius it has reached, z: the light where you are
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    bool sky = depth >= 0.99999;
    vec3 ndc = vec3(texCoord * 2.0 - 1.0, Params.y > 0.5 ? depth : depth * 2.0 - 1.0);
    vec4 h = InvViewProj * vec4(ndc, 1.0);
    vec3 p = h.xyz / h.w;
    float scene = sky ? 1e6 : length(p);
    vec3 d = normalize(p);
    vec3 c = Centre.xyz;
    float r = Centre.w;

    // the shell: crossings of the line of sight with the sphere, in front of what's there
    float rim = 0.0;
    float b = dot(d, c);
    float disc = b * b - (dot(c, c) - r * r);
    if (disc > 0.0) {
        float q = sqrt(disc);
        for (int i = 0; i < 2; i++) {
            float t = i == 0 ? b - q : b + q;
            if (t > 0.05 && t < scene) {
                vec3 n = normalize(d * t - c);
                rim += pow(1.0 - abs(dot(d, n)), 4.0);
            }
        }
    }
    // the front on the world's surfaces
    float off = sky ? 1e3 : length(p - c) - r;
    float band = exp(-pow(off / Params.w, 2.0));
    float amount = Params.x * (0.8 * rim + band);

    // bend the light outward from the bomb on screen, rippling across the band
    vec2 away = texCoord - Screen.xy;
    away.x *= Screen.z;
    away = normalize(away + vec2(1e-5));
    away.x /= Screen.z;
    float ripple = sin(off * 3.0 - Params.z * 20.0);
    vec2 uv = texCoord + away * amount * 0.012 * (0.6 + 0.4 * ripple);
    vec3 col = texture(InSampler, uv).rgb;
    col += vec3(0.85, 0.9, 1.0) * amount * 0.10;

    // the dust it left: the line of sight's length inside the swept sphere, up to what it sees
    float dr = Fog.y;
    float ddisc = b * b - (dot(c, c) - dr * dr);
    if (Fog.x > 0.0 && ddisc > 0.0) {
        float q = sqrt(ddisc);
        float len = max(0.0, min(b + q, scene) - max(b - q, 0.0));
        float haze = 1.0 - exp(-Fog.x * len);
        col = mix(col, vec3(0.60, 0.60, 0.62) * Fog.z, haze);
    }
    fragColor = vec4(col, 1.0);
}
