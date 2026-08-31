// Shared waterline-map sampling for fog_plane.fsh and fog_vapor.fsh. Import AFTER the includer has
// declared the uniforms it reads: Sampler0 (the waterline map), WaterlineOrigin, WaterlineSize,
// WaterlineMaxDist, FoamPixelsPerBlock, MapPixelsPerBlock and FoamWidth.
//
// Shared so the two layers cannot drift apart: the vapour thins where the foam is strong, and it can
// only line up with the ring the surface draws if both read the map the same way -- same pixel snap,
// same texel-centre offset, same two-ring strengths.

// Two independent foam rings combined by max. Each ring's strength scales BOTH its band width
// (FoamWidth * strength) and its intensity, so a plant (0.5) reads half as wide and half as strong as
// a solid or entity (1.0). They form separately so neither overrides the other.
const float FOGGED_SOLID_STRENGTH = 1.0;
const float FOGGED_PLANT_STRENGTH = 0.5;

// The map is a square of finite reach, and foam that simply stopped where its data ran out drew a
// hard-edged square around the player that moved with them. So the foam is eased out over the rim of
// the square's INSCRIBED circle instead: it ends in a soft ring, well inside the data, and the corners
// of the map -- the only part that could give the square away -- never carry foam at all.
// Fraction of that circle's radius the fade spans.
const float FOGGED_RIM_FADE = 0.3;

// World position snapped to the foam pixel grid, so everything drawn on the surface pixelates on the
// same lattice and lines up with the blocks: the foam ring, the surface spots, and the vapour's
// thinning. Anything reading the map or drawing over it should snap through here.
vec2 fogged_pixelSnap(vec2 worldXZ) {
    return floor(worldXZ * FoamPixelsPerBlock) / FoamPixelsPerBlock;
}

// The map's own cell grid, which is coarser than the pixels drawn on it (see WaterlineMap's
// EFFECT_DETAIL): the UV below has to be built from the cells that actually exist, while the position
// being looked up is snapped to the finer grid the effects are drawn on.
vec2 fogged_cellSnap(vec2 worldXZ) {
    return floor(worldXZ * MapPixelsPerBlock) / MapPixelsPerBlock;
}

// Raw map sample at this world position: R = distance to solid/entity, G = distance to plant,
// B = the liquid mask. Off the map it reads "nothing near, no liquid".
//
// Sampled at the texel centre (+0.5) so NEAREST does not bias the whole ring a cell in -X/-Z.
vec3 fogged_waterline(vec2 worldXZ) {
    vec2 pworld = fogged_pixelSnap(worldXZ);
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize + 0.5 / (WaterlineSize * MapPixelsPerBlock);
    if (luv.x < 0.0 || luv.x > 1.0 || luv.y < 0.0 || luv.y > 1.0) {
        return vec3(1.0, 1.0, 0.0);
    }
    vec3 wl = texture(Sampler0, luv).rgb;

    // Ease both distance channels back to "nothing near" towards the rim (see FOGGED_RIM_FADE). Done
    // on the distances rather than on the finished ring so the band narrows as it fades, which is how
    // foam thins out anyway -- and so every reader of the map (surface, vapour) gets the same edge.
    // The liquid mask is left alone: it is a cut-out, not a gradient, and nothing draws past the rim.
    float rim = 0.5 * WaterlineSize;
    float r = length(pworld - (WaterlineOrigin + rim));
    float keep = 1.0 - smoothstep(rim * (1.0 - FOGGED_RIM_FADE), rim, r);
    wl.rg = mix(vec2(1.0), wl.rg, keep);
    return wl;
}

// How strongly foam rings this spot, 0 well clear of anything to 1 hard against it.
float fogged_foamEdge(vec3 wl) {
    if (FoamWidth <= 0.0) {
        return 0.0;
    }
    float solidEdge = FOGGED_SOLID_STRENGTH
            * (1.0 - clamp(wl.r * WaterlineMaxDist / (FoamWidth * FOGGED_SOLID_STRENGTH), 0.0, 1.0));
    float plantEdge = FOGGED_PLANT_STRENGTH
            * (1.0 - clamp(wl.g * WaterlineMaxDist / (FoamWidth * FOGGED_PLANT_STRENGTH), 0.0, 1.0));
    return max(solidEdge, plantEdge);
}
