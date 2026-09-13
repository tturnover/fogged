#version 150

#moj_import <fog.glsl>

// Cold-vapour ("liquid nitrogen") layer drawn on top of the separation plane. Geometry (undulating
// mist sheets that rise/fall over the plane + distant splash wisps) comes from FogVapor; this shader
// carves a drifting, PIXELATED wispy mask out of each quad and dissolves it into the world fog so it
// matches the blocky look of the plane and never shows a hard rim.

uniform sampler2D Sampler0;     // waterline map, for thinning the mist where the foam is strong
uniform sampler2D Sampler1;     // the baked noise fields (NoiseField); see fogged_field_sample.glsl
uniform sampler2D Sampler3;     // scene depth snapshot (taken just before the plane) for the soft edge
uniform vec2 NoiseOrigin;       // anchor-relative world XZ of the noise texture's corner
uniform float NoiseCells;       // its edge length in texels
uniform float NoiseCellsPerBlock;
uniform float NoiseValid;       // 0 when there is no baked texture this frame
uniform float NoiseTime;        // the boil clock it was baked at

uniform vec4 ColorModulator;
uniform vec3 VaporColor;        // overall vapour tint, shared by every sheet (see the note on vertexColor below)
uniform float FogStart;
uniform float FogEnd;
uniform int FogShape;
uniform float WispScale;        // world-space frequency of the wisp/terrace noise
uniform float PixelsPerBlock;   // snap the noise to this grid (matches the plane's foam pixels)
uniform vec2 WaterlineOrigin;   // world XZ of the waterline map's corner
uniform float WaterlineSize;    // map edge length in blocks
uniform float WaterlineMaxDist; // distance (blocks) the map's stored value of 1.0 represents
uniform float FoamPixelsPerBlock; // the map's cells-per-block resolution (Config.waterlineCellsPerBlock)
uniform float FoamWidth;        // same foam band the surface draws, so the two line up
uniform float PlaneFadeStart;   // distance at which the vapour starts fading with the horizon
uniform float PlaneFadeEnd;     // distance at which it is fully gone
uniform mat4 ProjMat;           // reused to linearise depth for the soft-occlusion fade
uniform vec2 ScreenSize;        // framebuffer size in pixels, to map gl_FragCoord into the depth sampler
uniform float DepthValid;       // 0 if the scene-depth snapshot is unavailable/disabled this frame (see SceneDepth)

in vec4 vertexColor;
in vec2 worldXZ;
in vec3 relPos;

out vec4 fragColor;

const float WISP_STEPS = 4.0; // alpha quantised into this many chunky bands

// Soft occlusion (fogged_softOcclusion, shared with fog_plane.fsh -- see fogged_occlusion.glsl); needs
// Sampler3/ScreenSize/ProjMat/DepthValid, all declared above. The snapshot omits the plane, so the
// vapour never fades against the surface it rides on.
#moj_import <fogged:fogged_occlusion.glsl>

// --- world-space 3D value-noise fbm (fogged_fbm3 etc., shared with fog_plane.fsh -- see
// fogged_noise.glsl). TILEABLE: the spatial lattice (xy) wraps at a world period equal to the
// renderer's NOISE_ANCHOR, so the field is identical across the anchor tile flip -> no seam at any
// distance. z is time (unwrapped); no octave rotation (a rotated lattice would not tile). ---
const float NOISE_PERIOD_BLOCKS = 4096.0; // MUST equal FogPlaneRenderer.NOISE_ANCHOR for a seamless wrap -- see Config.java's mirrored-constants comment
#moj_import <fogged:fogged_noise.glsl>
#moj_import <fogged:fogged_field.glsl>
#moj_import <fogged:fogged_field_sample.glsl>

// Waterline map sampling, shared with fog_plane.fsh (see fogged_foam.glsl).
#moj_import <fogged:fogged_foam.glsl>

// How much of the mist the foam takes away where it is at full strength. Not all of it: the two are
// the same surface seen two ways, and a hard hole in the mist around every block reads worse than a
// thinning does.
const float FOAM_THINNING = 0.85;

void main() {
    // All sheets are drawn in one call (see FogVapor.render), so the per-sheet StepThreshold and alpha
    // that used to be separate per-draw uniforms now ride per-vertex in Color instead: Color.r is this
    // sheet's terrace threshold (0..1), Color.a is this sheet's alpha, Color.g/b are unused. The actual
    // tint is shared by every sheet, so it now comes from the VaporColor uniform instead of vertexColor.
    float stepThreshold = vertexColor.r;
    float alpha = vertexColor.a * ColorModulator.a;
    vec3 tint = VaporColor * ColorModulator.rgb;

    // Pixel-snap the world position (== the plane's foam resolution); the pattern does NOT translate.
    // It only reshapes in place (see fogged_vaporField), so it boils without sliding. This single
    // field drives both the terrace coverage and the surface texture -- one texel fetch from the baked
    // field (NoiseField) wherever it reaches, evaluated in place beyond.
    vec2 q = floor(worldXZ * PixelsPerBlock) / PixelsPerBlock;
    float nval = fogged_fields(q, WispScale).r;

    // Terrace: this plane is present where the noise reaches its step threshold, with a soft per-pixel
    // edge band (rather than a hard cut) so the steps read but don't look like solid blocks. Stacking
    // the equally-spaced planes nests them into noise-shaped steps at the pixel grid.
    float cov = smoothstep(stepThreshold, stepThreshold + 0.12, nval);
    if (cov <= 0.0) {
        discard;
    }

    // Chunky surface texture within the terrace.
    float tex = ceil(smoothstep(0.30, 0.95, nval) * WISP_STEPS) / WISP_STEPS;
    float a = alpha * (0.6 + 0.4 * tex) * cov;

    // Dissolve into the world/murk fog so distant vapour blends away exactly like the plane under it.
    float fogDist = fog_distance(relPos, FogShape);
    float fogA = 1.0 - clamp((fogDist - FogStart) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
    a *= fogA;

    // Fade the far rim by max(horizontal radius, vertical drop), matching the plane: full render-
    // distance radius at any height, but the whole layer fades once the camera climbs farther above
    // the surface than it can see, so it doesn't hang in the void after the world below fogs out.
    a *= 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, max(length(relPos.xz), abs(relPos.y)));

    // Thin out over foam. Mist and foam are both "the surface is disturbed here", and stacking them
    // buries the foam's shape under a wash of grey exactly where it is most worth seeing -- against
    // the blocks and entities breaking through. Read from the same map through the same helper the
    // surface uses, so the two agree on where the ring is to the pixel.
    vec3 wl = fogged_waterline(worldXZ);
    a *= 1.0 - fogged_foamEdge(wl) * FOAM_THINNING;

    // Soft occlusion edge against terrain (see fogged_softOcclusion), so vapour grazing a block
    // dissolves -- only near something crossing the surface (see fogged_nearCrossing), as the plane does.
    a *= mix(1.0, fogged_softOcclusion(), fogged_nearCrossing(wl));

    if (a <= 0.003) {
        discard;
    }

    fragColor = vec4(tint, a);
}
