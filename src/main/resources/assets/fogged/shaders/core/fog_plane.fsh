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
uniform float PlaneFadeStart;   // distance at which the surface starts fading to transparent
uniform float PlaneFadeEnd;     // distance at which it is fully gone (reveals the real horizon)
uniform mat4 ProjMat;           // reused to linearise depth for the soft-occlusion fade
uniform vec2 ScreenSize;        // framebuffer size in pixels, to map gl_FragCoord into the depth sampler
uniform float HolesActive;      // 1 on the fogged side (dissolve holes on), 0 on the dry side (solid plane)
uniform int EntityHoleCount;    // number of active entity dissolve discs (0..MAX_ENTITY_HOLES)
// Packed 4 floats per entity: camera-relative centre X, Z, horizontal radius (blocks), vertical gap to
// the plane (blocks; 0 while the hitbox straddles it). Sized MAX_ENTITY_HOLES * 4 (see the renderer).
uniform float EntityHoles[128];

in vec4 vertexColor;
in vec2 worldXZ;
in vec3 relPos;

out vec4 fragColor;

// --- shader-side 3D value noise. TILEABLE: the spatial lattice (xy) wraps at a world-space period so
// the field repeats seamlessly. That period is made equal to the renderer's NOISE_ANCHOR, so when the
// anchor tile flips as the camera moves the pattern is identical across the jump -- no seam at ANY
// distance. The z axis is time (unwrapped), so it still boils forward in place. Octaves are NOT rotated
// here: a rotated lattice does not tile on the world axes, so seamlessness requires dropping it. ---
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
// Fractal noise: stack octaves at doubling frequency; each octave wraps at twice the period of the last
// so the whole sum stays periodic. The time axis scales up per octave so large blobs drift slowly while
// fine detail churns faster. `per` is the base spatial period in the passed p-coordinate space.
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

// Foam & spot pixelation: 4 pixels per block -> each pixel is a quarter of a block. Both the foam
// edge and the surface spots snap to this same grid so their pixels are identical in size and aligned.
const float FOAM_PIXELS_PER_BLOCK = 4.0;
const float FOAM_STEPS = 4.0;
const float OCCLUSION_FADE = 3.0;  // blocks of depth over which an occluding edge softens

// Window depth -> positive camera distance, from the same ProjMat used to project the quad. Both the
// fragment and the sampled scene depth go through this, so their difference is a true world-space gap.
float viewDist(float d) {
    float ndc = d * 2.0 - 1.0;
    return ProjMat[3][2] / (ndc + ProjMat[2][2]);
}

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

// Soft occlusion: 1.0 in the open, fading to 0.0 as the plane closes on the occluding geometry in the
// pre-plane depth snapshot, so a block/shore silhouette is a gradient instead of a hard depth cut.
float softOcclusion() {
    float sceneD = texture(Sampler3, gl_FragCoord.xy / ScreenSize).r;
    if (sceneD >= 1.0) {
        return 1.0; // open sky behind: nothing to fade against
    }
    float gap = viewDist(sceneD) - viewDist(gl_FragCoord.z);
    return clamp(gap / OCCLUSION_FADE, 0.0, 1.0);
}

void main() {
    vec4 color = vertexColor * ColorModulator;

    // Foam edge: world-space distance to the nearest surface-crossing block/entity, ringed within
    // FoamWidth. World-space, so it never flickers with view angle. Snap to the foam pixel grid (so it
    // pixelates and aligns with blocks) and sample at the texel centre (+0.5) so NEAREST doesn't bias
    // the whole ring a cell in -X/-Z.
    vec2 pworld = floor(worldXZ * FOAM_PIXELS_PER_BLOCK) / FOAM_PIXELS_PER_BLOCK;
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize + 0.5 / (WaterlineSize * FOAM_PIXELS_PER_BLOCK);
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
    vec2 sworld = floor(worldXZ * FOAM_PIXELS_PER_BLOCK) / FOAM_PIXELS_PER_BLOCK;
    vec2 cell = sworld / SPOT_CELL_BLOCKS;
    // Animate by advancing the noise's time axis: morphs in place, always forward (no ping-pong, no
    // slide). Time is monotonic wall-clock seconds, so the rate below is per real second. The base
    // period is the world period in cell space, so the noise tiles exactly with the anchor -> no seam.
    float s = fbm3(cell, Time * SPOT_TIME_RATE, NOISE_PERIOD_BLOCKS / SPOT_CELL_BLOCKS);
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
        const float NEAR_HOLE_RADIUS = 5.0; // blocks, horizontal radius of the dissolve around the player
        const float NEAR_HOLE_HEIGHT = 2.0; // eye-to-plane vertical gap over which it closes
        float hole = (1.0 - smoothstep(0.0, NEAR_HOLE_RADIUS, length(relPos.xz)))
                   * (1.0 - smoothstep(0.0, NEAR_HOLE_HEIGHT, abs(relPos.y)));

        // Entity dissolve discs: the same hole opened around every entity near the plane, sized by its
        // hitbox, so a crossing mob/player isn't hard-cut by the depth-writing plane -- it pokes through
        // a soft hole instead. Each disc fades out radially past its hitbox radius and vertically as the
        // entity separates from the plane (ENTITY_HOLE_HEIGHT). Constant loop bound for GLSL 150.
        const float ENTITY_HOLE_HEIGHT = 1.0; // vertical gap past the hitbox over which a disc closes
        for (int i = 0; i < 32; i++) {
            if (i >= EntityHoleCount) {
                break;
            }
            vec2 c = vec2(EntityHoles[i * 4], EntityHoles[i * 4 + 1]);
            float radius = EntityHoles[i * 4 + 2];
            float vgap = EntityHoles[i * 4 + 3];
            float radial = 1.0 - smoothstep(0.0, radius, length(relPos.xz - c));
            float vgate = 1.0 - smoothstep(0.0, ENTITY_HOLE_HEIGHT, vgap);
            hole = max(hole, radial * vgate);
        }
        // Only dissolve on the fogged side. From the dry side (looking down onto the murk) a hole would
        // be a clear, unfogged window straight through to the world the murk should hide; keep the plane
        // solid there. On the fogged side the revealed content sits in the murk fog, so it stays hidden.
        hole *= HolesActive;

        // Soft occlusion edge against terrain/blocks (see softOcclusion): grazing shores and block
        // silhouettes dissolve instead of cutting hard, plus the near-player / entity holes above. Done
        // as a screen-door DISCARD, not an alpha fade: the plane writes depth, so a see-through faded
        // fragment still culled the later translucent water pass and left a hard cut line where water
        // flows down past the boundary near a block. Discarding the faded fraction of pixels writes no
        // depth there, so the water renders through the dissolve instead of being cut.
        float coverage = softOcclusion() * (1.0 - hole);
        if (coverage < bayerDither(gl_FragCoord.xy)) {
            discard;
        }
        if (outColor.a <= 0.003) {
            discard;
        }

        fragColor = outColor;
    }
}
