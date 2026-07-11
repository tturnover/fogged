#version 150

#moj_import <fog.glsl>

// Cold-vapour ("liquid nitrogen") layer drawn on top of the separation plane. Geometry (undulating
// mist sheets that rise/fall over the plane + distant splash wisps) comes from FogVapor; this shader
// carves a drifting, PIXELATED wispy mask out of each quad and dissolves it into the world fog so it
// matches the blocky look of the plane and never shows a hard rim.

uniform sampler2D Sampler3;     // scene depth snapshot (terrain, before the plane) for the soft edge

uniform vec4 ColorModulator;
uniform float FogStart;
uniform float FogEnd;
uniform int FogShape;
uniform float Time;             // smooth game time in seconds (drives the drift)
uniform float WispScale;        // world-space frequency of the wisp/terrace noise
uniform float PixelsPerBlock;   // snap the noise to this grid (matches the plane's foam pixels)
uniform float StepThreshold;    // this plane only shows where the noise field is >= this (terraces)
uniform float PlaneFadeStart;   // distance at which the vapour starts fading with the horizon
uniform float PlaneFadeEnd;     // distance at which it is fully gone
uniform mat4 ProjMat;           // reused to linearise depth for the soft-occlusion fade
uniform vec2 ScreenSize;        // framebuffer size in pixels, to map gl_FragCoord into the depth sampler

in vec4 vertexColor;
in vec2 worldXZ;
in vec3 relPos;

out vec4 fragColor;

const float WISP_STEPS = 4.0; // alpha quantised into this many chunky bands
const float VAPOR_TIME_RATE = 0.1; // noise time-axis advance per second (~one reshuffle per 10 s)
const float OCCLUSION_FADE = 3.0; // blocks of depth over which an occluding edge softens

// Window depth -> positive camera distance via the same ProjMat used to project the quad (see
// fog_plane.fsh). Soft occlusion: fade toward 0 as the vapour closes on the pre-plane terrain snapshot,
// so a block silhouette dissolves instead of cutting hard. The snapshot omits the plane, so the vapour
// never fades against the surface it rides on.
float viewDist(float d) {
    float ndc = d * 2.0 - 1.0;
    return ProjMat[3][2] / (ndc + ProjMat[2][2]);
}
float softOcclusion() {
    float sceneD = texture(Sampler3, gl_FragCoord.xy / ScreenSize).r;
    if (sceneD >= 1.0) {
        return 1.0;
    }
    float gap = viewDist(sceneD) - viewDist(gl_FragCoord.z);
    return clamp(gap / OCCLUSION_FADE, 0.0, 1.0);
}

// --- world-space 3D value-noise fbm, TILEABLE (same construction as fog_plane.fsh). The spatial
// lattice (xy) wraps at a world period equal to the renderer's NOISE_ANCHOR, so the field is identical
// across the anchor tile flip -> no seam at any distance. z is time (unwrapped); no octave rotation
// (a rotated lattice would not tile). ---
const float NOISE_PERIOD_BLOCKS = 4096.0; // MUST equal FogPlaneRenderer.NOISE_ANCHOR for a seamless wrap

float h31(vec3 ip, float per) {
    vec2 w = mod(ip.xy, vec2(per)); // wrap the (already-integer) spatial lattice into one tile; GLSL mod >= 0
    float n = w.x * 127.1 + w.y * 311.7 + ip.z * 74.7;
    return fract(sin(n) * 43758.5453);
}
float vnoise3(vec3 p, float per) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(h31(i + vec3(0,0,0), per), h31(i + vec3(1,0,0), per), f.x),
                  mix(h31(i + vec3(0,1,0), per), h31(i + vec3(1,1,0), per), f.x), f.y);
    float b = mix(mix(h31(i + vec3(0,0,1), per), h31(i + vec3(1,0,1), per), f.x),
                  mix(h31(i + vec3(0,1,1), per), h31(i + vec3(1,1,1), per), f.x), f.y);
    return mix(a, b, f.z);
}
float fbm3(vec2 p, float t, float per) {
    float v = 0.0;
    float amp = 0.5;
    float pr = per;
    for (int i = 0; i < 4; i++) {
        v += amp * vnoise3(vec3(p, t), pr);
        p *= 2.0;
        pr *= 2.0;
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
    // Base spatial period of the noise in p-space: the world period times the wisp scale. WispScale is
    // chosen so this is an integer, so the lattice wraps exactly and the field tiles with the anchor.
    // The second layer uses 2x frequency (a whole-number multiple, so it also tiles); a fractional
    // multiple like 1.7 would break the wrap and reintroduce a seam.
    float per = NOISE_PERIOD_BLOCKS * WispScale;
    float nval = fbm3(p, t, per) * 0.6 + fbm3(p * 2.0 + 19.0, t * 1.3 + 7.0, per) * 0.4;

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

    // Fade the far rim by max(horizontal radius, vertical drop), matching the plane: full render-
    // distance radius at any height, but the whole layer fades once the camera climbs farther above
    // the surface than it can see, so it doesn't hang in the void after the world below fogs out.
    a *= 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, max(length(relPos.xz), abs(relPos.y)));

    // Soft occlusion edge against terrain (see softOcclusion), so vapour grazing a block dissolves.
    a *= softOcclusion();

    if (a <= 0.003) {
        discard;
    }

    fragColor = vec4(color.rgb, a);
}
