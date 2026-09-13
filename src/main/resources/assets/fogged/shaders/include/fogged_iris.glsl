// Fragment code spliced into a shader pack's gbuffers program by IrisShaderPatcher, so the murk can be
// drawn THROUGH the pack (lit, shadowed and fogged by it) while keeping its own shape. Not a core
// shader: no #version, no #moj_import. The patcher pastes fogged_dither.glsl, fogged_noise.glsl and
// fogged_field.glsl ahead of this (with NOISE_PERIOD_BLOCKS declared), and declares viewWidth / viewHeight / gbufferProjection / gbufferProjectionInverse /
// gbufferModelViewInverse when the pack has not (Iris fills any of its uniforms a program declares).
//
// The pack's program owns the colour: the plane's foam arrives as the texture it samples (see
// WaterlineMap's foam colour map) and the vapour's tint as the vertex colour. Everything this mod
// otherwise does to a fragment's ALPHA -- the rim fade, the entity dissolve discs, the near-camera
// dither, the vapour's noise terraces and its distance fade -- is folded into one visibility here
// and applied as a Bayer discard, the only thing this code can do to a fragment without knowing how
// the pack writes its outputs. Every name is prefixed to stay clear of the pack's own.

uniform int fogged_Mode;              // 0 = not this mod's draw (the default for every other draw), 1 = plane, 2 = vapour
uniform float fogged_PixelSize;       // screen pixels per dither cell (Config.ditherPixelSize)
uniform vec3 fogged_WorldOffset;      // camera-relative -> world, anchored as FogPlaneRenderer's WorldOffset
uniform float fogged_Time;            // monotonic seconds for the vapour boil
uniform vec2 fogged_Fade;             // PlaneFadeStart, PlaneFadeEnd
uniform vec4 fogged_NearDither;       // near, far, min visibility, enabled (0/1)
uniform float fogged_HolesActive;     // 1 on the fogged side (entity discs open), 0 on the dry side
uniform int fogged_HoleCount;
uniform vec4 fogged_Holes[32];        // camera-relative X, Z, radius, vertical gap -- as fog_plane's EntityHoles
uniform vec4 fogged_Vapor;            // this sheet's terrace threshold, alpha, WispScale, pixels per block
uniform vec2 fogged_VaporFog;         // fog start / end the vapour dissolves over
uniform sampler2D fogged_SceneDepth;  // this mod's pre-plane depth snapshot (SceneDepth), on a unit of its own
uniform float fogged_DepthValid;      // 0 when that snapshot is off or failed this frame: never occlude
uniform sampler2D fogged_Waterline;   // the waterline map (WaterlineMap), on a unit of its own
uniform vec4 fogged_WaterlineInfo;    // its world XZ origin (anchored as WorldOffset), edge length, and the distance its 1.0 means
uniform int fogged_DebugView;         // == Config.DebugView's ordinal; see fogged_apply
uniform sampler2D fogged_Noise;       // the baked noise fields (NoiseField), on a unit of its own
uniform vec4 fogged_NoiseInfo;        // its anchored XZ origin, edge length in texels (0 = none this frame), cells per block

// Camera-relative world position of this fragment, rebuilt from its depth. The pack's own varyings
// are unknowable, and a reconstruction needs nothing from the vertex stage, so it survives packs with
// geometry or tessellation stages too. Depth precision only starts to matter hundreds of blocks out,
// where everything here has already faded.
vec3 fogged_relPos() {
    vec3 ndc = vec3(gl_FragCoord.xy / vec2(viewWidth, viewHeight), gl_FragCoord.z) * 2.0 - 1.0;
    vec4 view = gbufferProjectionInverse * vec4(ndc, 1.0);
    view /= view.w;
    return (gbufferModelViewInverse * vec4(view.xyz, 1.0)).xyz;
}

// Soft occlusion against the depth snapshot, as fogged_occlusion.glsl does it for the core shaders:
// 1.0 in the open, 0.0 as the surface closes on whatever is already drawn behind it, so a block, a
// shore or a mob crossing the surface dissolves into it (here as a dither) instead of a hard cut.
float fogged_viewDist(float d) {
    float ndc = d * 2.0 - 1.0;
    return gbufferProjection[3][2] / (ndc + gbufferProjection[2][2]);
}

float fogged_softOcclusion() {
    if (fogged_DepthValid < 0.5) {
        return 1.0;
    }
    float sceneD = texture(fogged_SceneDepth, gl_FragCoord.xy / vec2(viewWidth, viewHeight)).r;
    if (sceneD >= 1.0) {
        return 1.0;
    }
    float gap = fogged_viewDist(sceneD) - fogged_viewDist(gl_FragCoord.z);
    return clamp(gap / 3.0, 0.0, 1.0); // == FOGGED_OCCLUSION_FADE
}

// Where the map's answer starts fading out, as a share of its half-width, measured from the camera
// outwards; it reaches zero at the inscribed radius less a block. == WaterlineMap.FOAM_FADE_START /
// FOAM_FADE_MARGIN, which fade the baked foam over the same circle.
const float FOGGED_FOAM_FADE_START = 0.60;
const float FOGGED_FOAM_FADE_MARGIN = 1.0;

// How far into the map this point is, 1 well inside and 0 from the last radius that is certainly on
// the map outwards. The map is a square around the camera while the plane runs to the render distance,
// so anything that stops at the rim draws the rim -- and under a pack the plane is seamed on exactly
// that outline (FogPlaneRenderer.drawThroughPack). Stopping on the square's inscribed circle instead,
// less a block for the whole-block re-anchoring, means the map's contribution is already zero wherever
// the rim can be sampled: both sides of the seam then hold the same bare plane colour.
float fogged_mapReach(float radius, float halfSize) {
    float fadeTo = max(1.0, halfSize - FOGGED_FOAM_FADE_MARGIN);
    float fadeFrom = min(FOGGED_FOAM_FADE_START * halfSize, fadeTo - 1.0);
    return 1.0 - smoothstep(fadeFrom, fadeTo, radius);
}

// How close a world XZ is to something crossing the surface, from the waterline map (R: solids,
// entities and flowing water, not plants) -- the gate on the soft occlusion, as fogged_foam.glsl's
// fogged_nearCrossing is for the core shaders: the depth gap alone lets everything a few blocks
// under the surface show through as a ghost.
float fogged_nearCrossing(vec3 rel) {
    vec2 worldXZ = rel.xz + fogged_WorldOffset.xz;
    vec2 luv = (worldXZ - fogged_WaterlineInfo.xy) / fogged_WaterlineInfo.z;
    if (luv.x < 0.0 || luv.x > 1.0 || luv.y < 0.0 || luv.y > 1.0) {
        return 0.0;
    }
    // R alone: the crossings. B holds what only sits near the surface (the foam band) and rings it in
    // the baked colour map, but must not open the plane -- see fogged_foam.glsl.
    float d = texture(fogged_Waterline, luv).r * fogged_WaterlineInfo.w;
    float near = 1.0 - smoothstep(0.75, 2.0, d);
    return near * fogged_mapReach(length(rel.xz), fogged_WaterlineInfo.z * 0.5);
}

float fogged_occlusion(vec3 rel) {
    return mix(1.0, fogged_softOcclusion(), fogged_nearCrossing(rel));
}

float fogged_planeVisibility(vec3 rel) {
    // Far rim and camera-height falloff, as fog_plane.fsh's `fade`.
    float fade = 1.0 - smoothstep(fogged_Fade.x, fogged_Fade.y, max(length(rel.xz), abs(rel.y)));

    // Entity dissolve discs, as fog_plane.fsh's entity-hole loop.
    float hole = 0.0;
    for (int i = 0; i < 32; i++) {
        if (i >= fogged_HoleCount) {
            break;
        }
        vec4 h = fogged_Holes[i];
        float radial = 1.0 - smoothstep(0.0, h.z, length(rel.xz - h.xy));
        float vgate = 1.0 - smoothstep(0.0, 1.0, h.w);
        hole = max(hole, radial * vgate);
    }
    float vis = fade * (1.0 - hole * fogged_HolesActive) * fogged_occlusion(rel);

    if (fogged_NearDither.w > 0.5) {
        vis *= fogged_nearVisibility(length(rel), fogged_NearDither.x, fogged_NearDither.y, fogged_NearDither.z);
    }
    return vis;
}

// One mist sheet's coverage, as fog_vapor.fsh computes it -- same noise, same terrace band, same
// chunky texture, the same two fades and the soft occlusion -- minus the foam thinning, which reads
// the waterline map this program does not have.
float fogged_vaporVisibility(vec3 rel) {
    float stepThreshold = fogged_Vapor.x;
    float alpha = fogged_Vapor.y;
    float wispScale = fogged_Vapor.z;
    float pixelsPerBlock = fogged_Vapor.w;

    vec2 worldXZ = rel.xz + fogged_WorldOffset.xz;
    vec2 q = floor(worldXZ * pixelsPerBlock) / pixelsPerBlock;
    // The terrace field: a texel of the baked field where it reaches, evaluated in place beyond.
    float nval;
    vec2 cell = (q - fogged_NoiseInfo.xy) * fogged_NoiseInfo.w;
    if (fogged_NoiseInfo.z > 0.0 && cell.x >= 0.0 && cell.y >= 0.0
            && cell.x < fogged_NoiseInfo.z && cell.y < fogged_NoiseInfo.z) {
        nval = texelFetch(fogged_Noise, ivec2(cell + 0.5), 0).r;
    } else {
        nval = fogged_vaporField(q, fogged_Time, wispScale);
    }

    float cov = smoothstep(stepThreshold, stepThreshold + 0.12, nval);
    float tex = ceil(smoothstep(0.30, 0.95, nval) * 4.0) / 4.0;
    float a = alpha * (0.6 + 0.4 * tex) * cov;

    float dist = length(rel);
    a *= 1.0 - clamp((dist - fogged_VaporFog.x) / max(fogged_VaporFog.y - fogged_VaporFog.x, 1e-4), 0.0, 1.0);
    a *= 1.0 - smoothstep(fogged_Fade.x, fogged_Fade.y, max(length(rel.xz), abs(rel.y)));
    a *= fogged_occlusion(rel);
    return a;
}

void fogged_apply() {
    if (fogged_Mode == 0) {
        return;
    }
    vec3 rel = fogged_relPos();
    // The debug views proper live in fog_plane.fsh, which owns its colour; this code cannot write one
    // (see the header), so the one it can answer is "where does the waterline map actually reach".
    // WATERLINE_MAP keeps only the plane standing over the map's footprint: whatever square is left on
    // screen IS the footprint, which either matches the seam being chased or rules it out.
    if (fogged_DebugView == 5 && fogged_Mode == 1) {
        vec2 luv = (rel.xz + fogged_WorldOffset.xz - fogged_WaterlineInfo.xy) / fogged_WaterlineInfo.z;
        if (luv.x < 0.0 || luv.x > 1.0 || luv.y < 0.0 || luv.y > 1.0) {
            discard;
        }
        return;
    }
    float vis = fogged_Mode == 1 ? fogged_planeVisibility(rel) : fogged_vaporVisibility(rel);
    if (vis < fogged_bayer(gl_FragCoord.xy, fogged_PixelSize)) {
        discard;
    }
}
