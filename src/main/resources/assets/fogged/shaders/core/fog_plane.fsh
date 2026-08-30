#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0; // world-space waterline map (R = solid/entity dist, G = plant dist)
uniform sampler2D Sampler3; // scene depth snapshot (captured just before this pass) for soft edges

uniform vec4 PlaneColor;    // murk colour (rgb); alpha unused, the surface is always drawn opaque
uniform vec4 FoamColor;     // foam base colour (rgb) and strength (a)
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
uniform float Time; // monotonic wall-clock seconds (FogShaders#animTimeSeconds), drives the spot morph
uniform float FoamWidth;
uniform vec2 WaterlineOrigin;   // world XZ of the waterline map's corner
uniform float WaterlineSize;    // map edge length in blocks
uniform float WaterlineMaxDist; // distance (blocks) the map's stored value of 1.0 represents
uniform float FoamPixelsPerBlock; // waterline map's cells-per-block resolution (Config.waterlineCellsPerBlock)
uniform float PlaneFadeStart;   // ray distance at which the surface starts fading out
uniform float PlaneFadeEnd;     // ray distance at which it is fully gone (reveals the real horizon)
uniform vec2 ScreenSize;        // framebuffer size in pixels, to map gl_FragCoord into the depth sampler
uniform float DepthValid;       // 0 if the scene-depth snapshot is unavailable/disabled this frame (see SceneDepth)

// Camera-relative world space: the camera sits at the origin, so a ray is just a direction and the
// boundary is the horizontal plane y == RelSurfaceY. Both matrices are the camera view (rotation
// only, no translation) combined with the projection, so they map that space to and from clip space.
uniform mat4 InvViewProj;
uniform mat4 ViewProj;
uniform float RelSurfaceY;      // surfaceY - cameraY
uniform vec2 WorldOffsetXZ;     // camera XZ, minus the anchor tile (see FogPlaneRenderer.NOISE_ANCHOR)
uniform float FoggedSide;       // 1 while the camera is inside the murk, 0 while it is on the dry side

// Config.DebugView ordinal: 0 renders normally, anything else replaces the murk with a raw view of
// one of the buffers behind it (see debugColor).
uniform int DebugView;

in vec2 ndc;

out vec4 fragColor;

// --- shader-side 3D value noise (fogged_fbm3 etc., shared with fog_vapor.fsh -- see fogged_noise.glsl).
// TILEABLE: the spatial lattice (xy) wraps at a world-space period so the field repeats seamlessly.
// That period is made equal to the renderer's NOISE_ANCHOR, so when the anchor tile flips as the camera
// moves the pattern is identical across the jump -- no seam at ANY distance. The z axis is time
// (unwrapped), so it still boils forward in place. Octaves are NOT rotated here: a rotated lattice does
// not tile on the world axes, so seamlessness requires dropping it. ---
const float NOISE_PERIOD_BLOCKS = 4096.0; // MUST equal FogPlaneRenderer.NOISE_ANCHOR for a seamless wrap -- see Config.java's mirrored-constants comment
#moj_import <fogged:fogged_noise.glsl>

// Foam & spot pixelation: FoamPixelsPerBlock pixels per block. Both the foam edge and the surface spots
// snap to this same grid so their pixels are identical in size and aligned.
const float FOAM_STEPS = 4.0;

// Blocks of depth over which an occluding silhouette softens. The comparison is between two ray
// lengths in the same space, so this is a true world-space distance at any view angle.
const float OCCLUSION_FADE = 3.0;

// Ordered 4x4 Bayer threshold for screen-door transparency, indexed by the output pixel so a partial
// coverage becomes a stable checker of kept/dropped pixels rather than a continuous alpha.
//
// The cells are offset by half a step, giving thresholds 1/32 .. 31/32 rather than 0 .. 15/16. A cell
// of exactly 0 is never crossed by `opacity < threshold`, so one pixel in sixteen survived even at
// ZERO coverage -- which past the far fade's end painted a sparse stipple across the whole sky above
// the surface's horizon, out to wherever the quad reached.
float bayerDither(vec2 fc) {
    const float m[16] = float[16](
         0.5/16.0,  8.5/16.0,  2.5/16.0, 10.5/16.0,
        12.5/16.0,  4.5/16.0, 14.5/16.0,  6.5/16.0,
         3.5/16.0, 11.5/16.0,  1.5/16.0,  9.5/16.0,
        15.5/16.0,  7.5/16.0, 13.5/16.0,  5.5/16.0);
    int x = int(mod(fc.x, 4.0));
    int y = int(mod(fc.y, 4.0));
    return m[y * 4 + x];
}

// Camera-relative world position of a point on this pixel's view ray, from a window-space depth in
// [0,1]. The w-divide undoes the projection.
vec3 unproject(float depth) {
    vec4 p = InvViewProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

// Raw views of the buffers feeding the murk, selected by DebugView (== Config.DebugView's ordinal).
vec3 debugColor(vec2 wl, float edge, float lum, float opacity, vec2 hitXZ, float sceneDepth,
                vec3 dir, bool hasHit) {
    if (DebugView == 1) {                 // FOAM
        return vec3(edge, 0.0, lum);
    }
    if (DebugView == 2) {                 // SCENE_DEPTH
        if (DepthValid < 0.5) {
            return vec3(1.0, 0.0, 1.0);   // magenta: nothing was captured / occlusion is off
        }
        if (sceneDepth >= 1.0) {
            return vec3(0.0, 0.0, 1.0);   // blue: open sky behind, nothing to fade against
        }
        return vec3(1.0 - clamp(length(unproject(sceneDepth)) / 64.0, 0.0, 1.0));
    }
    if (DebugView == 3) {                 // PLANE_OPACITY
        return vec3(1.0 - opacity, opacity, 0.0);
    }
    if (DebugView == 4) {                 // RAY_HIT
        // Where this pixel's view ray meets the boundary, as fractional world XZ -- it must stay
        // locked to the world as the camera moves. Pixels whose ray never reaches the boundary show
        // the reconstructed direction instead (rgb = dir * 0.5 + 0.5), so a screen with NO hits
        // anywhere says the ray maths is wrong rather than the plane being out of view.
        return hasHit ? vec3(fract(hitXZ), 0.0) : dir * 0.5 + 0.5;
    }
    if (DebugView == 5) {                 // WATERLINE_MAP
        return vec3(1.0 - wl.r, 1.0 - wl.g, 0.0);
    }
    return vec3(0.0);
}

void main() {
    // The view ray for this pixel, in camera-relative world space.
    vec3 dir = normalize(unproject(1.0));

    // Where it meets the boundary. A ray running away from the plane (or along it) never does.
    // Debug views must NOT be dropped for that: a pixel with no intersection would then show the
    // scene drawn normally, which is indistinguishable from the buffer under test being empty there.
    // They keep drawing with rayT pinned to 0 so the raw buffers still cover the whole screen.
    float rayT = abs(dir.y) < 1e-6 ? -1.0 : RelSurfaceY / dir.y;
    bool hasHit = rayT > 0.0;
    if (!hasHit) {
        if (DebugView == 0) {
            discard;
        }
        rayT = 0.0;
    }
    vec3 hit = dir * rayT;              // camera-relative point on the surface
    vec2 worldXZ = hit.xz + WorldOffsetXZ;

    // Soft occlusion: how far the scene behind this pixel sits past the surface. Both distances are
    // lengths along the same ray, so the gap is a real world-space distance however the view is
    // angled -- and because the snapshot is taken after the entity, block-entity and Flywheel passes,
    // a mob or a machine crossing the boundary softens the murk exactly like a terrain block does.
    float sceneDepth = texture(Sampler3, gl_FragCoord.xy / ScreenSize).r;
    float occlusion = 1.0;
    if (DepthValid >= 0.5 && sceneDepth < 1.0) {
        occlusion = clamp((length(unproject(sceneDepth)) - rayT) / OCCLUSION_FADE, 0.0, 1.0);
    }

    // Fade the surface out with ray distance, so it never reaches past where the world itself fades
    // away. One distance covers both the far rim and "camera high above the plane" -- looking down
    // from height makes every ray long -- where the old quad needed a separate camera-height term.
    float farFade = 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, rayT);
    // ...and a matching one over the last block, so the surface eases in as the eye crosses it rather
    // than flooding the screen the instant the boundary passes eye level. At eye height every ray to
    // the surface is short, so this is the same distance term acting at the near end -- not the old
    // near-player dissolve disc, which was a hole punched to hide a hard depth cut.
    const float NEAR_FADE = 1.0;
    float nearFade = smoothstep(0.0, NEAR_FADE, rayT);

    // From INSIDE the murk the surface is a ceiling, and a ceiling that fades out with distance is a
    // hole onto the un-fogged world above, opening and closing as the camera turns. So the far fade
    // is dropped on the fogged side -- the murk fog ends the view long before the ceiling would have
    // faded anyway. The soft edge stays: it only ever dissolves right at a silhouette, where what it
    // reveals is the rest of the block or machine crossing the boundary, which is the point of it.
    float opacity = occlusion * nearFade * (FoggedSide > 0.5 ? 1.0 : farFade);

    // Foam edge: world-space distance to the nearest surface-crossing block/entity, ringed within
    // FoamWidth. World-space, so it never flickers with view angle. Snap to the foam pixel grid (so it
    // pixelates and aligns with blocks) and sample at the texel centre (+0.5) so NEAREST doesn't bias
    // the whole ring a cell in -X/-Z.
    vec2 pworld = floor(worldXZ * FoamPixelsPerBlock) / FoamPixelsPerBlock;
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize + 0.5 / (WaterlineSize * FoamPixelsPerBlock);
    vec2 wl = vec2(1.0); // "nothing near" until the map says otherwise; also what DebugView 5 shows
    float edge = 0.0;
    if (FoamWidth > 0.0 && luv.x >= 0.0 && luv.x <= 1.0 && luv.y >= 0.0 && luv.y <= 1.0) {
        wl = texture(Sampler0, luv).rg; // R = dist to solid/entity, G = dist to plant
        // Two independent foam rings combined by max. Each ring's strength scales BOTH its band width
        // (config FoamWidth * strength) and its intensity, so a plant (0.5) reads half as wide and half
        // as strong as a solid/entity (1.0). They form separately so neither overrides the other.
        const float SOLID_STRENGTH = 1.0;
        const float PLANT_STRENGTH = 0.5;
        float solidEdge = SOLID_STRENGTH * (1.0 - clamp(wl.r * WaterlineMaxDist / (FoamWidth * SOLID_STRENGTH), 0.0, 1.0));
        float plantEdge = PLANT_STRENGTH * (1.0 - clamp(wl.g * WaterlineMaxDist / (FoamWidth * PLANT_STRENGTH), 0.0, 1.0));
        edge = max(solidEdge, plantEdge);
    }

    // Surface spots: low-frequency world-space noise blobs, on the same pixel grid as the foam, that
    // fade in/out in place (the noise's time axis advances, so they morph rather than drift).
    const float SPOT_CELL_BLOCKS = 4.0;    // blob feature size in blocks (divides NOISE_PERIOD for a tile)
    const float SPOT_TIME_RATE = 0.1;      // noise time-axis advance per second (~one reshuffle per 10 s)
    vec2 cell = pworld / SPOT_CELL_BLOCKS;
    float s = fogged_fbm3(cell, Time * SPOT_TIME_RATE, NOISE_PERIOD_BLOCKS / SPOT_CELL_BLOCKS);
    // Map onto the two lowest foam bands only (0.25/0.50) so a spot reads as foam not next to an edge.
    float t = smoothstep(0.45, 0.70, s);
    float lum = t <= 0.0 ? 0.0 : (t < 0.5 ? 0.25 : 0.5);
    lum *= FoamColor.a; // same foam strength the edge foam uses

    // Debug views replace the murk wholesale, fully opaque so nothing shows through.
    if (DebugView != 0) {
        // Still write a depth: a shader that sets gl_FragDepth anywhere leaves it undefined on any
        // path that does not.
        vec4 dclip = ViewProj * vec4(hit, 1.0);
        gl_FragDepth = hasHit ? dclip.z / dclip.w * 0.5 + 0.5 : 1.0;
        fragColor = vec4(debugColor(wl, edge, lum, opacity, worldXZ, sceneDepth, dir, hasHit), 1.0);
        return;
    }

    // Screen-door transparency: every softening term (the occlusion edge, the far fade, the near
    // fade) drops a fraction of the pixels in a stable 4x4 checker instead of lowering alpha. That is
    // the look this wants -- chunky and pixelated, matching the foam and the surface spots rather
    // than a smooth gradient over them.
    //
    // It also means one draw does the whole job. A discarded fragment writes no depth, so the murk
    // ends up writing depth exactly where it is solid, which an alpha fade could only achieve by
    // splitting the surface into a depth-writing pass and a blended one.
    if (opacity < bayerDither(gl_FragCoord.xy)) {
        discard;
    }

    // Base that the foam and spots blend up from. Kept near the full plane colour: the murk is
    // opaque, so a heavily darkened base showed as a dark disc around the player (no fog there).
    vec3 planarFog = PlaneColor.rgb * 0.92;

    // Foam: distance-to-edge gradient, stair-stepped into FOAM_STEPS chunky bands. Follows the
    // contour of every block/entity. f = whichever is stronger, the edge foam or the surface spot.
    float foam = edge * edge * (3.0 - 2.0 * edge);
    foam = ceil(foam * FOAM_STEPS) / FOAM_STEPS;
    foam *= FoamColor.a;
    float f = max(foam, lum);

    // Fog the base only, then lay foam on top, so foam is never blended away by the fog.
    float fogDist = fog_distance(hit, FogShape);
    vec4 fogged = linear_fog(vec4(planarFog, 1.0), fogDist, FogStart, FogEnd, FogColor);

    // Kept pixels are fully opaque; the dither above is what carries the softness.
    vec4 outColor = vec4(mix(fogged.rgb, FoamColor.rgb, f), 1.0);

    // The surface's own depth, nudged very slightly away from the camera so anything drawn after it
    // at the same height -- the vapour sheets ride right on the surface -- is not z-fought out of
    // existence by a depth this shader derived through a different route than the rasteriser.
    vec4 clip = ViewProj * vec4(hit * 1.0005, 1.0);
    gl_FragDepth = clip.z / clip.w * 0.5 + 0.5;
    fragColor = outColor;
}
