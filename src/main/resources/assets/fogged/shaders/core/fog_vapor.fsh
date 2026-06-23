#version 150

#moj_import <fog.glsl>

// Cold-vapour ("liquid nitrogen") layer drawn on top of the separation plane. Geometry (undulating
// mist sheets that rise/fall over the plane + distant splash wisps) comes from FogVapor; this shader
// carves a drifting, PIXELATED wispy mask out of each quad and dissolves it into the world fog so it
// matches the blocky look of the plane and never shows a hard rim.

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
uniform float Time;             // smooth game time in seconds (drives the drift)
uniform vec3 WorldOffset;
uniform float WispScale;        // world-space frequency of the wisp/terrace noise
uniform float PixelsPerBlock;   // snap the noise to this grid (matches the plane's foam pixels)
uniform float StepThreshold;    // this plane only shows where the noise field is >= this (terraces)
uniform float PlaneFadeStart;   // distance at which the vapour starts fading with the horizon
uniform float PlaneFadeEnd;     // distance at which it is fully gone

in vec4 vertexColor;
in vec2 worldXZ;
in vec3 relPos;

out vec4 fragColor;

const float WISP_STEPS = 4.0; // alpha quantised into this many chunky bands

// --- world-space value-noise fbm (same construction as fog_plane.fsh) ---
float h21(vec2 p) {
    p = floor(p);
    float n = p.x * 127.1 + p.y * 311.7;
    return fract(sin(n) * 43758.5453);
}
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = h21(i);
    float b = h21(i + vec2(1.0, 0.0));
    float c = h21(i + vec2(0.0, 1.0));
    float d = h21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
float fbm(vec2 p) {
    const mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * vnoise(p);
        p = rot * p * 2.0;
        amp *= 0.5;
    }
    return v / 0.9375;
}

void main() {
    vec4 color = vertexColor * ColorModulator;

    // Pixel-snap the world position (== the plane's foam resolution); the pattern does NOT translate.
    // It only reshapes in place by crossfading between two fixed noise fields, so it boils without
    // sliding. This single field drives both the terrace coverage and the surface texture.
    vec2 q = floor(worldXZ * PixelsPerBlock) / PixelsPerBlock;
    vec2 p = q * WispScale;
    float m = 0.5 + 0.5 * sin(Time * 0.6);
    float nval = mix(fbm(p), fbm(p + 11.0), m) * 0.6
               + mix(fbm(p * 1.7 + 19.0), fbm(p * 1.7 + 41.0), 1.0 - m) * 0.4;

    // Terrace: this plane is present where the noise reaches its step threshold, with a soft per-pixel
    // edge band (rather than a hard cut) so the steps read but don't look like solid blocks. Stacking
    // the equally-spaced planes nests them into noise-shaped steps at the pixel grid.
    float cov = smoothstep(StepThreshold, StepThreshold + 0.12, nval);
    if (cov <= 0.0) {
        discard;
    }

    // Chunky surface texture within the terrace.
    float tex = ceil(smoothstep(0.30, 0.95, nval) * WISP_STEPS) / WISP_STEPS;
    float a = color.a * (0.6 + 0.4 * tex) * cov;

    // Dissolve into the world/murk fog so distant vapour blends away exactly like the plane under it.
    float fogDist = fog_distance(relPos, FogShape);
    float fogA = 1.0 - clamp((fogDist - FogStart) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
    a *= fogA;

    // Fade the far rim with the horizon (3D distance), matching the plane's PlaneFade.
    a *= 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, length(relPos));

    if (a <= 0.003) {
        discard;
    }
    fragColor = vec4(color.rgb, a);
}
