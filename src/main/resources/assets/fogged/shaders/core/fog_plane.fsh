#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0; // world-space waterline map (R = solid/entity dist, G = plant dist)
uniform sampler2D Sampler1; // the baked noise fields (NoiseField); see fogged_field_sample.glsl
uniform sampler2D Sampler3; // scene depth snapshot (captured just before the plane) for soft edges
uniform vec2 NoiseOrigin;   // anchor-relative world XZ of the noise texture's corner
uniform float NoiseCells;   // its edge length in texels
uniform float NoiseCellsPerBlock;
uniform float NoiseValid;   // 0 when there is no baked texture this frame
uniform float NoiseTime;    // the boil clock it was baked at
uniform float WispScale;    // the vapour field's frequency, for the fallback evaluation only

uniform vec4 ColorModulator;
uniform vec4 FoamColor;     // foam base colour (rgb) and strength (a)
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
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
// Near-camera dither (Config.planeNearDither): near, far, min visibility, enabled (0/1). See below.
uniform vec4 NearDither;
uniform float DitherPixelSize;  // screen pixels per dither cell (Config.ditherPixelSize)
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
#moj_import <fogged:fogged_field.glsl>
#moj_import <fogged:fogged_field_sample.glsl>

// Waterline map sampling, shared with fog_vapor.fsh so the mist thins on exactly the ring drawn here
// (see fogged_foam.glsl).
#moj_import <fogged:fogged_foam.glsl>

// Foam & spot pixelation: FoamPixelsPerBlock pixels per block. Both the foam edge and the surface spots
// snap to this same grid so their pixels are identical in size and aligned.
const float FOAM_STEPS = 4.0;

// Soft occlusion (fogged_softOcclusion, shared with fog_vapor.fsh -- see fogged_occlusion.glsl); needs
// Sampler3/ScreenSize/ProjMat/DepthValid, all declared above.
#moj_import <fogged:fogged_occlusion.glsl>

// Bayer dither and the near-camera visibility ramp (fogged_bayer / fogged_nearVisibility -- see
// fogged_dither.glsl, shared with the shader-pack path).
#moj_import <fogged:fogged_dither.glsl>

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
    // Near-camera dither: the closer a fragment is to the eye, the more of its pixels are dropped, so
    // the surface opens into a chunky screen-door around the camera instead of snapping in as a hard
    // sheet at eye level when the head crosses it. A discard, not alpha, so it costs nothing in
    // sorting and behaves identically in both depth passes below: a dropped pixel is dropped in each.
    if (NearDither.w > 0.5) {
        float nearVis = fogged_nearVisibility(length(relPos), NearDither.x, NearDither.y, NearDither.z);
        if (nearVis < fogged_bayer(gl_FragCoord.xy, DitherPixelSize)) {
            discard;
        }
    }

    vec4 color = vertexColor * ColorModulator;

    // Foam edge: world-space distance to the nearest surface-crossing block/entity, ringed within
    // FoamWidth. World-space, so it never flickers with view angle (see fogged_foam.glsl for the
    // pixel snap and how the two rings combine).
    vec2 wl = fogged_waterline(worldXZ); // R = dist to solid/entity, G = dist to plant; also DebugView 5
    float edge = fogged_foamEdge(wl);

    // Surface spots: low-frequency world-space noise blobs, on the same pixel grid as the foam, that
    // fade in/out in place (fogged_spotField) -- a texel of the baked field (NoiseField) wherever it
    // reaches, evaluated in place beyond.
    float s = fogged_fields(fogged_pixelSnap(worldXZ), WispScale).g;
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
    // The occlusion fade is confined to where the map says something crosses (fogged_nearCrossing):
    // the depth gap alone cannot tell a block's face just under the surface next to its silhouette
    // from a block lying wholly beneath it, and the second used to show through as a ghost.
    float occlusion = mix(1.0, fogged_softOcclusion(), fogged_nearCrossing(wl));
    float opacity = occlusion * (1.0 - hole) * fade;

    // Debug views replace the plane wholesale, always fully opaque so nothing shows through.
    if (DebugView != 0) {
        fragColor = vec4(debugColor(wl, edge, lum, opacity, entityHole), 1.0);
        return;
    }

    // Everything softened is a Bayer discard, never alpha, in this one depth-writing draw -- as the
    // shader-pack path has it. A surviving pixel is solid and writes depth, so what is behind it
    // stays hidden; a dropped one shows whatever is behind, and whatever is drawn later there. That
    // is what makes a dissolve INTO water read right: the water pass comes after the plane, and
    // against an alpha-softened plane that wrote no depth it simply painted itself over the fade
    // and the foam, a bright pit where the surface should thin into it. Dithered, the plane's
    // surviving pixels hide the water behind them and the dropped ones let it through, and the two
    // interleave into the fade. Alpha was tried for the smoother ramp it gives over a wide terrain
    // gradient; the pixel size is config (ditherPixelSize) for anyone who finds the grain too fine.
    if (opacity < fogged_bayer(gl_FragCoord.xy, DitherPixelSize)) {
        discard;
    }
    if (outColor.a <= 0.003) {
        discard;
    }

    fragColor = outColor;
}
