// The two world-anchored noise fields the surface draws with, as pure functions of the pixel-snapped,
// anchor-relative world XZ `q` and the boil clock -- shared by the pass that bakes them into a texture
// once per tick (noise_field.fsh) and by the shaders that read that texture and fall back to
// evaluating them where it does not reach (fog_vapor.fsh, fog_plane.fsh, fogged_iris.glsl). Import
// after fogged_noise.glsl, with NOISE_PERIOD_BLOCKS declared: both fields tile at that period in q.

// The vapour's terrace / wisp field: two value-noise layers, the second at twice the frequency (a
// whole-number multiple, so it tiles too). wispScale is chosen so NOISE_PERIOD_BLOCKS * wispScale is
// an integer and the lattice wraps exactly with the anchor. Animates by advancing the noise's time
// axis, forward only, so it boils in place without sliding.
const float FOGGED_VAPOR_TIME_RATE = 0.1; // noise time-axis advance per second (~one reshuffle per 10 s)

float fogged_vaporField(vec2 q, float time, float wispScale) {
    vec2 p = q * wispScale;
    float t = time * FOGGED_VAPOR_TIME_RATE;
    float per = NOISE_PERIOD_BLOCKS * wispScale;
    return fogged_fbm3(p, t, per) * 0.6 + fogged_fbm3(p * 2.0 + 19.0, t * 1.3 + 7.0, per) * 0.4;
}

// The plane's surface-spot field: low-frequency blobs, FOGGED_SPOT_CELL_BLOCKS across, that fade in and
// out in place.
const float FOGGED_SPOT_CELL_BLOCKS = 4.0; // divides NOISE_PERIOD_BLOCKS, so it tiles
const float FOGGED_SPOT_TIME_RATE = 0.1;

float fogged_spotField(vec2 q, float time) {
    return fogged_fbm3(q / FOGGED_SPOT_CELL_BLOCKS, time * FOGGED_SPOT_TIME_RATE, NOISE_PERIOD_BLOCKS / FOGGED_SPOT_CELL_BLOCKS);
}
