#version 150

// Copies the main render target's depth into whatever target this shader is drawn into, by sampling
// (not blitting) it: a shader read only needs the source to be a readable depth texture and never
// requires the source/destination depth formats to match, unlike glBlitFramebuffer -- see SceneDepth.
//
// Named Sampler0 (not something more descriptive like DepthSampler) because ShaderInstance only
// auto-binds RenderSystem.setShaderTexture(N, ...) into a sampler literally named "Sampler" + N
// (see ShaderInstance.setDefaultUniforms) -- any other name never receives its texture and silently
// samples nothing.
uniform sampler2D Sampler0;

out vec4 fragColor;

void main() {
    // Exact texel fetch, matching the old blit's GL_NEAREST semantics (source and destination targets
    // are always the same resolution -- see SceneDepth.capture()).
    gl_FragDepth = texelFetch(Sampler0, ivec2(gl_FragCoord.xy), 0).r;
    fragColor = vec4(0.0); // unused: SceneDepth draws with colorMask disabled
}
