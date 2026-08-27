#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0; // world-space waterline map (R = solid/entity dist, G = plant dist)
uniform sampler2D Sampler3; // scene depth snapshot (terrain, captured before the plane) for soft edges

uniform vec4 ColorModulator;
uniform vec4 FoamColor;     // foam base colour (rgb) and strength (a)
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
uniform float Time; // monotonic wall-clock seconds (FogShaders#animTimeSeconds), drives the spot morph
uniform float FoamWidth;
uniform float FoamDebug;
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

// Foam & spot pixelation: FoamPixelsPerBlock pixels per block. Both the foam edge and the surface spots
// snap to this same grid so their pixels are identical in size and aligned.
const float FOAM_STEPS = 4.0;

// Soft occlusion (fogged_softOcclusion, shared with fog_vapor.fsh -- see fogged_occlusion.glsl); needs
// Sampler3/ScreenSize/ProjMat/DepthValid, all declared above.
#moj_import <fogged:fogged_occlusion.glsl>

// Ordered 4x4 Bayer threshold for screen-door transparency. Indexed by the output pixel so a partial
// coverage becomes a stable checker of kept/dropped pixels rather than a continuous alpha.
float bayerDither(vec2 fc) {
    const float m[16] = float[16](
         0.0/16.0,  8.0/16.0,  2.0/16.0, 10.0/16.0,
        12.0/16.0,  4.0/16.0, 14.0/16.0,  6.0/16.0,
         3.0/16.0, 11.0/16.0,  1.0/16.0,  9.0/16.0,
        15.0/16.0,  7.0/16.0, 13.0/16.0,  5.0/16.0);
    int x = int(mod(fc.x, 4.0));
    int y = int(mod(fc.y, 4.0));
    return m[y * 4 + x];
}

void main() {
    vec4 color = vertexColor * ColorModulator;

    // Foam edge: world-space distance to the nearest surface-crossing block/entity, ringed within
    // FoamWidth. World-space, so it never flickers with view angle. Snap to the foam pixel grid (so it
    // pixelates and aligns with blocks) and sample at the texel centre (+0.5) so NEAREST doesn't bias
    // the whole ring a cell in -X/-Z.
    vec2 pworld = floor(worldXZ * FoamPixelsPerBlock) / FoamPixelsPerBlock;
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize + 0.5 / (WaterlineSize * FoamPixelsPerBlock);
    float edge = 0.0;
    if (FoamWidth > 0.0 && luv.x >= 0.0 && luv.x <= 1.0 && luv.y >= 0.0 && luv.y <= 1.0) {
        vec2 wl = texture(Sampler0, luv).rg; // R = dist to solid/entity, G = dist to plant
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
    // fade in/out in place (morph blends two offset noise layers over time, no translation).
    const float SPOT_CELL_BLOCKS = 4.0;    // blob feature size in blocks (divides NOISE_PERIOD for a tile)
    const float SPOT_TIME_RATE = 0.1;      // noise time-axis advance per second (~one reshuffle per 10 s)
    vec2 sworld = floor(worldXZ * FoamPixelsPerBlock) / FoamPixelsPerBlock;
    vec2 cell = sworld / SPOT_CELL_BLOCKS;
    // Animate by advancing the noise's time axis: morphs in place, always forward (no ping-pong, no
    // slide). Time is monotonic wall-clock seconds, so the rate below is per real second. The base
    // period is the world period in cell space, so the noise tiles exactly with the anchor -> no seam.
    float s = fogged_fbm3(cell, Time * SPOT_TIME_RATE, NOISE_PERIOD_BLOCKS / SPOT_CELL_BLOCKS);
    // Map onto the two lowest foam bands only (0.25/0.50) so a spot reads as foam not next to an edge.
    float t = smoothstep(0.45, 0.70, s);
    float lum = t <= 0.0 ? 0.0 : (t < 0.5 ? 0.25 : 0.5);
    lum *= FoamColor.a; // same foam strength the edge foam uses

    if (FoamDebug > 0.5) {
        fragColor = vec4(edge, 0.0, lum, 1.0); // R = foam edge factor, B = spot shading
    } else {
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
        outColor.a *= fade;
        // Near-player dissolve: as the eye nears the plane, open a small soft disc around the player so
        // the surface doesn't snap in as a hard sheet right at eye level. relPos.y is the eye-to-plane
        // vertical gap, so the hole opens only near the boundary and fades shut as the eye moves away.
        // Deliberately NOT gated by HolesActive: it must open symmetrically crossing in either direction.
        // HolesActive is 1 only while already below the boundary, so gating this the same way as the
        // entity holes below left it pre-opened while approaching from below (an exit) but still shut at
        // the instant of crossing from above (an entry) -- the one moment it's needed most.
        const float NEAR_HOLE_RADIUS = 5.0; // blocks, horizontal radius of the dissolve around the player
        const float NEAR_HOLE_HEIGHT = 2.0; // eye-to-plane vertical gap over which it closes
        float nearHole = (1.0 - smoothstep(0.0, NEAR_HOLE_RADIUS, length(relPos.xz)))
                       * (1.0 - smoothstep(0.0, NEAR_HOLE_HEIGHT, abs(relPos.y)));

        // Entity dissolve discs: the same hole opened around every entity near the plane, sized by its
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
        float hole = max(nearHole, entityHole * HolesActive);

        // Soft occlusion edge against terrain/blocks (see softOcclusion): grazing shores and block
        // silhouettes dissolve instead of cutting hard, plus the near-player / entity holes above. Done
        // as a screen-door DISCARD, not an alpha fade: the plane writes depth, so a see-through faded
        // fragment still culled the later translucent water pass and left a hard cut line where water
        // flows down past the boundary near a block. Discarding the faded fraction of pixels writes no
        // depth there, so the water renders through the dissolve instead of being cut.
        float coverage = fogged_softOcclusion() * (1.0 - hole);
        if (coverage < bayerDither(gl_FragCoord.xy)) {
            discard;
        }
        if (outColor.a <= 0.003) {
            discard;
        }

        fragColor = outColor;
    }
}
