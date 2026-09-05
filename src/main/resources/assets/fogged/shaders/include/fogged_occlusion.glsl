// Shared soft-occlusion helper used by fog_plane.fsh and fog_vapor.fsh. Import this AFTER the includer
// has declared the uniforms it reads: Sampler3 (scene depth snapshot), ScreenSize, ProjMat, DepthValid.

const float FOGGED_OCCLUSION_FADE = 3.0; // blocks of depth over which an occluding edge softens

// Window depth -> positive camera distance, from the same ProjMat used to project the quad. Both the
// fragment and the sampled scene depth go through this, so their difference is a true world-space gap.
float fogged_viewDist(float d) {
    float ndc = d * 2.0 - 1.0;
    return ProjMat[3][2] / (ndc + ProjMat[2][2]);
}

// Soft occlusion: 1.0 in the open, fading to 0.0 as the surface closes on the occluding geometry in the
// pre-plane depth snapshot, so a block/shore silhouette is a gradient instead of a hard depth cut.
//
// DepthValid < 0.5 means either the depth snapshot itself is unavailable this frame (SceneDepth failed
// to capture -- see SceneDepth.java) or soft occlusion is disabled by config (planeSoftOcclusion) --
// either way, degrade to "never occlude" (render fully solid against terrain) instead of sampling
// garbage from an unbound/invalid texture.
float fogged_softOcclusion() {
    if (DepthValid < 0.5) {
        return 1.0;
    }
    float sceneD = texture(Sampler3, gl_FragCoord.xy / ScreenSize).r;
    if (sceneD >= 1.0) {
        return 1.0; // open sky behind: nothing to fade against
    }
    float gap = fogged_viewDist(sceneD) - fogged_viewDist(gl_FragCoord.z);
    return clamp(gap / FOGGED_OCCLUSION_FADE, 0.0, 1.0);
}
