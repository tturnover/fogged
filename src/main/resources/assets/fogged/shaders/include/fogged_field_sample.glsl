// Reading the baked noise fields (NoiseField), for fog_vapor.fsh and fog_plane.fsh. Import after
// fogged_field.glsl, with these declared by the includer: Sampler1 (the field texture), NoiseOrigin
// (anchor-relative world XZ of its corner), NoiseCells (its edge length in texels), NoiseCellsPerBlock,
// NoiseValid (0 when there is no texture this frame), NoiseTime (the boil clock it was baked at).
//
// Off the texture the fields are evaluated in place, at the same clock, so the seam is invisible. That
// is the only place the noise costs anything: within reach every pixel of every mist sheet is one
// texel fetch instead of two fbm evaluations, which was most of what the mod spent per frame.

// R = vapour field, G = spot field, for the pixel-snapped q (a cell corner).
vec2 fogged_fields(vec2 q, float wispScale) {
    if (NoiseValid > 0.5) {
        vec2 cell = (q - NoiseOrigin) * NoiseCellsPerBlock;
        if (cell.x >= 0.0 && cell.y >= 0.0 && cell.x < NoiseCells && cell.y < NoiseCells) {
            return texelFetch(Sampler1, ivec2(cell + 0.5), 0).rg;
        }
    }
    return vec2(fogged_vaporField(q, NoiseTime, wispScale), fogged_spotField(q, NoiseTime));
}
