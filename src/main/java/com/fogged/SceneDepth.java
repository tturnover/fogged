package com.fogged;

import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;

import net.minecraft.client.Minecraft;

// Per-frame snapshot of the scene depth buffer, taken BEFORE the fog plane draws so it holds only the
// terrain/blocks (not the plane or its vapour). The plane and vapour shaders sample it to soft-fade
// their alpha as they approach occluding geometry, so a block/shore silhouette reads as a gradient
// instead of a hard depth cut.
//
// A separate copy is required because the plane WRITES depth: sampling the live depth attachment while
// it is also the render target's depth buffer is a read/write feedback loop (undefined in GL). Blitting
// the depth into our own target once per frame sidesteps that, and reusing the same terrain-only
// snapshot for the later vapour pass keeps the vapour from fading against the plane it sits on.
public final class SceneDepth {

    private static TextureTarget copy;
    private static int w;
    private static int h;

    private SceneDepth() {
    }

    // Copy the current main depth buffer into our own target. Must be called on the render thread, after
    // the terrain passes and BEFORE the plane is drawn.
    public static void capture() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int mw = main.width;
        int mh = main.height;
        if (copy == null || mw != w || mh != h) {
            if (copy != null) {
                copy.destroyBuffers();
            }
            copy = new TextureTarget(mw, mh, true, false);
            copy.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            w = mw;
            h = mh;
        }
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, copy.frameBufferId);
        GL30.glBlitFramebuffer(0, 0, mw, mh, 0, 0, mw, mh, GL30.GL_DEPTH_BUFFER_BIT, GL30.GL_NEAREST);
        main.bindWrite(false); // restore the main framebuffer for the upcoming draws
    }

    public static int depthTextureId() {
        return copy == null ? 0 : copy.getDepthTextureId();
    }
}
