#version 150

// The murk as a screen-space pass over the finished frame (see MurkComposite): every pixel is fogged
// by how much of the line of sight to it runs through the murk, which is the half-space on the
// fogged side of the boundary. Reading the scene depth back is what lets this see the murk from
// OUTSIDE it: the fog uniforms only ever describe the camera's own surroundings, so nothing they
// drive can fog the world glimpsed down through a hole in the surface -- which used to show clear
// to the sea floor.
//
// Under an Iris shader pack this pass is also the whole of the murk fog: a pack computes its own
// fog and never reads the vanilla uniforms the murk takes over (FogModifier).
//
// Sampler0 is a copy of the main render target's depth with the whole frame in it (SceneDepth.SCENE,
// retaken at the end) -- under Iris that is also the pack's depthtex0, so it holds everything the
// pack drew, hand included. Sampler1 is the copy from before the plane and the water (SceneDepth.OPAQUE),
// under a pack only: from inside the murk the world behind a pixel of water is fogged by its own
// distance, as the vanilla fog fogs it fragment by fragment before the water blends over it, where a
// pack fogs it its own way. Copies, because main is what this pass draws into. Named Sampler0/1 for
// the reason depth_copy.fsh gives.
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform float OpaqueValid;  // 0 when there is no pre-water copy from this frame: measure to Sampler0

uniform mat4 InvProjMat;    // inverse of the world projection, to unproject depth into view space
uniform mat4 InvViewMat;    // inverse of the camera view, to turn that into camera-relative world space
uniform vec3 MurkColor;     // the plane colour, as FogModifier sets the fog colour
uniform vec2 MurkRange;     // fog start / end in blocks of murk crossed (FogModifier: 0.25 * fogDistance, fogDistance)
uniform float PlaneY;       // camera-relative height of the boundary surface
uniform float MurkSide;     // +1: the murk lies below PlaneY, -1: above it (flipFog)
// The camera is in the murk. Then everything fogs by plain distance, as the fog uniforms do it, and
// the surface itself by the tighter PlaneFogEnd: on the murk side the core path fogs the plane from
// 0 to 0.15 * fogDistance (FogPlaneRenderer) so the ceiling reads as murk within a few blocks, and
// the pack path cannot fog its own draw, so every pixel that unprojects onto the surface gets it here.
uniform float CameraInside;
uniform float PlaneFogEnd;
uniform float FogInside;    // 0 when something else fogs the inside (the vanilla fog uniforms): only dim
uniform float Darkness;     // the surface's shadow: how much darker the world is under it (Config.murkDarkness)
const float DARKNESS_RAMP_BLOCKS = 3.0; // the shadow reaches full this deep under the surface
// From the clear side the murk is only ever seen down through the surface, so it ends where the
// surface starts to fade (FogPlaneRenderer's PlaneFadeStart/End), judged at the point where the line
// of sight enters the murk. The plane's rim fade exists to hand over to the horizon, and what it
// reveals there has to be the horizon: fogging it turned the whole fade zone into a dark band.
uniform vec2 PlaneFade;

out vec4 fragColor;

const float WATER_SEE_THROUGH = 0.8; // the share of the murk behind water that shows through it: a pack's water is mostly refraction, so most of it

// Camera-relative world position of the pixel at this depth. Sky and anything undrawn sit at depth
// 1, which unprojects to infinity: take the direction from a point half-way in instead and push it
// out past any fog range.
vec3 unproject(float depth) {
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(Sampler0, 0));
    bool sky = depth >= 1.0;
    vec4 view = InvProjMat * vec4(vec3(uv, sky ? 0.5 : depth) * 2.0 - 1.0, 1.0);
    vec3 rel = (InvViewMat * vec4(view.xyz / view.w, 1.0)).xyz;
    return sky ? normalize(rel) * 1.0e5 : rel;
}

// Whether this pixel is the murk surface itself rather than something else that happens to reach its
// height. Matching the height alone is not enough: the surface sits at a fractional Y, so no block's
// TOP shares it -- but a block's SIDE runs through every height between its own, so the sides of
// everything the boundary cuts through had a row of pixels passing the test, and got painted with the
// surface's fog: a murk-coloured line drawn across every column where it meets the plane.
//
// The pre-plane depth copy settles it as a fact rather than a guess. Sampler1 is the frame as it stood
// BEFORE the plane was drawn (SceneDepth.OPAQUE), so a pixel is the plane exactly when the finished
// frame is nearer than that copy: the column's side face sits at the same depth in both and is not the
// plane, while the plane is in front of whatever the copy holds. The height band stays, and is what
// keeps the water -- also drawn after the copy -- from being taken for the surface.
//
// An earlier version asked instead whether the surface under the pixel was flat, by comparing the
// screen-space gradients of rel.y and rel.xz. That drew a shape of its own: |dFdx| + |dFdy| summed
// over x and z is an L1 norm, and its level set is a square turned 45 degrees -- with rel.y rebuilt
// from quantised depth, the gradient grows with distance until the test flips, and it flipped at a
// different distance along the world axes than along the diagonals. The result was a diamond edge
// standing around the camera. Derivatives of a value read back from a depth buffer -- one the plane
// itself dithers into -- have no bound worth relying on here.
bool onPlaneSurface(vec3 rel, float dist, float depth, float behind) {
    return depth < 1.0
        && abs(rel.y - PlaneY) < 0.05 + dist * 0.004
        && (OpaqueValid < 0.5 || behind > depth);
}

void main() {
    float depth = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r;
    vec3 rel = unproject(depth);
    float dist = length(rel);
    // The frame before the plane and the water, where there is one this frame (see SceneDepth.OPAQUE).
    float behind = OpaqueValid > 0.5 ? texelFetch(Sampler1, ivec2(gl_FragCoord.xy), 0).r : depth;
    bool onPlane = onPlaneSurface(rel, dist, depth, behind);

    float fog;
    if (CameraInside > 0.5 && FogInside < 0.5) {
        fog = 0.0; // fogged already; the dim below is all this pass adds
    } else if (CameraInside > 0.5) {
        // From within, the murk is the camera's whole world: plain distance.
        fog = smoothstep(MurkRange.x, MurkRange.y, dist);
        // Water in front of something (Sampler1 sees further than Sampler0) also carries the murk of
        // what is behind it, the way it does without a pack, where the world behind is fogged fragment
        // by fragment and the water blends over that: only the share of the water that is see-through
        // takes it, so the surface keeps its own colour instead of vanishing into flat murk. Not the
        // hand, though, which is drawn after that copy too: Iris draws it with its depth squeezed
        // right up against the camera, so it is told apart by distance alone -- eased in over a
        // sliver, since a hard cut showed as a disc on water the camera was nearly touching.
        if (OpaqueValid > 0.5) {
            if (behind > depth) {
                float fogBehind = smoothstep(MurkRange.x, MurkRange.y, length(unproject(behind)));
                fog += (1.0 - fog) * WATER_SEE_THROUGH * fogBehind * smoothstep(0.06, 0.14, dist);
            }
        }
        // The surface's own tighter fog (see PlaneFogEnd), on the surface only (see onPlaneSurface).
        if (PlaneFogEnd > 0.0 && onPlane) {
            fog = max(fog, smoothstep(0.0, PlaneFogEnd, dist));
        }
    } else if (onPlane) {
        fog = 0.0; // the surface itself, seen from the clear side: the murk is behind it, not in front
    } else {
        // Signed height of the camera and of the point over the boundary, negative on the murk side.
        float c0 = MurkSide * (0.0 - PlaneY);
        float c1 = MurkSide * (rel.y - PlaneY);
        if (c1 >= 0.0) {
            fog = 0.0; // both ends on the clear side
        } else {
            float t = c0 / (c0 - c1); // where the line of sight enters the murk
            float inMurk = (1.0 - t) * dist;
            // The rim fade scales the fog, not the path: past the rim the path under the surface is
            // hundreds of blocks, and half of that is still all the fog there is.
            vec3 entry = rel * t;
            float rim = 1.0 - smoothstep(PlaneFade.x * 0.9, PlaneFade.x, max(length(entry.xz), abs(entry.y)));
            fog = smoothstep(MurkRange.x, MurkRange.y, inMurk) * rim;
        }
    }
    // The surface's shadow: a pixel is dimmed by how deep under the surface it lies, so what the
    // murk covers is darker than what stands in the open, from either side alike, and the light
    // never jumps as the camera crosses. Not the sky, which is under nothing.
    float dim = 0.0;
    if (depth < 1.0) {
        dim = Darkness * smoothstep(0.0, DARKNESS_RAMP_BLOCKS, MurkSide * (PlaneY - rel.y));
        // Fade the shadow out with whatever fog has already taken the pixel. A shadow falls on the
        // world; once the fog has closed over a pixel there is no world left on it to shade, only
        // murk -- and murk casts none on itself.
        //
        // This matters where the pass adds no fog of its own because the vanilla fog uniforms are
        // doing it (inside the murk, no shader pack): there fog is 0 here, so nothing else scaled the
        // dim, and the far terrain -- already fogged to exactly the background colour, invisible --
        // came back darkened by it. Against the cleared sky behind, which is at depth 1 and takes no
        // dim at all, that drew the terrain's outline as a darker band: a silhouette along the horizon
        // where there is meant to be nothing but flat murk.
        //
        // The vanilla fog's own curve, reproduced: it is smoothstep(start, end, distance) over the
        // same MurkRange, and since FogModifier pins the shape to SPHERE its distance is length(rel),
        // which is dist. So this cancels the dim exactly where the fog reaches full.
        float already = (CameraInside > 0.5 && FogInside < 0.5)
            ? smoothstep(MurkRange.x, MurkRange.y, dist)
            : fog;
        dim *= 1.0 - already;
    }
    // One alpha blend does both the dim and the fog: the frame is to become
    // frame * (1 - dim) * (1 - fog) + murk * fog, and src * a + dst * (1 - a) is exactly that with
    // a = 1 - (1 - dim) * (1 - fog) and src = murk * fog / a (a >= fog, so src <= murk).
    float a = 1.0 - (1.0 - dim) * (1.0 - fog);
    if (a <= 0.0) {
        discard;
    }
    fragColor = vec4(MurkColor * (fog / a), a);
}
