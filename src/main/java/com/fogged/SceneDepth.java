package com.fogged;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;

// Per-frame snapshot of the scene depth buffer, taken BEFORE the fog plane draws so it holds the
// whole scene as it stands at that point -- terrain, entities, block entities and Flywheel's
// instanced visuals -- but not the plane or its vapour. The plane and vapour shaders sample it to
// soft-fade their alpha as they approach occluding geometry, so a block, shore, mob or machine
// silhouette reads as a gradient instead of a hard depth cut.
//
// The depth comes from whatever framebuffer is CURRENTLY BOUND FOR DRAWING, asked of GL directly,
// not from Minecraft.getMainRenderTarget(). Those are not always the same buffer: vanilla itself
// binds translucentTarget / particlesTarget (each with its own copied depth) under Fabulous
// graphics, and a renderer that redirects the pipeline can move it anywhere. Reading main's depth
// while the scene is being drawn somewhere else yields a snapshot of a different buffer -- which
// looks exactly like a snapshot that is missing everything drawn this frame.
//
// A separate copy is required because the plane WRITES depth: sampling the live depth attachment while
// it is also the render target's depth buffer is a read/write feedback loop (undefined in GL). The copy
// is produced by drawing a full-screen quad that samples the main target's depth texture and writes
// gl_FragDepth into our own target (depth_copy shader), rather than glBlitFramebuffer: a blit requires
// the source and destination depth formats to match bit-for-bit, which fails with GL_INVALID_OPERATION
// the moment any other installed mod reconfigures the main target's depth format (a different chunk-
// rendering backend, a shader-pack-style renderer, anything that touches Minecraft.getMainRenderTarget).
// A shader read is format-agnostic -- sampling a depth texture always yields a normalised [0,1] value
// regardless of its backing storage -- so this sidesteps that whole failure class instead of only
// working around one instance of it.
public final class SceneDepth {

    private static TextureTarget copy;
    private static int w;
    private static int h;

    // Whether the last capture actually produced usable depth data. FogPlaneRenderer/FogVapor mirror
    // this into a shader uniform so soft occlusion degrades to "never occlude" instead of sampling
    // garbage when it's false, rather than the mod rendering incorrectly or spamming GL errors forever.
    private static boolean depthAvailable = true;
    private static boolean loggedFailure = false;

    // Framebuffer the last capture read from (0 = the default framebuffer), so the capture can rebind
    // what it found rather than forcing main back into place.
    private static int sourceFbo;

    private SceneDepth() {
    }

    // Depth texture of the framebuffer currently bound for drawing, or 0 if it has none we can sample
    // (no depth attachment at all, or a renderbuffer -- renderbuffers cannot be bound as a texture).
    private static int boundDepthTexture() {
        sourceFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (sourceFbo == 0) {
            return 0; // the default framebuffer's depth is never a texture we can sample
        }
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        if (type != GL11.GL_TEXTURE) {
            return 0; // no depth attachment, or a renderbuffer (which cannot be bound as a texture)
        }
        return GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
    }

    public static boolean depthAvailable() {
        return depthAvailable;
    }

    // Copy the current main depth buffer into our own target. Must be called on the render thread, after
    // everything that can cross the boundary has drawn and BEFORE the plane itself does.
    public static void capture() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int mw = main.width;
        int mh = main.height;
        boolean resized = copy == null || mw != w || mh != h;
        if (resized) {
            if (copy != null) {
                copy.destroyBuffers();
            }
            copy = new TextureTarget(mw, mh, true, false);
            copy.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            w = mw;
            h = mh;
            depthAvailable = true; // retry after every resize, in case a prior failure was transient
        }

        // Prefer the bound target's own depth; fall back to main's only when it has none to sample.
        int depthTex = boundDepthTexture();
        if (depthTex == 0) {
            depthTex = main.getDepthTextureId();
        }
        if (depthTex == 0) {
            depthAvailable = false;
            logFailureOnce("no framebuffer in the pipeline exposes a sampleable depth texture");
            return;
        }

        ShaderInstance shader = FogShaders.DEPTH_COPY;
        if (shader == null) {
            depthAvailable = false; // shader not loaded/compiled yet; nothing to draw with this frame
            return;
        }

        copy.bindWrite(true);
        // Only depth matters downstream; never touch copy's color attachment.
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.depthFunc(GL11.GL_ALWAYS); // commit every fragment's depth regardless of the clear
        // The quad below is wound back-facing under GL's default CCW-front convention; without this,
        // terrain's leftover backface-cull state (still active from the pass just before this stage)
        // discards the whole draw and copy silently never receives any fragment writes at all.
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, depthTex);
        RenderSystem.setShader(() -> shader);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bb.addVertex(-1.0F, -1.0F, 0.0F);
        bb.addVertex(-1.0F, 1.0F, 0.0F);
        bb.addVertex(1.0F, 1.0F, 0.0F);
        bb.addVertex(1.0F, -1.0F, 0.0F);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL); // vanilla default
        RenderSystem.enableCull();
        // Rebind whatever was bound before this capture, not main: binding main unconditionally would
        // redirect the rest of the frame away from a target another renderer had put in place.
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, sourceFbo);
        GlStateManager._viewport(0, 0, mw, mh);

        // Defense-in-depth: check once per resize (not every frame -- an unconditional glGetError() call
        // forces a driver sync point, itself a frame-hitch risk) in case something about the copy target
        // or the sampled texture is unusable in a way this code doesn't otherwise detect.
        if (resized) {
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                depthAvailable = false;
                logFailureOnce("GL error " + err + " while copying scene depth");
            }
        }
    }

    public static int depthTextureId() {
        return copy == null ? 0 : copy.getDepthTextureId();
    }

    private static void logFailureOnce(String reason) {
        if (loggedFailure) {
            return;
        }
        loggedFailure = true;
        if (Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
            Fogged.LOGGER.warn("Fogged: scene-depth capture unavailable ({}) -- the fog plane/vapour will "
                    + "render without soft occlusion edges against terrain. This usually means another "
                    + "installed mod has changed how the main render target's depth buffer works.", reason);
        }
    }
}
