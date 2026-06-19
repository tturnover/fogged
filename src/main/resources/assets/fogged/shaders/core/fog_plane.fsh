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

void main() {
    vec4 color = vertexColor * ColorModulator;

    // --- foam edge: sample the world-space waterline map for the distance to the nearest block /
    //     entity crossing the surface, then ring it within FoamWidth. Purely world-space, so it never
    //     cuts off or flickers with view angle, and the foam sits on the water around objects. ---
    vec2 luv = (worldXZ - WaterlineOrigin) / WaterlineSize;
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

    // Foam mask (G): static & world-locked so shoreline foam pixels sit still on the block grid.
    float texNoise = texture(Sampler1, wuv).g;

    if (FoamDebug > 0.5) {
        // R = foam edge factor, B = animated water shading.
        fragColor = vec4(edge, 0.0, lum, 1.0);
        return;
    }

    // Base surface: plane colour shaded by the animated water pattern. Kept noticeably darker than
    // the plane/sky/fog colour so the water reads as a solid surface even over open sky or voids.
    color.rgb *= (0.40 + 0.45 * lum);

    // Foam: per-texel white noise gated by the dilated edge. Cap the edge below 1 so the threshold
    // never reaches 0 -- that keeps even the contact line broken into individual block-grid pixels
    // (dense near the waterline, sparse further out) instead of a solid white blob.
    float foam = step(1.0 - edge * 0.85, texNoise);

    // Foam on top of the base water.
    vec4 outColor = color;
    vec3 foamColor = mix(vec3(1.0), color.rgb, 0.15); // white with a faint plane-colour tint
    outColor.rgb = mix(outColor.rgb, foamColor, foam);
    outColor.a = max(color.a, foam);

    // Fade only the far EDGE of the quad to transparent (true 3D distance), so the surface stays
    // solid everywhere you actually look -- even from far below it -- and only the rim hides itself.
    float fade = 1.0 - smoothstep(PlaneFadeStart, PlaneFadeEnd, length(relPos));
    outColor.a *= fade;

    fragColor = outColor;
}
