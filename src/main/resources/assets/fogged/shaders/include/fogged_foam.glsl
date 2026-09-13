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

// Where the map's answer starts fading out, as a share of its half-width, measured from the camera
// outwards; it reaches zero at the inscribed radius less a block. == WaterlineMap.FOAM_FADE_START /
// FOAM_FADE_MARGIN, which fade the baked foam over the same circle.
const float FOGGED_FOAM_FADE_START = 0.60;
const float FOGGED_FOAM_FADE_MARGIN = 1.0;

// How far into the map this point is, 1 well inside and 0 from the last radius that is certainly on
// the map outwards. The map is a square around the camera while the plane runs to the render distance,
// so anything that stops at the rim draws the rim -- and under a pack the plane is seamed on exactly
// that outline (FogPlaneRenderer.drawThroughPack). Stopping on the square's inscribed circle instead,
// less a block for the whole-block re-anchoring, means the map's contribution is already zero wherever
// the rim can be sampled: both sides of the seam then hold the same bare plane colour.
float fogged_mapReach(float radius, float halfSize) {
    float fadeTo = max(1.0, halfSize - FOGGED_FOAM_FADE_MARGIN);
    float fadeFrom = min(FOGGED_FOAM_FADE_START * halfSize, fadeTo - 1.0);
    return 1.0 - smoothstep(fadeFrom, fadeTo, radius);
}

// World position snapped to the foam pixel grid, so everything drawn on the surface pixelates on the
// same lattice and lines up with the blocks: the foam ring, the surface spots, and the vapour's
// thinning. Anything reading the map or drawing over it should snap through here.
vec2 fogged_pixelSnap(vec2 worldXZ) {
    return floor(worldXZ * FoamPixelsPerBlock) / FoamPixelsPerBlock;
}

// Raw map sample at this world position: R = distance to the nearest solid block or entity CROSSING
// the surface, G = distance to the nearest plant, B = distance to the nearest solid or entity that
// only sits near the surface (the foam band). Off the map it reads "nothing near", and it is eased
// to that answer on the circle fogged_mapReach describes, so the rim is never where it changes.
//
// B is kept apart from R for the dissolve's sake: the foam rings whichever is nearer, but the plane
// only dissolves against things that actually stand through it (fogged_nearCrossing reads R alone).
// With both in one channel, a block half a block under the surface opened the plane above it.
//
// Sampled at the texel centre (+0.5) so NEAREST does not bias the whole ring a cell in -X/-Z.
vec3 fogged_waterline(vec2 worldXZ) {
    vec2 pworld = fogged_pixelSnap(worldXZ);
    vec2 luv = (pworld - WaterlineOrigin) / WaterlineSize + 0.5 / (WaterlineSize * FoamPixelsPerBlock);
    if (luv.x < 0.0 || luv.x > 1.0 || luv.y < 0.0 || luv.y > 1.0) {
        return vec3(1.0);
    }
    float halfSize = WaterlineSize * 0.5;
    float reach = fogged_mapReach(length(pworld - (WaterlineOrigin + halfSize)), halfSize);
    return mix(vec3(1.0), texture(Sampler0, luv).rgb, reach);
}

// How strongly foam rings this spot, 0 well clear of anything to 1 hard against it.
float fogged_foamEdge(vec3 wl) {
    if (FoamWidth <= 0.0) {
        return 0.0;
    }
    float solid = min(wl.r, wl.b); // a crossing or something merely near it, whichever is closer
    float solidEdge = FOGGED_SOLID_STRENGTH
            * (1.0 - clamp(solid * WaterlineMaxDist / (FoamWidth * FOGGED_SOLID_STRENGTH), 0.0, 1.0));
    float plantEdge = FOGGED_PLANT_STRENGTH
            * (1.0 - clamp(wl.g * WaterlineMaxDist / (FoamWidth * FOGGED_PLANT_STRENGTH), 0.0, 1.0));
    return max(solidEdge, plantEdge);
}

// How close this point is to something that actually CROSSES the surface (1 at it, 0 beyond reach),
// from the same map: only the row at the boundary is seeded, so what merely lies beneath the surface
// does not register. The soft occlusion is gated by this so it softens the silhouette of a crossing
// block or entity without letting everything a few blocks under the surface show through as a ghost.
// Solids and entities only (R): a plant at the boundary is a blade or two in the surface, and the
// dissolve around each came out as a sprinkling of dark specks across the whole plane.
float fogged_nearCrossing(vec3 wl) {
    float d = wl.r * WaterlineMaxDist;
    return 1.0 - smoothstep(0.75, 2.0, d);
}
