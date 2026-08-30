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
import net.minecraft.util.Mth;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// The murk surface at the breathing boundary, drawn as a SCREEN-SPACE COMPOSITE: a full-screen quad
// whose fragment shader rebuilds each pixel's view ray, intersects the horizontal boundary plane
// analytically, and blends murk over the pixel when that intersection is nearer than the scene depth
// already in the buffer. Foam comes from a world-space waterline map (WaterlineMap).
//
// There is no plane geometry. Occlusion is one per-pixel comparison of two ray lengths, so anything
// the scene rendered occludes the murk correctly and dissolves into it over OCCLUSION_FADE blocks --
// terrain, mobs, block entities and Flywheel's instanced Create parts alike. The quad this used to
// be needed a camera-sized extent, a rim fade derived from that extent, a separate camera-height
// fade, backface culling off, and a dissolve-hole system with a 128-float uniform array to punch
// entity-shaped gaps in the hard depth cut it produced. None of that exists any more.
//
// Which render stage it runs at is config (planeStage), defaulting to AFTER_TRANSLUCENT_BLOCKS --
// see the stage() method for what each choice puts in the buffers first. Render order decides what
// the murk can cover and what covers it, so it is worth being able to move without a rebuild.
//
// Softness -- the occlusion edge and the distance fades -- is a screen-door DITHER, not an alpha
// ramp: a stable 4x4 checker of kept and dropped pixels, matching the chunky pixelated look of the
// foam and surface spots. It also keeps the whole surface to one draw, because a discarded fragment
// writes no depth: the murk ends up writing depth exactly where it is solid, which an alpha fade
// could only manage by splitting into a depth-writing pass and a blended one.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogPlaneRenderer {

    // Period (blocks) of the world-space anchor tile used to keep shader noise/foam coords small and
    // precise far from spawn (see the WorldOffsetXZ note below). A power of two and a multiple of the
    // foam/water pixel grid, large enough that its seam is essentially never crossed in play. Must
    // match FogVapor#NOISE_ANCHOR so the two layers anchor identically.
    static final double NOISE_ANCHOR = 4096.0;

    // Reused so the per-frame matrix maths allocates nothing.
    private static final Matrix4f viewProj = new Matrix4f();
    private static final Matrix4f invViewProj = new Matrix4f();

    // Last frame's render state, for the debug HUD (FogDebugOverlay) to read back. Written only here.
    static double lastSurfaceY;
    static boolean lastBelow;
    static float lastFadeEnd;
    static boolean lastDepthValid;
    static boolean lastDrawn;

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (!Config.RENDER_PLANE.getAsBoolean()) {
            lastDrawn = false;
            return;
        }
        if (event.getStage() != stage(Config.PLANE_STAGE.get())) {
            return;
        }
        ShaderInstance shader = FogShaders.FOG_PLANE;
        if (shader == null) {
            lastDrawn = false;
            return; // shaders not loaded yet; a ray-marched surface has no plain-shader fallback
        }
        lastDrawn = true;

        Vec3 cam = event.getCamera().getPosition();
        double surfaceY = Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET;
        // On the fogged side of the boundary the camera is in the thick murk fog; the surface (and its
        // foam) must be obscured once it is out of that fog's reach. flipFog swaps which side that is.
        boolean below = (cam.y < surfaceY) != Config.FLIP_FOG.getAsBoolean();
        lastSurfaceY = surfaceY;
        lastBelow = below;

        // Keep the world-space waterline foam map up to date around the camera. Its radius follows the
        // render distance so the foam covers the part of the surface that is actually visible.
        int mapBlocks = mc.options.getEffectiveRenderDistance() * 16;
        WaterlineMap.update(mc.level, cam, Mth.floor(surfaceY), mapBlocks);

        // Snapshot the scene depth BEFORE compositing, because this pass writes depth and sampling the
        // live buffer while writing it is a read/write feedback loop. Reused by FogVapor. Skipped when
        // soft occlusion is off -- the only case where capturing it buys nothing this frame.
        boolean wantSoftOcclusion = Config.PLANE_SOFT_OCCLUSION.getAsBoolean();
        if (wantSoftOcclusion) {
            SceneDepth.capture();
        }
        // Degrades to "never occlude" in the shader rather than sampling stale/garbage depth when
        // occlusion is off or the capture above failed (see SceneDepth).
        float depthValid = wantSoftOcclusion && SceneDepth.depthAvailable() ? 1.0F : 0.0F;
        lastDepthValid = depthValid > 0.0F;

        // Debug views are diagnostics, not the surface: they draw with the depth test off so they show
        // the buffer they are visualising over the whole screen. Depth-tested, a debug fragment behind
        // a mob or a machine is discarded and you see that object drawn normally -- which looks exactly
        // like "it never made it into the snapshot" and makes SCENE_DEPTH unable to answer the one
        // question it exists for.
        boolean debug = Config.DEBUG_VIEW.get() != Config.DebugView.OFF;

        float[] plane = Config.planeColor();
        float r = plane[0];
        float g = plane[1];
        float b = plane[2];
        // Config alpha (plane[3]) is ignored: the murk is composited opaque from both sides so it
        // always reads as murk (a low config alpha made it look like clear glass up close).

        // Camera-relative world space <-> clip space. getModelViewMatrix() is the camera view at every
        // stage (rotation only, since the world is drawn camera-relative), so the product below maps a
        // point measured from the camera straight to clip space, and its inverse turns a pixel back
        // into a ray. Both go to the shader: one to build rays, one to project the hit back for depth.
        viewProj.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix());
        invViewProj.set(viewProj).invert();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (debug) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.enableDepthTest(); // near geometry rejects the murk behind it
            RenderSystem.depthMask(true);   // dithered-away pixels write nothing, so this is safe
        }
        // The clip-space quad below is wound back-facing under GL's default CCW-front convention, and
        // the pass before this leaves terrain's backface culling on, which would discard it whole.
        RenderSystem.disableCull();

        // Sampler0 = waterline map (foam rings). Sampler3 = scene depth snapshot for soft edges.
        RenderSystem.setShaderTexture(0, WaterlineMap.textureId());
        RenderSystem.setShaderTexture(3, SceneDepth.depthTextureId());
        RenderSystem.setShader(() -> shader);

        // Anchor world-space coords to a large tile near the camera (see NOISE_ANCHOR): the shader
        // floors worldXZ onto a pixel grid, and full world coords far from spawn lose float32
        // precision, so the snapped noise/foam boils and flickers as the camera moves sub-block.
        // Both WorldOffsetXZ and WaterlineOrigin shift by the same anchor, so foam UVs stay exact.
        // Round (not floor) so the anchor tiles are centred on the origin: their seams fall at
        // +/-NOISE_ANCHOR/2, NOT at 0. A floor here put a seam exactly on x=0 / z=0, so crossing
        // spawn flipped the anchor and made the snapped noise jump. Rounding keeps the whole
        // -2048..2048 spawn region on one tile.
        double ax = Math.rint(cam.x / NOISE_ANCHOR) * NOISE_ANCHOR;
        double az = Math.rint(cam.z / NOISE_ANCHOR) * NOISE_ANCHOR;

        shader.safeGetUniform("InvViewProj").set(invViewProj);
        shader.safeGetUniform("ViewProj").set(viewProj);
        shader.safeGetUniform("RelSurfaceY").set((float) (surfaceY - cam.y));
        // Inside the murk the ceiling keeps no distance fade, so it cannot open holes onto the world
        // above -- see the FoggedSide branch in the shader.
        shader.safeGetUniform("FoggedSide").set(below ? 1.0F : 0.0F);
        shader.safeGetUniform("WorldOffsetXZ").set((float) (cam.x - ax), (float) (cam.z - az));
        shader.safeGetUniform("PlaneColor").set(r, g, b, 1.0F);
        shader.safeGetUniform("Time").set(FogShaders.animTimeSeconds()); // monotonic boil clock
        shader.safeGetUniform("FoamWidth").set((float) (double) Config.FOAM_WIDTH.get());
        shader.safeGetUniform("DebugView").set(Config.DEBUG_VIEW.get().ordinal());
        float[] foam = Config.foamColor();
        shader.safeGetUniform("FoamColor").set(foam[0], foam[1], foam[2], foam[3]);
        // Where the waterline map sits in the world, and how its stored distance is scaled.
        shader.safeGetUniform("WaterlineOrigin").set((float) (WaterlineMap.originX() - ax), (float) (WaterlineMap.originZ() - az));
        shader.safeGetUniform("WaterlineSize").set((float) WaterlineMap.size());
        shader.safeGetUniform("WaterlineMaxDist").set(WaterlineMap.MAX_DIST);
        // Foam/spot pixel-snap grid must match the map's actual resolution (Config.waterlineCellsPerBlock).
        shader.safeGetUniform("FoamPixelsPerBlock").set((float) WaterlineMap.cellsPerBlock());
        // Fade out by ray distance so the murk never shows past where the world fades away.
        float fadeEnd = visibleReach(mc, event.getCamera(), below);
        lastFadeEnd = fadeEnd;
        shader.safeGetUniform("PlaneFadeStart").set(fadeEnd * 0.8F);
        shader.safeGetUniform("PlaneFadeEnd").set(fadeEnd);
        // Framebuffer size so the shader maps gl_FragCoord into the scene-depth snapshot.
        shader.safeGetUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
        shader.safeGetUniform("DepthValid").set(depthValid);

        // Only force our green murk fog when actually IN the murk (below the surface): there it reaches
        // full within a few blocks so the ceiling reads as murk. Above it -- dry OR underwater -- we
        // leave the vanilla scene fog, so the murk dissolves into whatever the world fades to (the
        // horizon/world-edge fog when dry, the water fog when submerged) instead of tinting the far
        // view its own colour or cutting a hard edge at the loaded-chunk boundary.
        float far = Config.FOG_DISTANCE.getAsInt();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        // getShaderFogColor hands back RenderSystem's live array, so copy it before overwriting it.
        float[] live = RenderSystem.getShaderFogColor();
        float[] savedFogColor = { live[0], live[1], live[2], live[3] };
        if (below) {
            RenderSystem.setShaderFogStart(0.0F);
            RenderSystem.setShaderFogEnd(far * 0.15F);
            RenderSystem.setShaderFogColor(r, g, b, 1.0F); // murk colour, like the fog beneath it
        }

        drawFullScreen();

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);
        RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);

        // Cold-vapour mist sheets are drawn separately in FogVapor, at the same stage but on a LOW
        // event priority so it always lands on top of the murk rather than under it.

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    // The stage the composite runs at (config planeStage), and what each one means for it.
    //
    //   AFTER_CUTOUT_BLOCKS       terrain only. Entities, block entities and Flywheel all draw AFTER
    //                             the murk, so they appear in front of it whatever side they are on.
    //   AFTER_ENTITIES            entities are in, block entities and Flywheel are not.
    //   AFTER_BLOCK_ENTITIES      entity batches, the block-entity pass and Flywheel (which
    //                             dispatches at the "blockentities" profiler push just ahead of
    //                             vanilla's own) are all in -- but not water.
    //   AFTER_TRANSLUCENT_BLOCKS  DEFAULT, and the first stage where everything the murk has to hide
    //                             is already in the buffers, water included. Found by testing: the
    //                             stages before this each left something drawing over the murk.
    //   AFTER_PARTICLES           as above, plus particles.
    //   AFTER_WEATHER             last stage in the level pass, nothing left to draw over it. This is
    //                             where RisingToxicity puts its own toxic plane.
    //
    // The last two run against a different framebuffer under Fabulous graphics (vanilla binds
    // translucentTarget / particlesTarget there); SceneDepth reads whichever is bound, so the depth
    // snapshot follows it, and the murk lands in that target rather than the main one.
    private static RenderLevelStageEvent.Stage stage(Config.PlaneStage which) {
        return switch (which) {
            case AFTER_CUTOUT_BLOCKS -> RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
            case AFTER_ENTITIES -> RenderLevelStageEvent.Stage.AFTER_ENTITIES;
            case AFTER_TRANSLUCENT_BLOCKS -> RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
            case AFTER_PARTICLES -> RenderLevelStageEvent.Stage.AFTER_PARTICLES;
            case AFTER_WEATHER -> RenderLevelStageEvent.Stage.AFTER_WEATHER;
            default -> RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        };
    }

    // Clip-space quad covering the screen; fog_plane.vsh passes it through untransformed.
    private static void drawFullScreen() {
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bb.addVertex(-1.0F, -1.0F, 0.0F);
        bb.addVertex(-1.0F, 1.0F, 0.0F);
        bb.addVertex(1.0F, 1.0F, 0.0F);
        bb.addVertex(1.0F, -1.0F, 0.0F);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    // Distance at which the world fades away on the camera's side, i.e. how far the murk (and the
    // vapour above it) may reach before it must be gone. Below the boundary that wall is the murk fog;
    // above it the render distance -- except with the camera in a fluid, where the vanilla scene fog
    // ends the view much sooner (and closer still in the seconds after diving, while water vision ramps
    // up). Without that clamp a surface far below the camera stayed a hard, depth-writing sheet across
    // water the eye reads as empty: submerging over sections that were still rebuilding painted the
    // whole gap murk, with a straight horizon cut where it ran out.
    static float visibleReach(Minecraft mc, Camera camera, boolean below) {
        float reach = below ? Config.FOG_DISTANCE.getAsInt() : mc.options.getEffectiveRenderDistance() * 16.0F;
        if (camera.getFluidInCamera() != FogType.NONE) {
            reach = Math.min(reach, RenderSystem.getShaderFogEnd());
        }
        return reach;
    }
}
