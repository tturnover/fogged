#version 150

#moj_import <fog.glsl>

uniform sampler2D Sampler0; // world-space waterline distance map (R = distance to nearest waterline)
uniform sampler2D Sampler1; // procedural, seamlessly-tiling water/foam texture (R=water, G=foam)

uniform vec4 ColorModulator;
uniform vec4 FoamColor;     // foam base colour (rgb) and strength (a)
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;
uniform int FogShape;
uniform float GameTime;
uniform float FoamWidth;
uniform float FoamDebug;
uniform vec2 WaterlineOrigin;   // world XZ of the waterline map's corner
uniform float WaterlineSize;    // map edge length in blocks
uniform float WaterlineMaxDist; // distance (blocks) the map's stored value of 1.0 represents
uniform float PlaneFadeStart;   // distance at which the surface starts fading to transparent
uniform float PlaneFadeEnd;     // distance at which it is fully gone (reveals the real horizon)

in vec4 vertexColor;
in vec2 worldXZ;
in vec3 relPos;

out vec4 fragColor;

const float TAU = 6.2831853;

// --- shader-side value noise, evaluated in world space so it never tiles/repeats like a texture ---
float h21(vec2 p) {
    p = floor(p);
    float n = p.x * 127.1 + p.y * 311.7;
    return fract(sin(n) * 43758.5453);
}
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = h21(i);
    float b = h21(i + vec2(1.0, 0.0));
    float c = h21(i + vec2(0.0, 1.0));
    float d = h21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}
// Fractal noise: stack octaves, rotating each one so blobs are organic, not axis-aligned squares.
float fbm(vec2 p) {
    const mat2 rot = mat2(0.80, 0.60, -0.60, 0.80);
    float v = 0.0;
    float amp = 0.5;
    for (int i = 0; i < 4; i++) {
        v += amp * vnoise(p);
        p = rot * p * 2.0;
        amp *= 0.5;
    }
    return v / 0.9375; // normalise (0.5+0.25+0.125+0.0625) back to ~[0,1]
}

// Must match WaterTexture: TILE_BLOCKS blocks span SIZE texels (16 texels per block).
const float WATER_TILE_BLOCKS = 4.0;
const float WATER_TILE_TEXELS = 64.0;

// Foam & spot pixelation: 4 pixels per block -> each pixel is a quarter of a block. Both the foam
// edge and the surface spots snap to this same grid so their pixels are identical in size and aligned.
const float FOAM_PIXELS_PER_BLOCK = 4.0;
const float FOAM_STEPS = 4.0;

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
        float waterDist = texture(Sampler0, luv).r * WaterlineMaxDist;
        edge = 1.0 - clamp(waterDist / FoamWidth, 0.0, 1.0);
    }

    // Surface spots: low-frequency world-space noise blobs, on the same pixel grid as the foam, that
    // fade in/out in place (morph blends two offset noise layers over time, no translation).
    const float SPOT_CELL_BLOCKS = 3.0;    // blob feature size in blocks
    const float SPOT_MORPH_SPEED = 500.0;  // how fast the layout reshuffles
    vec2 sworld = floor(worldXZ * FOAM_PIXELS_PER_BLOCK) / FOAM_PIXELS_PER_BLOCK;
    vec2 cell = sworld / SPOT_CELL_BLOCKS;
    float morph = 0.5 + 0.5 * sin(GameTime * SPOT_MORPH_SPEED);
    float s  = mix(fbm(cell), fbm(cell + vec2(31.0, 47.0)), morph);
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

        // Fade only the far rim (3D distance) so the surface stays solid where you look and the outer
        // edge reveals the real horizon; discard once fully faded.
        float fade = 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, length(relPos));
        outColor.a *= fade;
        if (outColor.a <= 0.003) {
            discard;
        }

        fragColor = outColor;
    }
}
