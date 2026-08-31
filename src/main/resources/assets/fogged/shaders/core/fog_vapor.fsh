#version 150

#moj_import <fog.glsl>

// Cold-vapour ("liquid nitrogen") layer drawn on top of the separation plane. Geometry (undulating
// mist sheets that rise/fall over the plane + distant splash wisps) comes from FogVapor; this shader
// carves a drifting, PIXELATED wispy mask out of each quad and dissolves it into the world fog so it
// matches the blocky look of the plane and never shows a hard rim.

uniform vec4 ColorModulator;
uniform sampler2D Sampler0;     // waterline map, for thinning the mist where the foam is strong
uniform vec3 VaporColor;        // overall vapour tint, shared by every sheet (see the note on vertexColor below)
uniform float FogStart;
uniform float FogEnd;
uniform int FogShape;
uniform float Time;             // smooth game time in seconds (drives the drift)
uniform float WispScale;        // world-space frequency of the wisp/terrace noise
uniform float PixelsPerBlock;   // snap the noise to this grid (matches the plane's foam pixels)
uniform vec2 WaterlineOrigin;   // world XZ of the waterline map's corner
uniform float WaterlineSize;    // map edge length in blocks
uniform float WaterlineMaxDist; // distance (blocks) the map's stored value of 1.0 represents
uniform float FoamPixelsPerBlock; // pixels per block the foam and spots are drawn on
uniform float MapPixelsPerBlock;  // cells per block the waterline map itself holds (Config.waterlineCellsPerBlock)
uniform float FoamWidth;        // same foam band the surface draws, so the two line up
uniform float PlaneFadeStart;   // distance at which the vapour starts fading with the horizon
uniform float PlaneFadeEnd;     // distance at which it is fully gone
uniform mat4 ProjMat;

in vec4 vertexColor;
in vec2 worldXZ;
in vec3 relPos;

out vec4 fragColor;

const float WISP_STEPS = 4.0; // alpha quantised into this many chunky bands
const float VAPOR_TIME_RATE = 0.1; // noise time-axis advance per second (~one reshuffle per 10 s)

// --- world-space 3D value-noise fbm (fogged_fbm3 etc., shared with fog_plane.fsh -- see
// fogged_noise.glsl). TILEABLE: the spatial lattice (xy) wraps at a world period equal to the
// renderer's NOISE_ANCHOR, so the field is identical across the anchor tile flip -> no seam at any
// distance. z is time (unwrapped); no octave rotation (a rotated lattice would not tile). ---
const float NOISE_PERIOD_BLOCKS = 4096.0; // MUST equal FogPlaneRenderer.NOISE_ANCHOR for a seamless wrap -- see Config.java's mirrored-constants comment
#moj_import <fogged:fogged_noise.glsl>

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
    float nval = fogged_fbm3(p, t, per) * 0.6 + fogged_fbm3(p * 2.0 + 19.0, t * 1.3 + 7.0, per) * 0.4;

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
    a *= 1.0 - fogged_foamEdge(fogged_waterline(worldXZ)) * FOAM_THINNING;

    // No depth-based fade here. The mist used to dissolve against the scene-depth snapshot, but the
    // murk composite now runs after the translucent pass, so that snapshot contains WATER -- and the
    // sheets ride a couple of blocks over the surface, which put nearly every pixel of mist over
    // water inside the fade band and erased the layer. Hard occlusion by real geometry is already
    // handled by the depth test; the murk hides everything past the surface on its own.

    if (a <= 0.003) {
        discard;
    }

    fragColor = vec4(tint, a);
}
