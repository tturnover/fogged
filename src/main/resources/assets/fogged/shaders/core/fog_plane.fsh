#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0; // world-space waterline map (R = solid/entity dist, G = plant dist)
uniform sampler2D Sampler3; // scene depth snapshot (captured just before the plane) for soft edges

uniform vec4 ColorModulator;
uniform vec4 FoamColor;     // foam base colour (rgb) and strength (a)
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
uniform float Time; // monotonic wall-clock seconds (FogShaders#animTimeSeconds), drives the spot morph
uniform float FoamWidth;
// Config.DebugView ordinal: 0 renders normally, anything else replaces the plane with a raw
// view of one of the buffers behind it (see debugColor).
uniform int DebugView;
uniform vec2 WaterlineOrigin;   // world XZ of the waterline map's corner
uniform float WaterlineSize;    // map edge length in blocks
uniform float WaterlineMaxDist; // distance (blocks) the map's stored value of 1.0 represents
uniform float FoamPixelsPerBlock; // waterline map's cells-per-block resolution (Config.waterlineCellsPerBlock)
uniform float PlaneFadeStart;   // distance at which the surface starts fading to transparent
uniform float PlaneFadeEnd;     // distance at which it is fully gone (reveals the real horizon)
uniform mat4 ProjMat;           // reused to linearise depth for the soft-occlusion fade
uniform vec2 ScreenSize;        // framebuffer size in pixels, to map gl_FragCoord into the depth sampler
uniform float DepthValid;       // 0 if the scene-depth snapshot is unavailable/disabled this frame (see SceneDepth)
uniform float HolesActive;      // 1 on the fogged side (dissolve holes on), 0 on the dry side (solid plane)
// Which half of the two-pass plane draw this is: 1 = the solid core (drawn with depth writes on),
// 0 = everything softened by occlusion, holes or the rim fade (blended, no depth writes). Each pass
// discards the fragments the other owns, so the plane only writes depth where it is solid.
uniform float DepthPass;
uniform int EntityHoleCount;    // number of active entity dissolve discs (0..MAX_ENTITY_HOLES)
// Packed 4 floats per entity: camera-relative centre X, Z, horizontal radius (blocks), vertical gap to
// the plane (blocks; 0 while the hitbox straddles it). Sized MAX_ENTITY_HOLES * 4 (see the renderer).
uniform float EntityHoles[128];

in vec4 vertexColor;
in vec2 worldXZ;
in vec3 relPos;

out vec4 fragColor;

// --- shader-side 3D value noise (fogged_fbm3 etc., shared with fog_vapor.fsh -- see fogged_noise.glsl).
// TILEABLE: the spatial lattice (xy) wraps at a world-space period so the field repeats seamlessly.
// That period is made equal to the renderer's NOISE_ANCHOR, so when the anchor tile flips as the camera
// moves the pattern is identical across the jump -- no seam at ANY distance. The z axis is time
// (unwrapped), so it still boils forward in place. Octaves are NOT rotated here: a rotated lattice does
// not tile on the world axes, so seamlessness requires dropping it. ---
const float NOISE_PERIOD_BLOCKS = 4096.0; // MUST equal FogPlaneRenderer.NOISE_ANCHOR for a seamless wrap -- see Config.java's mirrored-constants comment
#moj_import <fogged:fogged_noise.glsl>

// Waterline map sampling, shared with fog_vapor.fsh so the mist thins on exactly the ring drawn here
// (see fogged_foam.glsl).
#moj_import <fogged:fogged_foam.glsl>

// Foam & spot pixelation: FoamPixelsPerBlock pixels per block. Both the foam edge and the surface spots
// snap to this same grid so their pixels are identical in size and aligned.
const float FOAM_STEPS = 4.0;

// Soft occlusion (fogged_softOcclusion, shared with fog_vapor.fsh -- see fogged_occlusion.glsl); needs
// Sampler3/ScreenSize/ProjMat/DepthValid, all declared above.
#moj_import <fogged:fogged_occlusion.glsl>

// Raw views of the buffers feeding the plane, selected by DebugView (== Config.DebugView's ordinal).
vec3 debugColor(vec2 wl, float edge, float lum, float opacity, float entityHole) {
    if (DebugView == 1) {                 // FOAM
        return vec3(edge, 0.0, lum);
    }
    if (DebugView == 2) {                 // SCENE_DEPTH
        if (DepthValid < 0.5) {
            return vec3(1.0, 0.0, 1.0);   // magenta: nothing was captured / occlusion is off
        }
        float sceneD = texture(Sampler3, gl_FragCoord.xy / ScreenSize).r;
        if (sceneD >= 1.0) {
            return vec3(0.0, 0.0, 1.0);   // blue: open sky behind, nothing to fade against
        }
        return vec3(1.0 - clamp(fogged_viewDist(sceneD) / 64.0, 0.0, 1.0));
    }
    if (DebugView == 3) {                 // PLANE_OPACITY
        return vec3(1.0 - opacity, opacity, 0.0);
    }
    if (DebugView == 4) {                 // DISSOLVE_HOLES
        return vec3(0.0, entityHole * HolesActive, 0.0);
    }
    if (DebugView == 5) {                 // WATERLINE_MAP
        return vec3(1.0 - wl.r, 1.0 - wl.g, 0.0);
    }
    return vec3(0.0);
}

void main() {
    vec4 color = vertexColor * ColorModulator;

    // Foam edge: world-space distance to the nearest surface-crossing block/entity, ringed within
    // FoamWidth. World-space, so it never flickers with view angle (see fogged_foam.glsl for the
    // pixel snap and how the two rings combine).
    vec2 wl = fogged_waterline(worldXZ); // R = dist to solid/entity, G = dist to plant; also DebugView 5
    float edge = fogged_foamEdge(wl);

    // Surface spots: low-frequency world-space noise blobs, on the same pixel grid as the foam, that
    // fade in/out in place (morph blends two offset noise layers over time, no translation).
    const float SPOT_CELL_BLOCKS = 4.0;    // blob feature size in blocks (divides NOISE_PERIOD for a tile)
    const float SPOT_TIME_RATE = 0.1;      // noise time-axis advance per second (~one reshuffle per 10 s)
    vec2 cell = fogged_pixelSnap(worldXZ) / SPOT_CELL_BLOCKS;
    // Animate by advancing the noise's time axis: morphs in place, always forward (no ping-pong, no
    // slide). Time is monotonic wall-clock seconds, so the rate below is per real second. The base
    // period is the world period in cell space, so the noise tiles exactly with the anchor -> no seam.
    float s = fogged_fbm3(cell, Time * SPOT_TIME_RATE, NOISE_PERIOD_BLOCKS / SPOT_CELL_BLOCKS);
    // Map onto the two lowest foam bands only (0.25/0.50) so a spot reads as foam not next to an edge.
    float t = smoothstep(0.45, 0.70, s);
    float lum = t <= 0.0 ? 0.0 : (t < 0.5 ? 0.25 : 0.5);
    lum *= FoamColor.a; // same foam strength the edge foam uses

    // Base that the foam and spots blend up from. Kept near the full plane colour: the plane is
    // opaque, so a heavily darkened base showed as a dark disc around the player (no fog there).
    vec3 planarFog = color.rgb * 0.92;

    // Foam: distance-to-edge gradient, stair-stepped into FOAM_STEPS chunky bands. Follows the
    // contour of every block/entity. f = whichever is stronger, the edge foam or the surface spot.
    float foam = edge * edge * (3.0 - 2.0 * edge);
    foam = ceil(foam * FOAM_STEPS) / FOAM_STEPS;
    foam *= FoamColor.a;
    float f = max(foam, lum);

    // Fog the base only, then lay foam on top, so foam is never blended away by the fog.
    float fogDist = fog_distance(relPos, FogShape);
    vec4 fogged = linear_fog(vec4(planarFog, color.a), fogDist, FogStart, FogEnd, FogColor);

    vec4 outColor = color;
    outColor.rgb = mix(fogged.rgb, FoamColor.rgb, f);
    outColor.a = max(fogged.a, f);

    // Raise alpha by the fog amount so distant geometry behind the plane tints to the plane colour
    // (the fragment is already that colour there) while the near footprint keeps the config alpha.
    float fogA = clamp((fogDist - FogStart) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
    outColor.a = max(outColor.a, fogA);

    // Fade the far rim by the larger of the HORIZONTAL radius and the VERTICAL drop to the surface.
    // Horizontal keeps the murk disc at full render-distance radius no matter the camera height (a
    // pure 3D distance shrank it as the player climbed); the vertical term then fades the whole
    // plane once the camera is farther above the surface than it can see, so it doesn't hang in the
    // void after the world below has fogged out. The outer rim reveals the real horizon.
    float fade = 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, max(length(relPos.xz), abs(relPos.y)));

    // Entity dissolve discs: a soft hole opened around every entity near the plane, sized by its
    // hitbox, so a crossing mob/player isn't hard-cut by the depth-writing plane -- it pokes through
    // a soft hole instead. Each disc fades out radially past its hitbox radius and vertically as the
    // entity separates from the plane (ENTITY_HOLE_HEIGHT). Constant loop bound for GLSL 150.
    const float ENTITY_HOLE_HEIGHT = 1.0; // vertical gap past the hitbox over which a disc closes
    float entityHole = 0.0;
    for (int i = 0; i < 32; i++) {
        if (i >= EntityHoleCount) {
            break;
        }
        vec2 c = vec2(EntityHoles[i * 4], EntityHoles[i * 4 + 1]);
        float radius = EntityHoles[i * 4 + 2];
        float vgap = EntityHoles[i * 4 + 3];
        float radial = 1.0 - smoothstep(0.0, radius, length(relPos.xz - c));
        float vgate = 1.0 - smoothstep(0.0, ENTITY_HOLE_HEIGHT, vgap);
        entityHole = max(entityHole, radial * vgate);
    }
    // Entity holes stay dry-side-gated: from the dry side (looking down onto the murk) a hole would
    // be a clear, unfogged window straight through to the world the murk should hide; keep the plane
    // solid there for OTHER entities. On the fogged side the revealed content sits in the murk fog,
    // so it stays hidden.
    float hole = entityHole * HolesActive;

    // Everything that makes the plane less than solid, in one opacity term: the soft occlusion edge
    // against whatever is already in the depth snapshot (grazing shores, block silhouettes, and --
    // since the snapshot is taken after them -- mobs and machines crossing the boundary), the
    // entity holes, and the far rim + camera-height falloff.
    float opacity = fogged_softOcclusion() * (1.0 - hole) * fade;

    // Debug views replace the plane wholesale. They draw only in the depth-writing pass so the split
    // below can't paint them twice, and they are always fully opaque so nothing shows through.
    if (DebugView != 0) {
        if (DepthPass < 0.5) {
            discard;
        }
        fragColor = vec4(debugColor(wl, edge, lum, opacity, entityHole), 1.0);
        return;
    }

    // Split the draw in two on that opacity, so the plane can only ever write depth where it is
    // fully solid. It is drawn twice: DepthPass 1 takes the solid core with depth writes on, then
    // DepthPass 0 takes everything softer with them off. A depth-writing fragment the player can
    // see through hides everything drawn later behind it -- that culled the translucent water pass
    // (a hard cut where water falls past the boundary next to a block) and, while the plane still
    // drew before entities, sliced every mob and machine at the boundary. The vertical part of the
    // fade makes relPos.y-driven opacity uniform across the whole quad, so this also covers "camera
    // high above the plane": the entire surface moves to the no-depth pass rather than turning into
    // a see-through sheet that still occludes.
    //
    // Splitting this way is what lets the softness be plain alpha. It used to be a screen-door
    // dither (a 4x4 Bayer discard) purely because the single draw always wrote depth; over a wide
    // terrain gradient that reads as a smooth ramp, but over something mob-sized the whole
    // silhouette sits at partial coverage and shows up as a chewed-looking checkerboard. FogVapor
    // never writes depth and has always faded with straight alpha -- the plane now matches it.
    bool solidHere = opacity >= 0.999;
    if ((DepthPass > 0.5) != solidHere) {
        discard;
    }
    outColor.a *= opacity;
    if (outColor.a <= 0.003) {
        discard;
    }

    fragColor = outColor;
}
