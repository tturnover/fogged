// Shared waterline-map sampling for fog_plane.fsh and fog_vapor.fsh. Import AFTER the includer has
// declared the uniforms it reads: Sampler0 (the waterline map), WaterlineOrigin, WaterlineSize,
// WaterlineMaxDist, FoamPixelsPerBlock and FoamWidth.
//
// Shared so the two layers cannot drift apart: the vapour thins where the foam is strong, and it can
// only line up with the ring the surface draws if both read the map the same way -- same pixel snap,
// same texel-centre offset, same two-ring strengths.

// Two independent foam rings combined by max. Each ring's strength scales BOTH its band width
// (FoamWidth * strength) and its intensity, so a plant (0.5) reads half as wide and half as strong as
// a solid or entity (1.0). They form separately so neither overrides the other.
const float FOGGED_SOLID_STRENGTH = 1.0;
const float FOGGED_PLANT_STRENGTH = 0.5;

// World position snapped to the foam pixel grid, so everything drawn on the surface pixelates on the
// same lattice and lines up with the blocks: the foam ring, the surface spots, and the vapour's
// thinning. Anything reading the map or drawing over it should snap through here.
vec2 fogged_pixelSnap(vec2 worldXZ) {
    return floor(worldXZ * FoamPixelsPerBlock) / FoamPixelsPerBlock;
}

// Raw map sample at this world position: R = distance to the nearest solid block or entity, G =
// distance to the nearest plant. Off the map it reads "nothing near".
//
// Sampled at the texel centre (+0.5) so NEAREST does not bias the whole ring a cell in -X/-Z.
vec2 fogged_waterline(vec2 worldXZ) {
    vec2 pworld = fogged_pixelSnap(worldXZ);
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize + 0.5 / (WaterlineSize * FoamPixelsPerBlock);
    if (luv.x < 0.0 || luv.x > 1.0 || luv.y < 0.0 || luv.y > 1.0) {
        return vec2(1.0);
    }
    return texture(Sampler0, luv).rg;
}

// How strongly foam rings this spot, 0 well clear of anything to 1 hard against it.
float fogged_foamEdge(vec2 wl) {
    if (FoamWidth <= 0.0) {
        return 0.0;
    }
    float solidEdge = FOGGED_SOLID_STRENGTH
            * (1.0 - clamp(wl.r * WaterlineMaxDist / (FoamWidth * FOGGED_SOLID_STRENGTH), 0.0, 1.0));
    float plantEdge = FOGGED_PLANT_STRENGTH
            * (1.0 - clamp(wl.g * WaterlineMaxDist / (FoamWidth * FOGGED_PLANT_STRENGTH), 0.0, 1.0));
    return max(solidEdge, plantEdge);
}
