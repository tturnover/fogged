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

    // --- foam edge: sample the world-space waterline map for the distance to the nearest block /
    //     entity crossing the surface, then ring it within FoamWidth. Purely world-space, so it never
    //     cuts off or flickers with view angle, and the foam sits on the water around objects. ---
    // Snap the lookup to the foam pixel grid so the foam is pixelated and lines up with blocks. Sample
    // at the texel CENTRE (+0.5 texel) so NEAREST never rounds to the neighbouring cell at a boundary,
    // which would shift the whole foam ring by ~a cell in -X/-Z. (Foam grid == map cell grid.)
    vec2 pworld = floor(worldXZ * FOAM_PIXELS_PER_BLOCK) / FOAM_PIXELS_PER_BLOCK;
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize + 0.5 / (WaterlineSize * FOAM_PIXELS_PER_BLOCK);
    float edge = 0.0;
    if (FoamWidth > 0.0 && luv.x >= 0.0 && luv.x <= 1.0 && luv.y >= 0.0 && luv.y <= 1.0) {
        float waterDist = texture(Sampler0, luv).r * WaterlineMaxDist;
        edge = 1.0 - clamp(waterDist / FoamWidth, 0.0, 1.0);
    }

    // --- stationary, non-repeating spots that fade in and out in place ---
    // Big low-frequency blobs from world-space value noise (no texture, so no tiling/repeat),
    // snapped to the foam pixel grid for chunky pixels.
    const float SPOT_CELL_BLOCKS = 3.0;    // blob feature size in blocks
    const float SPOT_MORPH_SPEED = 500.0;  // how fast the whole blob layout reshuffles
    // Snap on the SAME grid as the foam (16 px/block) so the spot pixels line up 1:1 with foam pixels.
    vec2 sworld = floor(worldXZ * FOAM_PIXELS_PER_BLOCK) / FOAM_PIXELS_PER_BLOCK;
    vec2 cell = sworld / SPOT_CELL_BLOCKS;
    // Spatial blob field morphs in place over time (blend two offset noise layers, no translation),
    // so the overall noise keeps changing instead of being a fixed pattern.
    float morph = 0.5 + 0.5 * sin(GameTime * SPOT_MORPH_SPEED);
    float s  = mix(fbm(cell), fbm(cell + vec2(31.0, 47.0)), morph);
    // Overlay the spots with the SAME colour steps as the edge foam: quantise into FOAM_STEPS bands
    // (0.25/0.5/0.75/1.0) and scale by FoamColor.a, so a spot pixel is indistinguishable from a foam pixel.
    float t = smoothstep(0.45, 0.70, s);
    // Map the noise onto the two lowest foam steps only (0.25/0.50) -- never the 0.75 or 1.0 bands.
    float lum = t <= 0.0 ? 0.0 : (t < 0.5 ? 0.25 : 0.5);
    lum *= FoamColor.a; // same foam strength the edge foam uses

    if (FoamDebug > 0.5) {
        // R = foam edge factor, B = animated water shading.
        fragColor = vec4(edge, 0.0, lum, 1.0);
    } else {
        // Dark water base that BOTH the edge foam and the surface spots blend up from, so identical
        // band values produce identical colours (the spots are just foam that isn't next to an edge).
        vec3 water = color.rgb * 0.40;

        // Foam: a gradient of the distance-to-waterline, strongest right at the object's edge and
        // fading out across FoamWidth. No noise texture, so it follows the contour of every block /
        // entity -- concentric bands that wrap around objects. Quantised into FOAM_STEPS bands.
        float foam = edge * edge * (3.0 - 2.0 * edge); // smoothstep-shaped ramp of the edge factor
        foam = ceil(foam * FOAM_STEPS) / FOAM_STEPS;   // stair-step the gradient into chunky bands
        foam *= FoamColor.a;                           // foam strength from the configured alpha

        // Strength of the foam here, edge or surface spot, whichever is stronger (same colour for both).
        float f = max(foam, lum);

        // Fog the WATER BASE only, then lay the foam on top, so the foam is never blended away by the
        // fog -- it stays visible across the whole surface, near and far. Distance is per-fragment from
        // the camera-relative position. The base blends into the same plane-coloured murk you see
        // beneath the boundary.
        float fogDist = fog_distance(relPos, FogShape);
        vec4 fogged = linear_fog(vec4(water, color.a), fogDist, FogStart, FogEnd, FogColor);

        vec4 outColor = color;
        outColor.rgb = mix(fogged.rgb, FoamColor.rgb, f);
        outColor.a = max(fogged.a, f);

        // The plane is semi-transparent so you can see it through water, but the opaque seabed BELOW it
        // was drawn earlier and otherwise shows through clear. Where the fog is building, the fragment
        // is already the plane colour, so raise its alpha by the fog amount: distant water/seabed is
        // tinted to the plane colour while the near footprint right under you stays see-through.
        float fogA = clamp((fogDist - FogStart) / max(FogEnd - FogStart, 1e-4), 0.0, 1.0);
        outColor.a = max(outColor.a, fogA);

        // Fade only the far EDGE of the quad to transparent (true 3D distance), so the surface stays
        // solid everywhere you actually look -- even from far below it -- and only the rim hides itself.
        float fade = 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, length(relPos));
        outColor.a *= fade;

        // The plane writes depth (to hide water below it), so discard the fully-faded rim -- otherwise
        // it would write depth over the far horizon it is meant to reveal.
        if (outColor.a <= 0.003) {
            discard;
        }

        fragColor = outColor;
    }
}
