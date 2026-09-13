#version 150

// Bakes the surface's two noise fields into a world-anchored texture, one texel per pixel-snap cell,
// once per tick (see NoiseField): R = the vapour's terrace field, G = the plane's spot field. The
// sheets and the plane then fetch a texel where they used to evaluate the noise per pixel.

uniform vec2 NoiseOrigin;        // anchor-relative world XZ of texel (0, 0)'s cell corner
uniform float NoiseCellsPerBlock;
uniform float Time;              // the boil clock this bake is at (FogShaders#animTimeSeconds)
uniform float WispScale;

out vec4 fragColor;

const float NOISE_PERIOD_BLOCKS = 4096.0; // == FogPlaneRenderer.NOISE_ANCHOR (see Config's mirrored constants)
#moj_import <fogged:fogged_noise.glsl>
#moj_import <fogged:fogged_field.glsl>

void main() {
    // The cell corner, exactly as the readers snap to it: floor(worldXZ * cells) / cells.
    vec2 q = NoiseOrigin + floor(gl_FragCoord.xy) / NoiseCellsPerBlock;
    fragColor = vec4(fogged_vaporField(q, Time, WispScale), fogged_spotField(q, Time), 0.0, 1.0);
}
