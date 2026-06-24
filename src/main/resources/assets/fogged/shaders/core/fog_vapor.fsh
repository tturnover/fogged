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
const float VAPOR_TIME_RATE = 0.1; // noise time-axis advance per second (~one reshuffle per 10 s)

// --- world-space 3D value-noise fbm (same construction as fog_plane.fsh). Feeding time into the third
// axis morphs the field in place and only ever forward, so the vapour boils continuously instead of
// ping-ponging back and forth like a sin() crossfade. ---
float h31(vec3 p) {
    p = floor(p);
    float n = p.x * 127.1 + p.y * 311.7 + p.z * 74.7;
    return fract(sin(n) * 43758.5453);
}
float vnoise3(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(h31(i + vec3(0,0,0)), h31(i + vec3(1,0,0)), f.x),
                  mix(h31(i + vec3(0,1,0)), h31(i + vec3(1,1,0)), f.x), f.y);
    float b = mix(mix(h31(i + vec3(0,0,1)), h31(i + vec3(1,0,1)), f.x),
                  mix(h31(i + vec3(0,1,1)), h31(i + vec3(1,1,1)), f.x), f.y);
    return mix(a, b, f.z);
}
float fbm3(vec2 p, float t) {
    const mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * vnoise3(vec3(p, t));
        p = rot * p * 2.0;
        t *= 1.7;
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
    // Animate by advancing the noise's time axis (forward only, morphs in place) instead of crossfading
    // two fixed fields with a sin() -- that ran the boil forward then backward.
    float t = Time * VAPOR_TIME_RATE;
    float nval = fbm3(p, t) * 0.6 + fbm3(p * 1.7 + 19.0, t * 1.3 + 7.0) * 0.4;

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
