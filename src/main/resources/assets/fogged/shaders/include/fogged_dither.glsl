// Ordered (Bayer) screen-door dither, shared by fog_plane.fsh and the code injected into a shader
// pack's programs (see IrisShaderPatcher). Pure functions of their parameters -- no uniforms -- so this
// can be imported anywhere, and can be pasted into a foreign shader without clashing with it.
//
// This is the dither Block Dithering uses for blocks near the camera: a 4x4 Bayer matrix compared
// against a visibility in 0..1 decides per screen pixel whether the fragment survives, so partial
// coverage comes out as a stable pixel pattern instead of alpha. Unlike alpha it needs no sorting and
// leaves depth writes alone, which is what lets it work inside a shader pack's own gbuffer pass.

const mat4 FOGGED_BAYER = mat4(
     1.0 / 17.0,  9.0 / 17.0,  3.0 / 17.0, 11.0 / 17.0,
    13.0 / 17.0,  5.0 / 17.0, 15.0 / 17.0,  7.0 / 17.0,
     4.0 / 17.0, 12.0 / 17.0,  2.0 / 17.0, 10.0 / 17.0,
    16.0 / 17.0,  8.0 / 17.0, 14.0 / 17.0,  6.0 / 17.0
);

// Threshold for this screen position; pixelSize screen pixels per dither cell. A fragment with
// visibility below it is dropped: visibility 1 always survives, 0 never does.
float fogged_bayer(vec2 fragCoord, float pixelSize) {
    ivec2 c = ivec2(floor(fragCoord / max(pixelSize, 1.0)));
    return FOGGED_BAYER[c.x & 3][c.y & 3];
}

float fogged_easeInOutCubic(float x) {
    return x < 0.5 ? 4.0 * x * x * x : 1.0 - pow(-2.0 * x + 2.0, 3.0) / 2.0;
}

// Visibility of a fragment `dist` blocks from the camera under the near dither: minVis at and inside
// `near`, ramping (eased) to fully visible at `far`.
float fogged_nearVisibility(float dist, float near, float far, float minVis) {
    float v = clamp(smoothstep(near, far, dist), minVis, 1.0);
    return fogged_easeInOutCubic(v);
}
