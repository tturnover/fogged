package com.fogged;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// The murk laid over the finished frame as a screen-space pass: one full-screen quad that reads the
// scene depth back and fogs every pixel by how much of the line of sight to it runs through the murk
// (see murk_composite.fsh). Two jobs, one pass:
//
// From the clear side, the world seen down through an opening in the surface -- the near-camera
// dither, an entity's dissolve disc -- is inside the murk and has to look it, and only a pass that
// knows where each pixel sits can fog it: the fog uniforms describe the camera's surroundings and
// nothing more, so with the camera in clear air the sea floor showed through such a hole as sharp as
// day. Here it fogs by the depth of murk between the surface and it.
//
// Under an Iris shader pack this is the whole of the murk fog, from either side. A pack computes its
// own fog from its own settings and never reads the vanilla fog uniforms FogModifier takes over, so
// below the boundary the world stayed clear to the horizon under Complementary and the like. It runs
// from the tail of Iris' finalizeLevelRendering (IrisPipelineMixin), the first point at which this
// mod's own shaders draw again -- Iris stops overriding them once its world render is finalised, and
// NeoForge's AFTER_LEVEL stage fires before that. Without a pack it runs at AFTER_LEVEL; inside the
// murk the fog uniforms already fog everything, sky included, so there it only lays the surface's
// shadow (murkDarkness): every pixel under the boundary is dimmed by how deep under it it lies, from
// either side, which no fog can do.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class MurkComposite {

    private MurkComposite() {
    }

    // Whether this frame's depth was taken before the particles were drawn (see below). Cleared by the
    // pass that consumes it, so a frame that never reaches that stage falls back to taking it itself.
    private static boolean depthTakenEarly;

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Take the depth BEFORE the particles. Every particle sheet draws with depth writes on, even
        // the see-through ones, so a particle in front of the murk leaves its own distance in the depth
        // buffer -- and this pass, which fogs a pixel by how much murk the line of sight to it crosses,
        // then measures to the particle rather than to the world behind it and lays next to no murk
        // there. A puff of spray over the surface punched a clear hole through the murk, and one at the
        // surface's own height was taken for the surface itself and cleared outright.
        //
        // AFTER_TRIPWIRE_BLOCKS is the last stage before AFTER_PARTICLES, and everything this pass has
        // to measure -- terrain, entities, the plane, water -- is drawn by then. It is the same one
        // capture as before, only taken a stage earlier, so it costs nothing extra.
        //
        // Not under a shader pack: there this pass runs from Iris' own finalize hook, long after these
        // stages, against whatever depth the pack has ended up with -- so the early copy would be of
        // the wrong buffer. The pack path keeps taking its own, as it always has.
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRIPWIRE_BLOCKS
                && !IrisCompatibility.shaderPackActive()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && Config.dimensionEnabled(mc.level)) {
                SceneDepth.SCENE.capture();
                depthTakenEarly = SceneDepth.SCENE.depthAvailable();
            }
            return;
        }
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || IrisCompatibility.shaderPackActive()) {
            return;
        }
        render(false);
    }

    /** Called from Iris' finalize hook while a pack is active. */
    public static void renderUnderPack() {
        render(true);
    }

    static double lastMs; // wall time of the last pass, for the debug HUD
    static final GpuTimer gpu = new GpuTimer();

    private static void render(boolean pack) {
        boolean hud = Config.DEBUG_HUD.getAsBoolean();
        long start = System.nanoTime();
        if (hud) {
            gpu.begin();
        }
        renderPass(pack);
        if (hud) {
            gpu.end();
        }
        lastMs = (System.nanoTime() - start) / 1.0e6;
    }

    private static void renderPass(boolean pack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.dimensionEnabled(mc.level)) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        boolean inside = Config.fogged(mc.level, camera.getPosition().y);
        // The dim is the surface's shadow on the world beneath it, pixel by pixel in the shader, from
        // either side; the value here is only how deep it gets.
        float darkness = (float) (double) Config.MURK_DARKNESS.get();
        // Without a pack the vanilla fog uniforms fog the murk's inside; this pass then only dims it.
        boolean fogInside = pack;
        if (inside && !pack && darkness <= 0.0F) {
            return;
        }
        ShaderInstance shader = FogShaders.MURK_COMPOSITE;
        if (shader == null) {
            return;
        }
        // Read the depth from a copy, never from the main target's own attachment: main is what this
        // draws into, and sampling a texture attached to the framebuffer being drawn is undefined even
        // with depth writes off -- on radeonsi it came back as flickering tile-shaped garbage, worse
        // after every window resize. The SCENE snapshot is done with by now (the plane and vapour read
        // it earlier in the frame), so it is retaken for this pass -- before the particles where the
        // stage for that fired, and here otherwise, which is where it was always taken.
        if (!depthTakenEarly) {
            SceneDepth.SCENE.capture();
        }
        depthTakenEarly = false;
        if (!SceneDepth.SCENE.depthAvailable()) {
            return;
        }
        int depth = SceneDepth.SCENE.depthTextureId();
        // Under a pack, the pre-water copy from the plane's stage (see SceneDepth.OPAQUE).
        boolean opaqueValid = pack && FogPlaneRenderer.lastDrawn && SceneDepth.OPAQUE.depthAvailable()
                && SceneDepth.OPAQUE.depthTextureId() != 0;

        // The projection still on RenderSystem here is the world's -- GameRenderer swaps it for the
        // hand's only after the level render returns -- so its inverse unprojects the depth correctly.
        // The view is the camera's rotation: LevelRenderer builds it from the conjugate, so the
        // rotation itself is the inverse.
        // Under a pack, the matrices Iris captured for it: exactly what its depth was written through.
        Matrix4f invProj = (pack ? IrisCompatibility.gbufferProjection() : new Matrix4f(RenderSystem.getProjectionMatrix())).invert();
        Matrix4f invView = pack ? IrisCompatibility.gbufferModelView().invert() : new Matrix4f().rotation(camera.rotation());
        float relY = (float) (Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET - camera.getPosition().y);
        float[] c = Config.planeColor();
        // Any debug view paints this pass magenta, so what it touches can be told from the pack's own fog.
        if (Config.DEBUG_VIEW.get() != Config.DebugView.OFF) {
            c = new float[] { 1.0F, 0.0F, 1.0F, 1.0F };
        }
        float far = Config.FOG_DISTANCE.getAsInt();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, depth);
        RenderSystem.setShaderTexture(1, opaqueValid ? SceneDepth.OPAQUE.depthTextureId() : depth);
        RenderSystem.setShader(() -> shader);
        shader.safeGetUniform("OpaqueValid").set(opaqueValid ? 1.0F : 0.0F);
        shader.safeGetUniform("InvProjMat").set(invProj);
        shader.safeGetUniform("InvViewMat").set(invView);
        shader.safeGetUniform("MurkColor").set(c[0], c[1], c[2]);
        shader.safeGetUniform("MurkRange").set(far * 0.25F, far); // as FogModifier sets the vanilla fog
        shader.safeGetUniform("PlaneY").set(relY);
        shader.safeGetUniform("MurkSide").set(Config.FLIP_FOG.getAsBoolean() ? -1.0F : 1.0F);
        shader.safeGetUniform("CameraInside").set(inside ? 1.0F : 0.0F);
        shader.safeGetUniform("FogInside").set(fogInside ? 1.0F : 0.0F);
        shader.safeGetUniform("Darkness").set(darkness);
        // The plane's own tighter fog from inside (see the shader); without a pack the plane fogs itself.
        shader.safeGetUniform("PlaneFogEnd").set(pack && FogPlaneRenderer.lastDrawn ? far * 0.15F : 0.0F);
        // As far as the surface itself reaches from this side (see the shader).
        float reach = FogPlaneRenderer.visibleReach(mc, camera, inside);
        shader.safeGetUniform("PlaneFade").set(reach * 0.8F, reach);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bb.addVertex(-1.0F, -1.0F, 0.0F);
        bb.addVertex(1.0F, -1.0F, 0.0F);
        bb.addVertex(1.0F, 1.0F, 0.0F);
        bb.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
