#version 150

// The murk as seen from INSIDE a liquid.
//
// A liquid keeps its own fog (see FogModifier): while the eye is under water the scene is drawn with
// the water's fog and none of the murk's, which is right for the water itself and wrong for
// everything past its surface -- that stretch is still under the plane and has to read as murk.
// Fog uniforms cannot express that: they measure from the eye, and the eye is in the water.
//
// So the murk is integrated per pixel instead. Along each view ray the murk occupies the stretch that
// is (a) past where the ray leaves the liquid and (b) still on the murk side of the plane, cut short
// by whatever solid the ray hits first. The length of that stretch is what this pass turns into
// opacity, so water stays water, the world above the surface fogs out into murk, and a thin layer of
// murk over a lake stays thin instead of flipping the whole view.

uniform sampler2D Sampler0; // scene depth snapshot (see SceneDepth)

uniform mat4 InvViewProj;   // inverse of (projection * camera view): window depth -> camera-relative world
uniform vec2 ScreenSize;    // framebuffer size in pixels
uniform vec4 MurkColor;     // murk colour (rgb); alpha unused, opacity comes from the ray span below

uniform float FluidTopRel;  // height of the eye's liquid surface above the eye, in blocks (> 0)
uniform float MurkEndRel;   // height of the murk's own ceiling above the eye (the plane), in blocks
uniform float MurkStart;    // span (blocks) the murk stays clear over, matching the murk fog's near plane
uniform float MurkFull;     // span (blocks) at which the murk is opaque, matching the murk fog's far plane

out vec4 fragColor;

// Camera-relative world position of a window-space depth at this pixel; depth 1.0 gives the far plane,
// which is what the view ray's direction is read off. Same reconstruction the murk surface uses.
vec3 unproject(float depth) {
    vec2 ndc = gl_FragCoord.xy / ScreenSize * 2.0 - 1.0;
    vec4 p = InvViewProj * vec4(ndc, depth * 2.0 - 1.0, 1.0);
    return p.xyz / p.w;
}

void main() {
    vec3 dir = normalize(unproject(1.0));
    // A ray that never climbs never leaves the liquid: looking level or down there is only water
    // ahead, whatever is beyond it, so this pass has nothing to say about those pixels.
    if (dir.y <= 0.0001) {
        discard;
    }

    // Where the ray leaves the liquid, and where it leaves the murk out of the top.
    float exitT = FluidTopRel / dir.y;
    float ceilingT = MurkEndRel / dir.y;

    // Where the ray stops. An empty depth (1.0) is open sky: nothing stops it short of the ceiling.
    float depth = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r;
    float sceneT = depth >= 1.0 ? 1.0e9 : length(unproject(depth));

    float span = min(ceilingT, sceneT) - exitT;
    if (span <= 0.0) {
        discard; // the pixel is still inside the liquid, or its scene sits below the surface
    }

    float alpha = clamp((span - MurkStart) / max(MurkFull - MurkStart, 0.001), 0.0, 1.0);
    if (alpha <= 0.003) {
        discard;
    }
    fragColor = vec4(MurkColor.rgb, alpha);
}
