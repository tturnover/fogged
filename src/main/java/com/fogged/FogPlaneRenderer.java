package com.fogged;

import org.joml.Matrix4f;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// The murk surface at the breathing boundary. Geometry comes from FogPlaneMesh -- greedily merged
// quads covering only the block columns where the surface is free to exist, so a solid block or a
// liquid is a quad that was never emitted. Foam comes from a world-space waterline map
// (WaterlineMap), and the soft edge from a depth snapshot (SceneDepth) compared per pixel.
//
// This was briefly a screen-space composite that solved a ray/plane intersection per pixel. Correct,
// but it shaded the whole screen -- sky included -- and wrote gl_FragDepth, which disables early-Z
// for the entire pass. As geometry the rasteriser supplies depth, early-Z rejects occluded fragments
// before any of the foam or noise work runs, and only the surface's own pixels are shaded at all.
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

    // Blocks the mesh skirt grows by at a time (see the note where it is computed).
    private static final int SKIRT_STEP = 64;

    // Checked here, in base code, before anything reaches into DistantHorizonsCompatibility -- the same
    // guard WaterlineMap keeps for Sable. Compat classes are never on the required path.
    private static final boolean DISTANT_HORIZONS = ModList.get().isLoaded("distanthorizons");

    // ...and the integration is off unless asked for, on top of that.
    private static boolean dhWasOn;

    static boolean dhCompat() {
        return DISTANT_HORIZONS && Config.DH_COMPAT.getAsBoolean();
    }

    // Reused so the per-frame matrix maths allocates nothing.
    private static final Matrix4f modelView = new Matrix4f();
    private static final Matrix4f invViewProj = new Matrix4f();
    private static final Matrix4f farProjection = new Matrix4f();

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
        if (event.getStage() != murkStage()) {
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
        // Under the surface of a liquid the camera is in, the murk surface has nothing to be seen
        // through: it is cut out of liquids, and drawing it anyway laid a slab of murk across the lake
        // being swum in. SubmergedMurk paints the murk past that liquid's surface instead.
        if (SubmergedMurk.hidesSurface(event.getCamera(), mc.level, surfaceY)) {
            lastDrawn = false;
            return;
        }
        // On the fogged side of the boundary the camera is in the thick murk fog; the surface (and its
        // foam) must be obscured once it is out of that fog's reach. flipFog swaps which side that is.
        boolean below = (cam.y < surfaceY) != Config.FLIP_FOG.getAsBoolean();
        lastSurfaceY = surfaceY;
        lastBelow = below;

        // Distant Horizons suppresses vanilla fog by default, which takes the murk with it and leaves
        // the world under the plane clear out to the LOD horizon. Hand it the murk to draw instead.
        boolean dh = dhCompat();
        if (dh) {
            DistantHorizonsCompatibility.applyMurkFog(below, Config.FOG_DISTANCE.getAsInt());
        } else if (dhWasOn) {
            // Just switched off: hand DH's fog settings back before we stop touching it, or they stay
            // pinned to the murk for the rest of the session.
            DistantHorizonsCompatibility.applyMurkFog(false, 0.0F);
        }
        dhWasOn = dh;

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

        // The mesh is in map-local block coords at y = 0, so the draw matrix is the camera view plus a
        // translation to the map's world corner and the boundary height. Keeping that out of the
        // vertices is what lets the mesh survive camera movement and a drifting boundary untouched.
        float offX = (float) (WaterlineMap.originX() - cam.x);
        float offY = (float) (surfaceY - cam.y);
        float offZ = (float) (WaterlineMap.originZ() - cam.z);
        modelView.set(event.getModelViewMatrix()).translate(offX, offY, offZ);

        // Inverse of (projection * camera view) -- WITHOUT the mesh translation, since it turns a
        // sampled scene depth back into a camera-relative position, not a mesh-local one.
        invViewProj.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        if (debug) {
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
        } else {
            RenderSystem.enableDepthTest(); // near geometry rejects the murk behind it
            RenderSystem.depthMask(true);   // dithered-away pixels write nothing, so this is safe
        }
        RenderSystem.disableCull(); // the surface is seen from both sides

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
        shader.safeGetUniform("MeshOffset").set(offX, offY, offZ);
        // Inside the murk the ceiling keeps no distance fade, so it cannot open holes onto the world
        // above -- see the FoggedSide branch in the shader.
        shader.safeGetUniform("FoggedSide").set(below ? 1.0F : 0.0F);
        // Mesh vertices are map-local, so world XZ is reached from the map's own origin.
        shader.safeGetUniform("WorldOffsetXZ").set((float) (WaterlineMap.originX() - ax),
                (float) (WaterlineMap.originZ() - az));
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
        shader.safeGetUniform("FoamPixelsPerBlock").set((float) WaterlineMap.effectPixelsPerBlock());
        shader.safeGetUniform("MapPixelsPerBlock").set((float) WaterlineMap.cellsPerBlock());
        // Fade out by ray distance so the murk never shows past where the world fades away.
        float fadeEnd = visibleReach(mc, below);
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

        // The mesh only reaches as far as the waterline map; past that there is no block data, so a
        // skirt carries the surface out to wherever the fade ends (see FogPlaneMesh). Quantised,
        // because the fade end moves continuously while the camera is in a fluid and a skirt that
        // tracked it exactly would rebuild the mesh every single frame.
        int skirt = Mth.ceil(Math.max(0.0F, fadeEnd - WaterlineMap.size() / 2.0F) / SKIRT_STEP) * SKIRT_STEP;
        FogPlaneMesh.ensureBuilt(mc.level, Mth.floor(surfaceY), skirt);
        boolean wireframe = Config.DEBUG_VIEW.get() == Config.DebugView.WIREFRAME;
        if (wireframe) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_LINE);
        }

        // Everything past the projection's far plane, drawn first and drawn differently. Distant
        // Horizons keeps putting LOD terrain out there, so the murk has to as well, but vanilla's
        // matrix clips at getDepthFar() and its depth buffer has no resolution to spare that far out.
        // So this stretch gets a projection with the far plane pushed back, and no depth test at all:
        // the shader's own occlusion term already compares against the depth SNAPSHOT, which was taken
        // with the original matrix and is unaffected, and it hard-gates to nothing wherever the scene
        // is in front. Drawn before the near mesh so the depth-tested half wins any overlap.
        float lods = dh ? DistantHorizonsCompatibility.renderDistanceBlocks() : 0.0F;
        if (lods > fadeEnd + SKIRT_STEP) {
            // Where LOD terrain rises above the boundary the haze must not exist at all; the grid
            // answers that from DH's own terrain data (see FarPlaneGrid), a few samples a tick.
            if (Config.DH_LOD_CUT.getAsBoolean()) {
                FarPlaneGrid.update(cam, lods, surfaceY);
            }
            extendFarPlane(event.getProjectionMatrix(), farProjection, lods * 1.5F);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            // The haze starts where the near surface stops being drawn and thins out to the LOD edge,
            // so the two meet without a seam instead of the flat slab the far skirt used to be.
            shader.safeGetUniform("FarPass").set(1.0F);
            shader.safeGetUniform("PlaneFadeStart").set(fadeEnd * 0.5F);
            shader.safeGetUniform("PlaneFadeEnd").set(lods);
            // The grid covers the near region too, so the haze runs under the near mesh rather than
            // butting against it: the near mesh is drawn second and depth-tested, so it wins wherever
            // both are visible, and there is no band left uncovered where the near mesh gets clipped.
            FogPlaneMesh.drawFar(modelView, farProjection, shader);
            // ...and back to the near pass's own state and fade range.
            shader.safeGetUniform("FarPass").set(0.0F);
            shader.safeGetUniform("PlaneFadeStart").set(fadeEnd * 0.8F);
            shader.safeGetUniform("PlaneFadeEnd").set(fadeEnd);
            if (!debug) {
                RenderSystem.enableDepthTest();
                RenderSystem.depthMask(true);
            }
        }

        FogPlaneMesh.draw(modelView, event.getProjectionMatrix(), shader);
        if (wireframe) {
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
        }

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

    // The stage the murk actually draws at: the configured one, pushed later when Distant Horizons
    // needs it to be.
    //
    // DH renders its LODs from the HEAD of LevelRenderer.renderSectionLayer, and that method is called
    // for the tripwire layer too -- after NeoForge has already fired AFTER_TRANSLUCENT_BLOCKS. So a
    // murk drawn at the default stage is painted over by LOD terrain: from below it shows through the
    // ceiling, and from above the surface appears to stop dead exactly where the LODs begin. Anything
    // from AFTER_PARTICLES on is past every renderSectionLayer call and safe.
    //
    // Only bumped when DH is actually drawing, and never pulled earlier -- a stage the user chose past
    // this point is left alone.
    static RenderLevelStageEvent.Stage murkStage() {
        RenderLevelStageEvent.Stage configured = stage(Config.PLANE_STAGE.get());
        if (!dhCompat() || DistantHorizonsCompatibility.renderDistanceBlocks() <= 0) {
            return configured;
        }
        if (configured == RenderLevelStageEvent.Stage.AFTER_PARTICLES
                || configured == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return configured;
        }
        return RenderLevelStageEvent.Stage.AFTER_WEATHER;
    }

    // A copy of {@code src} with its far plane moved out to {@code newFar}, everything else untouched.
    //
    // Solved out of the matrix rather than rebuilt from FOV and aspect, so it cannot drift from whatever
    // projection the game actually handed us. For a standard perspective matrix m22 = -(f+n)/(f-n) and
    // m32 = -2fn/(f-n), which invert to n = m32/(m22-1) and f = m32/(m22+1); only those two entries
    // change, so the horizontal and vertical scale -- and therefore every pixel's view ray -- is
    // identical to the near pass's.
    private static void extendFarPlane(Matrix4f src, Matrix4f dst, float newFar) {
        dst.set(src);
        float a = src.m22();
        float b = src.m32();
        float near = b / (a - 1.0F);
        if (!Float.isFinite(near) || near <= 0.0F || newFar <= near) {
            return; // not a perspective matrix we recognise; leave it exactly as it came
        }
        dst.m22(-(newFar + near) / (newFar - near));
        dst.m32(-2.0F * newFar * near / (newFar - near));
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
    static RenderLevelStageEvent.Stage stage(Config.PlaneStage which) {
        return switch (which) {
            case AFTER_CUTOUT_BLOCKS -> RenderLevelStageEvent.Stage.AFTER_CUTOUT_BLOCKS;
            case AFTER_ENTITIES -> RenderLevelStageEvent.Stage.AFTER_ENTITIES;
            case AFTER_TRANSLUCENT_BLOCKS -> RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
            case AFTER_PARTICLES -> RenderLevelStageEvent.Stage.AFTER_PARTICLES;
            case AFTER_WEATHER -> RenderLevelStageEvent.Stage.AFTER_WEATHER;
            default -> RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES;
        };
    }

    // Distance at which the murk (and the vapour above it) must be gone: the murk fog's own reach below
    // the boundary, the render distance above it, stretched by Distant Horizons.
    //
    // Deliberately NOT clamped to the current scene fog. Fogging out is not the same as not being
    // drawn -- terrain is still drawn all the way to the render distance and merely fogged, and the
    // murk takes the same fog uniforms, so it disappears into the distance on its own. Clamping its
    // geometry to the fog end instead made it fade EARLIER than the terrain it lies over, which is
    // exactly what any mod that shortens the view (IMB11's Fog, a biome pack, a resource pack) causes.
    static float visibleReach(Minecraft mc, boolean below) {
        float reach = below ? Config.FOG_DISTANCE.getAsInt() : mc.options.getEffectiveRenderDistance() * 16.0F;

        // Distant Horizons draws LOD terrain well past the vanilla render distance, so the murk has to
        // reach that far or its far rim becomes a hard ring with the world beyond the boundary on open
        // show past it. This applies on BOTH sides: from underneath the murk is a ceiling, and one that
        // stops at fogDistance while DH keeps drawing sky and LODs past it is the same ring seen from
        // below. The murk fog still hides everything out there -- the surface just has to be there to
        // be hidden, rather than absent.
        float lods = dhCompat() ? DistantHorizonsCompatibility.renderDistanceBlocks() : 0.0F;
        reach = Math.max(reach, lods);

        // Hard ceiling: the projection's own far plane. GameRenderer.getDepthFar() is
        // renderDistance * 4 -- 768 blocks at 12 chunks -- and geometry past it is clipped away
        // entirely, so a reach beyond this does not draw more murk, it just moves the fade out to
        // where the mesh has already been cut off and leaves a hard ring at the far plane instead of
        // a fade. This is why the murk cannot follow Distant Horizons all the way out: DH draws its
        // LODs with its own extended projection, and ours is still vanilla's.
        return Math.min(reach, mc.gameRenderer.getDepthFar());
    }
}
