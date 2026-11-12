// What Distant Horizons drew at this pixel, for the far pass of fog_plane.fsh. Import AFTER the
// includer has declared the uniforms it reads: Sampler4 (DhFarPass's snapshot of LOD depth, taken before
// the plane went in), ScreenSize, LodActive (0 when there are no LODs this frame), LodClearDepth (what a
// pixel nothing was drawn to reads as: 0 in reverse Z, 1 forward),
// LodInvViewProj (camera-relative, built from DH's own matrices) and FoamWidth.

// LODs carry no block data, so the foam out there is not a ring around anything: it is how shallow the
// LOD terrain lies beneath this point of the surface. A shore is where that goes to nothing. The gap is
// vertical, so it holds still as the view angle changes, where the distance along the ray would not.
//
// The band is wider than the near ring, or a ring a block or two wide falls between pixels within a
// few hundred blocks and the shoreline flickers. It grows with distance, and stops growing at
// LOD_FOAM_MAX_EXTRA so that flat ground lying just under the surface does not all turn to foam.
const float LOD_FOAM_HEIGHT = 2.0;       // blocks of depth the band covers near by, per FoamWidth
const float LOD_FOAM_PER_DISTANCE = 0.002;
const float LOD_FOAM_MAX_EXTRA = 3.0;

// Camera-relative position of the LOD at this pixel. False when there is none.
bool fogged_lodAt(out vec3 pos) {
    pos = vec3(0.0);
    if (LodActive < 0.5) {
        return false;
    }
    vec2 uv = gl_FragCoord.xy / ScreenSize;
    float depth = texture(Sampler4, uv).r;
    if (depth == LodClearDepth) {
        return false;
    }
    // DH runs without glClipControl, so even in reverse Z the window depth maps back the ordinary way.
    vec4 eye = LodInvViewProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    pos = eye.xyz / eye.w;
    return true;
}

// How strongly foam rings this surface point, given the LOD behind it: 0 in deep water, 1 at the shore.
float fogged_lodFoam(vec3 lodPos, vec3 surfacePos) {
    if (FoamWidth <= 0.0) {
        return 0.0;
    }
    float gap = (surfacePos.y - lodPos.y) * LodFoamSide;
    if (gap < 0.0) {
        return 0.0; // on the surface's near side: not beneath it
    }
    float width = FoamWidth * LOD_FOAM_HEIGHT + min(length(surfacePos) * LOD_FOAM_PER_DISTANCE, LOD_FOAM_MAX_EXTRA);
    return 1.0 - clamp(gap / width, 0.0, 1.0);
}
