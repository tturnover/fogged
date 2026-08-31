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
uniform float MurkExtinction; // blocks of murk that hide 63% of what is behind them

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

    // ...except the liquid's own surface, which is geometry and writes depth like anything else. From
    // underneath, every ray that leaves the water "stops" on it, one span of zero later, and the whole
    // pass drew nothing at all. What those pixels actually show is the world above the surface, seen
    // through it -- so a hit at the exit point is the surface itself and is not what stops the ray.
    // Only a hit clear of it (a shore, a cliff, a boat hull) really does.
    // Scaled with distance: a grazing ray meets the surface hundreds of blocks out, where the depth
    // buffer's own precision is worth more than a block.
    float surfaceSlop = max(1.0, exitT * 0.02);
    if (abs(sceneT - exitT) < surfaceSlop) {
        sceneT = 1.0e9;
    }

    float span = min(ceilingT, sceneT) - exitT;
    if (span <= 0.0) {
        discard; // the pixel is still inside the liquid, or its scene sits below the surface
    }

    // Extinction, not a ramp with a clear stretch in front of it. The murk fog can afford a near
    // plane because it always starts at the eye; this span starts at the water's surface and is often
    // only a few blocks long, and a linear ramp from a near plane renders every one of those spans as
    // nothing at all -- which is what made a thin layer of murk over a lake invisible from below.
    // Deliberately NOT attenuated by how much liquid the ray passes through on the way out. That was
    // tried -- the murk is behind the liquid, so its own fog "should" hide it -- and it reads wrong:
    // the murk thinned out exactly where there is most of it to see, and the view from under a lake
    // went back to the clear daylight this pass exists to get rid of.
    float alpha = 1.0 - exp(-span / max(MurkExtinction, 0.001));
    if (alpha <= 0.003) {
        discard;
    }
    fragColor = vec4(MurkColor.rgb, alpha);
}
