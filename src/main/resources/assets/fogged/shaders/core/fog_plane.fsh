#version 150

uniform sampler2D Sampler0; // world-space waterline distance map (R = distance to nearest waterline)
uniform sampler2D Sampler1; // procedural, seamlessly-tiling water/foam texture (R=water, G=foam)

uniform vec4 ColorModulator;
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

// Must match WaterTexture: TILE_BLOCKS blocks span SIZE texels (16 texels per block).
const float WATER_TILE_BLOCKS = 4.0;
const float WATER_TILE_TEXELS = 64.0;

// Foam pixelation: 16 pixels per block (matches vanilla block textures) and the number of discrete
// gradient bands the foam is quantised into.
const float FOAM_PIXELS_PER_BLOCK = 16.0;
const float FOAM_STEPS = 4.0;

void main() {
    vec4 color = vertexColor * ColorModulator;

    // --- foam edge: sample the world-space waterline map for the distance to the nearest block /
    //     entity crossing the surface, then ring it within FoamWidth. Purely world-space, so it never
    //     cuts off or flickers with view angle, and the foam sits on the water around objects. ---
    // Snap the lookup to a 16-px-per-block grid so the foam is pixelated and lines up with blocks.
    vec2 pworld = floor(worldXZ * FOAM_PIXELS_PER_BLOCK) / FOAM_PIXELS_PER_BLOCK;
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize;
    float edge = 0.0;
    if (FoamWidth > 0.0 && luv.x >= 0.0 && luv.x <= 1.0 && luv.y >= 0.0 && luv.y <= 1.0) {
        float waterDist = texture(Sampler0, luv).r * WaterlineMaxDist;
        edge = 1.0 - clamp(waterDist / FoamWidth, 0.0, 1.0);
    }

    // --- world-aligned, pixelated water sampled NEAREST from the procedural tile ---
    // UVs come from world XZ so texels line up with blocks (16 texels/block).
    vec2 wuv = worldXZ / WATER_TILE_BLOCKS;

    // Water shimmer (R): two layers scrolling in whole-texel steps so the pixels stay grid-snapped.
    vec2 scrollA = vec2(floor(GameTime * 12000.0), 0.0) / WATER_TILE_TEXELS;
    vec2 scrollB = vec2(0.0, floor(GameTime * 7000.0)) / WATER_TILE_TEXELS;
    float lumRaw = mix(texture(Sampler1, wuv + scrollA).r,
                       texture(Sampler1, wuv + scrollB + vec2(24.0, 40.0) / WATER_TILE_TEXELS).r, 0.5);
    float lum = smoothstep(0.35, 0.65, lumRaw); // push to clear light/dark bands

    if (FoamDebug > 0.5) {
        // R = foam edge factor, B = animated water shading.
        fragColor = vec4(edge, 0.0, lum, 1.0);
        return;
    }

    // Base surface: plane colour shaded by the animated water pattern. Kept noticeably darker than
    // the plane/sky/fog colour so the water reads as a solid surface even over open sky or voids.
    vec3 baseColor = color.rgb * (0.40 + 0.45 * lum);

    // Foam: a gradient of the distance-to-waterline, strongest right at the object's edge and fading
    // out across FoamWidth. No noise texture, so it follows the contour of every block/entity --
    // concentric bands that wrap around objects. Quantised into FOAM_STEPS discrete, visible bands.
    float foam = edge * edge * (3.0 - 2.0 * edge); // smoothstep-shaped ramp of the edge factor
    foam = ceil(foam * FOAM_STEPS) / FOAM_STEPS;   // stair-step the gradient into chunky bands

    // Gradient from the plane/water colour (outer) to near-white foam (at the waterline).
    vec3 foamColor = mix(color.rgb, vec3(1.0), 0.85);
    vec4 outColor = color;
    outColor.rgb = mix(baseColor, foamColor, foam);
    outColor.a = max(color.a, foam);

    // Fade only the far EDGE of the quad to transparent (true 3D distance), so the surface stays
    // solid everywhere you actually look -- even from far below it -- and only the rim hides itself.
    float fade = 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, length(relPos));
    outColor.a *= fade;

    fragColor = outColor;
}
