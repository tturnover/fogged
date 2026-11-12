package com.fogged;

import java.nio.ByteBuffer;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiRenderingApi;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;

// The murk past the render distance, drawn from inside Distant Horizons' own render pass. Every DH type
// in the mod is confined to this class, which nothing touches unless DH is installed (see
// DistantHorizonsCompatibility).
//
// The plane is drawn at DhApiBeforeFogRenderEvent. By then DH has laid down its opaque terrain and its
// water and nothing else, with its framebuffer bound, so a quad at the boundary is depth-tested against
// the LODs by the same depth buffer they went into -- that test IS the cut at height, with no terrain
// query and no second projection to reconcile. Everything DH does to LODs afterwards happens to the
// plane as well, because to DH it is one more thing in the buffers: its fog reads the depth the plane
// wrote, its far fade and anti-aliasing take the colour, and the apply pass carries it to the screen.
//
// Depth here is DH's and not vanilla's. 3.3 draws in reverse Z: with no glClipControl, so the projection
// puts the far plane at NDC 0 and near at 1, which lands in the window at 0.5..1 with the buffer cleared
// to 0. Nothing in this pass assumes any of that. The matrices are DH's, the depth function is left as
// DH set it, and the shaders are told what "nothing drawn" reads as.
//
// In the murk there is nothing out there to see, so LODs are not drawn at all; see BeforeRender.
final class DhFarPass {

    // DH's API 7.1 is mod version 3.3.0, the first to say which way its depth runs. Older than that the
    // depth is read wrong, so the mod behaves as if DH were not here.
    private static final int MIN_API_MAJOR = 7;
    private static final int MIN_API_MINOR = 1;

    // How far past the render distance the quad stays solid before the far pass takes the surface. The
    // far pass's colour lands in the main target before vanilla draws, so vanilla terrain and water
    // are painted over it: anywhere they can still reach, the quad has to be there to hide them, or
    // its dithered rim becomes a ring of the world under the surface. Chunks stop a little past the
    // render distance, by the width of one at most.
    private static final float HANDOFF_MARGIN = 32.0F;

    // How far under the quad's edge the far pass starts. The two compute that radius from separately
    // interpolated positions, so meeting exactly would leave pixels that belong to neither.
    private static final float HANDOFF_OVERLAP = 1.0F;

    // DH's terrain shader starts LODs this far past its near clip plane, unless it is in LOD-only mode.
    private static final float LOD_CLIP_PADDING = 16.0F;
    // Taken off that, so the quad has handed over before the first LOD fragment can appear.
    private static final float LOD_START_MARGIN = 2.0F;

    // Our own copy of LOD depth, taken before the plane goes in: the plane writes this depth buffer, and
    // the shaders read it (see the class note in DistantHorizonsCompatibility). Same format DH allocates
    // (GlDhMetaRenderer), so it is a plain blit and keeps all of reverse Z's precision. SceneDepth's
    // copy goes through a shader for the sake of formats it cannot know, and lands in a fixed-point
    // buffer that would throw that precision away out here.
    private static int depthTex;
    private static int depthFbo;
    private static int depthW;
    private static int depthH;

    // Framebuffers of ours, only ever bound long enough to touch DH's textures from outside its pass.
    private static int clearFbo;
    private static int maskFbo;
    private static int maskReturnFbo;
    private static boolean lodsCleared;

    private static boolean failed;

    private DhFarPass() {
    }

    static void register() {
        if (!apiSupported()) {
            Fogged.LOGGER.warn("Fogged: Distant Horizons {} is too old for the murk to reach its LODs (it needs 3.3.0 "
                    + "or newer); the plane stops at the render distance", DhApi.getModVersion());
            return;
        }
        DhApi.events.bind(DhApiBeforeRenderEvent.class, new BeforeRender());
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new BeforeFog());
    }

    private static boolean apiSupported() {
        int major = DhApi.getApiMajorVersion();
        return major > MIN_API_MAJOR || (major == MIN_API_MAJOR && DhApi.getApiMinorVersion() >= MIN_API_MINOR);
    }

    /** Whether anything of ours belongs in DH's pass this frame. */
    private static boolean running(Minecraft mc) {
        return !failed && mc.level != null && Config.DISTANT_HORIZONS.getAsBoolean()
                && Config.dimensionEnabled(mc.level)
                // A pack replaces DH's framebuffer with its own gbuffers, and none of this mod's shaders
                // may draw there (see IrisCompatibility). DH stays as it would be without the mod.
                && !IrisCompatibility.shaderPackActive();
    }

    private static boolean inMurk(Minecraft mc, Camera camera) {
        return Config.fogged(mc.level, camera.getPosition().y);
    }

    private static void fail(String what, Throwable e) {
        failed = true;
        if (Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
            Fogged.LOGGER.warn("Fogged: could not {} Distant Horizons; the plane stops at the render distance", what, e);
        }
    }

    // In the murk the fog closes in long before the render distance, so every LOD on screen would be
    // hidden anyway, and all of them drawn first. Cancelling the pass is the whole of the fog there:
    // what is behind the world is the background FogModifier has already turned to the murk's colour.
    //
    // DH's vanilla-fade pass does not go through this event. It runs a little later in the frame and
    // reads what the terrain pass left in DH's textures, so those are cleared on the way in, or it
    // would keep fading the last LODs drawn over the terrain.
    //
    // This used to be done by overriding DH's fog config through the API, which needed to be applied
    // and released on every crossing and shows up in DH's screen as "set by API". An event is one
    // frame's decision, and has nothing to release.
    private static final class BeforeRender extends DhApiBeforeRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
            Minecraft mc = Minecraft.getInstance();
            try {
                if (!running(mc) || !inMurk(mc, mc.gameRenderer.getMainCamera())) {
                    lodsCleared = false;
                    return;
                }
                if (lodsCleared || clearLodTextures()) {
                    lodsCleared = true;
                    event.cancelEvent();
                }
            } catch (RuntimeException | LinkageError e) {
                fail("hide the LODs of", e);
            }
        }
    }

    private static final class BeforeFog extends DhApiBeforeFogRenderEvent {
        @Override
        public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
            Minecraft mc = Minecraft.getInstance();
            try {
                if (running(mc) && !inMurk(mc, mc.gameRenderer.getMainCamera())
                        && Config.RENDER_PLANE.getAsBoolean()
                        && DhApi.Delayed.renderProxy.getRenderingApi() == EDhApiRenderingApi.OPEN_GL) {
                    draw(mc, event.value.getRenderParam());
                }
            } catch (RuntimeException | LinkageError e) {
                fail("draw the plane into", e);
            }
        }
    }

    private static void draw(Minecraft mc, DhApiRenderParam param) {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        Integer chunks = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
        if (chunks == null || chunks <= 0) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        snapshotDepth(main.width, main.height);

        Camera camera = mc.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        Matrix4f view = joml(param.dhModelViewMatrix);
        Matrix4f proj = joml(param.dhProjectionMatrix);

        float reach = FogPlaneRenderer.visibleReach(mc, camera, false);
        float inner = reach + HANDOFF_MARGIN;
        float outer = chunks * 16.0F;
        float lodStart = param.nearClipPlane + LOD_CLIP_PADDING - LOD_START_MARGIN;
        DhApiResult<Integer> color = proxy.getDhColorTextureGlId();
        if (!color.success || color.payload == null || color.payload <= 0) {
            return;
        }
        DistantHorizonsCompatibility.publish(depthTex, color.payload, proxy.getDepthDirection().farDepth, lodStart, inner, new Matrix4f(proj).mul(view).invert());

        // Everything Blaze3D's draw path would otherwise assume about a pass it has not been told about.
        // DH keeps its own idea of this state between its renderers, so it goes back as it was found.
        // VAO first: BufferUploader remembers the last buffer it bound, and DH has bound its own since.
        int vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int blendSrc = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int blendDst = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        BufferUploader.invalidate();
        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(proj, VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(view);
        RenderSystem.applyModelViewMatrix();
        try {
            FogPlaneRenderer.drawFar(mc, cam, lodStart - HANDOFF_OVERLAP, outer);
            FogVapor.drawFar(mc, cam, lodStart, outer);
        } finally {
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, savedSorting);
            BufferUploader.invalidate();
            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(program);
            RenderSystem.depthFunc(depthFunc);
            RenderSystem.depthMask(true);
            GlStateManager._blendFuncSeparate(blendSrc, blendDst, blendSrcAlpha, blendDstAlpha);
            if (blend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            if (cull) {
                RenderSystem.enableCull();
            } else {
                RenderSystem.disableCull();
            }
            RenderSystem.activeTexture(GL30.GL_TEXTURE0);
        }
    }

    // DH's vanilla fade, at the head of the tripwire layer, blends whatever has vanilla depth towards
    // DH's colour at that pixel, by distance. The quad has vanilla depth, so without this DH takes it
    // for terrain and fades it into the LOD ocean underneath: a disc of it around the camera, once the
    // camera is high enough for the 3D distance to reach the fade. The fade leaves a pixel alone where
    // DH's alpha is 0, so that is what the quad stamps into DH's colour texture, tested against vanilla
    // depth so a hill in front of it still fades as DH means it to. It has to be now: DH's own passes
    // set that alpha to 1 on the way out, and the fade only reads it later.
    /** Aim the next draw at DH's colour alpha, under vanilla's depth. False when there is no such texture. */
    static boolean beginFadeMask(int mainDepthTexture) {
        DhApiResult<Integer> color = DhApi.Delayed.renderProxy.getDhColorTextureGlId();
        if (!color.success || color.payload == null || color.payload <= 0 || mainDepthTexture == 0) {
            return false;
        }
        maskReturnFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (maskFbo == 0) {
            maskFbo = GlStateManager.glGenFramebuffers();
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.payload, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, mainDepthTexture, 0);
        RenderSystem.disableBlend();
        RenderSystem.depthMask(false);
        RenderSystem.colorMask(false, false, false, true);
        return true;
    }

    static void endFadeMask() {
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        // Let go of vanilla's depth texture: it is reallocated on a resize, and this would hold it.
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, 0, 0);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, maskReturnFbo);
    }

    // DH's matrices are row-major fields; JOML's constructor takes columns.
    private static Matrix4f joml(DhApiMat4f m) {
        return new Matrix4f(
                m.m00, m.m10, m.m20, m.m30,
                m.m01, m.m11, m.m21, m.m31,
                m.m02, m.m12, m.m22, m.m32,
                m.m03, m.m13, m.m23, m.m33);
    }

    private static void snapshotDepth(int w, int h) {
        int source = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (depthTex == 0 || w != depthW || h != depthH) {
            allocateDepth(w, h);
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, source);
        }
        int read = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, source);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depthFbo);
        GL30.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, source);
    }

    private static void allocateDepth(int w, int h) {
        if (depthTex != 0) {
            GlStateManager._deleteTexture(depthTex);
            GlStateManager._glDeleteFramebuffers(depthFbo);
        }
        depthTex = GlStateManager._genTexture();
        GlStateManager._bindTexture(depthTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT32F, w, h, 0, GL11.GL_DEPTH_COMPONENT,
                GL11.GL_FLOAT, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL30.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL30.GL_CLAMP_TO_EDGE);
        depthFbo = GlStateManager.glGenFramebuffers();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, depthFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depthTex, 0);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);
        depthW = w;
        depthH = h;
    }

    // Returns false when DH has no textures yet, in which case there is nothing for the fade to read
    // either, and LODs are left to draw this frame rather than cancelled over a texture we never saw.
    private static boolean clearLodTextures() {
        IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
        DhApiResult<Integer> color = proxy.getDhColorTextureGlId();
        DhApiResult<Integer> depth = proxy.getDhDepthTextureGlId();
        if (!color.success || !depth.success || color.payload == null || depth.payload == null
                || color.payload <= 0 || depth.payload <= 0) {
            return false;
        }
        int bound = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        if (clearFbo == 0) {
            clearFbo = GlStateManager.glGenFramebuffers();
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, clearFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, color.payload, 0);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, depth.payload, 0);
        float[] murk = Config.planeColor();
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthMask(true);
        GL11.glClearColor(murk[0], murk[1], murk[2], 0.0F);
        GL11.glClearDepth(proxy.getDepthDirection().farDepth);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(1.0); // vanilla sets its clear colour before every clear, but not this
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, bound);
        return true;
    }
}
