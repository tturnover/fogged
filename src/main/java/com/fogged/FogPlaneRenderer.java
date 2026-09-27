package com.fogged;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// Renders a large flat quad at the breathing boundary, centred on the camera so it reads as an
// "infinite" separation surface. Foam comes from a world-space waterline map (WaterlineMap).
//
// Drawn after terrain, entities, block entities and Flywheel's instanced visuals, but BEFORE the
// translucent water pass. It writes depth, so the passes that follow are depth-tested against it:
// far-side water below is culled by it, while near-side water still blends over it -- which is what
// keeps the plane visible through a lake. Everything already drawn is simply painted over where the
// plane is nearer -- terrain, mobs and machines on the far side all disappear behind the murk the
// same way. Water still reaches the depth snapshot the soft edge reads, as depth-only boxes for the
// streams crossing the boundary (waterDepthProxies), so a fall or a current dissolves the surface
// exactly like a block does.
//
// One depth-writing draw, in which everything the shader softens -- the occlusion dissolve, the
// dissolve holes, the near-camera dither, the far rim and camera-height falloff -- is a Bayer
// discard rather than alpha (see the shader for why): a pixel either survives, solid, and writes
// depth, or is dropped and writes nothing, so the plane never occludes what the player can see
// through it.
//
// Under an Iris shader pack none of this mod's shaders may draw (see IrisCompatibility), so the plane
// goes through the pack's own program instead (drawThroughPack): the same one depth-writing draw,
// its colour a baked foam map and its softening the same Bayer discard, by code spliced into that
// program.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogPlaneRenderer {

    // Period (blocks) of the world-space anchor tile used to keep shader noise/foam coords small and
    // precise far from spawn (see the WorldOffset note below). A power of two and a multiple of the
    // foam/water pixel grid, large enough that its seam is essentially never crossed in play. Must
    // match FogVapor#NOISE_ANCHOR so the two layers anchor identically.
    static final double NOISE_ANCHOR = 4096.0;

    // Entity dissolve discs: entities straddling / near the plane open a hitbox-sized hole in it (see the
    // fog_plane shader) so they poke through the depth-writing plane instead of being hard-cut at it.
    private static final int MAX_ENTITY_HOLES = 32;           // must match the shader's loop bound / array size
    private static final double ENTITY_HOLE_RANGE = 48.0;     // only entities within this horizontal dist get a hole
    private static final double ENTITY_HOLE_VERT = 1.0;       // vertical gap past the hitbox at which the hole closes
    private static final double ENTITY_HOLE_MARGIN = 0.4;     // extra radius past the hitbox so the disc clears the silhouette
    // Reused each frame to avoid per-frame allocation: 4 packed floats per disc (relX, relZ, radius, vgap).
    private static final float[] entityHoleBuf = new float[MAX_ENTITY_HOLES * 4];
    private static final double[] entityHoleDistSq = new double[MAX_ENTITY_HOLES];

    // Sable hulls get the same discs, laid along whatever part of the sub-level meets the plane: one
    // per HULL_HOLE_SPACING blocks of its footprint, each a little wider than the spacing so they
    // overlap into a continuous dissolve instead of a row of separate pits.
    private static final boolean SABLE = ModList.get().isLoaded("sable");
    private static final double HULL_HOLE_SPACING = 3.0;
    private static final double HULL_HOLE_RADIUS = 2.4;

    // Last frame's render state, for the debug HUD (FogDebugOverlay) to read back. Written only here.
    static double lastSurfaceY;
    static boolean lastBelow;
    static float lastFadeEnd;
    // Fog state seen at the plane draw, for the debug HUD.
    static float lastFogStart, lastFogEnd;
    static int lastFogShape;
    static final float[] lastFogColor = new float[4];
    // The colour this frame's framebuffer was cleared to, read back at renderSky (LevelRendererMixin)
    // while the GL clear value still holds it. Below the true horizon, where the plane's far reaches
    // are seen, nothing is drawn over the clear, so that is the backdrop the dry-side plane has to fog
    // into. It is not always the shader fog colour: IMB11's Fog writes the two from different places
    // (a clearColor wrap and shadowed fogRed/Green/Blue) and they disagree, so a plane fogged with the
    // scene colour stood against the backdrop as a hard straight cut. Vanilla terrain at full fog has
    // the same seam; the plane's dead-straight edge just made it read.
    public static final float[] frameClearColor = new float[4];
    public static boolean frameClearValid;
    static int lastHoles;
    static boolean lastDepthValid;
    static boolean lastDrawn;
    static boolean lastThroughPack;
    static int lastWaterBoxes;
    // Wall times of this stage's handler last frame, for the debug HUD: whole, and its parts.
    static double lastStageMs;
    static double lastMapMs;
    static double lastCaptureMs;
    static double lastProxyMs;
    static final GpuTimer gpu = new GpuTimer();

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Iris drives its shadow pass through the level renderer's own methods, not renderLevel, so
        // these stages should never fire inside it -- but a render-distance-wide opaque sheet in a
        // pack's shadow map blacks out the whole world under it, so make sure.
        if (IrisCompatibility.renderingShadowPass()) {
            return;
        }

        // Draw the plane before the translucent water pass and let it write depth, so it reads as a
        // solid murk barrier: real water on the far side of the plane (deep water below it when looking
        // down, the surface above it when submerged) is depth-culled and hidden, while near-side water
        // still blends over it. Terrain, drawn earlier, occludes the plane normally.
        //
        // AFTER_BLOCK_ENTITIES is the LAST stage before that water pass, and everything opaque that
        // can cross the boundary is already in the depth buffer by then: the entity batches, the
        // block-entity pass, and Flywheel's instanced visuals (it dispatches at the "blockentities"
        // profiler push, just ahead of vanilla's own block entities). Water is not; the streams that
        // matter are added to the snapshot below.
        //
        // The plane sat at AFTER_CUTOUT_BLOCKS for this reason: a Sable sub-level above the boundary
        // has to still render over the fog rather than be painted over by it. It is drawn well before
        // this stage, so its depth wins the test here; if a future Sable version moves its sub-level
        // pass past the block entities that would need revisiting.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        if (!Config.RENDER_PLANE.getAsBoolean() || !Config.dimensionEnabled(mc.level)) {
            lastDrawn = false;
            return;
        }
        lastDrawn = true;
        boolean hud = Config.DEBUG_HUD.getAsBoolean();
        long stageStart = System.nanoTime();
        if (hud) {
            gpu.begin();
        }
        boolean softEdges = Config.PLANE_SOFT_OCCLUSION.getAsBoolean();
        boolean pack = IrisCompatibility.shaderPackActive();
        lastThroughPack = pack;

        // Flush what the block-entity pass has only buffered so far. NeoForge fires this stage after
        // the solid and sheet batches are drawn but before the rest -- RenderType.cutout() among them,
        // which is what Create's kinetic renderers put their moving parts in -- and vanilla only
        // draws those just before the translucent terrain. Until they are drawn they are in neither
        // the depth buffer nor the snapshot taken below, so they were painted after the plane, over
        // it, crisp through every dithered hole. (Iris flushes these itself.)
        mc.renderBuffers().bufferSource().endBatch();

        Vec3 cam = event.getCamera().getPosition();
        double surfaceY = Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET;
        // Camera-relative so the pose matrix maps straight to clip space.
        float relY = (float) (surfaceY - cam.y);
        // On the fogged side of the boundary the camera is in the thick murk fog; the plane (and its
        // foam) must be obscured once it is out of that fog's reach. flipFog swaps which side that is.
        boolean below = (cam.y < surfaceY) != Config.FLIP_FOG.getAsBoolean();
        lastSurfaceY = surfaceY;
        lastBelow = below;

        // Keep the world-space waterline foam map up to date around the camera. Its radius follows the
        // render distance so the foam covers the part of the plane that is actually visible.
        int mapBlocks = mc.options.getEffectiveRenderDistance() * 16;
        // The pack path draws the foam as a texture, so it asks the map for its baked colour copy too.
        long t0 = System.nanoTime();
        WaterlineMap.update(mc.level, cam, Mth.floor(surfaceY), mapBlocks, pack);
        lastMapMs = (System.nanoTime() - t0) / 1.0e6;

        // Snapshot the scene depth BEFORE drawing the plane, so the plane and the vapour can soft-fade
        // against occluding geometry (the plane writes depth, so sampling the live depth buffer would
        // be a read/write feedback loop). Water is drawn only after the plane, so on its own the
        // snapshot would never hold it and a fall or a current would be cut hard at the surface: the
        // streams crossing the boundary are drawn into the snapshot afterwards, depth only, as boxes.
        // Skipped when soft occlusion is disabled by config, where capturing it buys nothing this frame.
        //
        // Under a shader pack the snapshot is taken by a fixed-function copy (SceneDepth) and read by
        // the code spliced into the pack's program, where it comes out as a dither around every edge.
        lastCaptureMs = 0.0;
        lastProxyMs = 0.0;
        if (pack) {
            SceneDepth.OPAQUE.capture(); // for MurkComposite (see SceneDepth.OPAQUE)
        }
        if (softEdges) {
            t0 = System.nanoTime();
            SceneDepth.SCENE.capture();
            long t1 = System.nanoTime();
            waterDepthProxies(mc, event.getModelViewMatrix(), cam, Mth.floor(surfaceY));
            lastCaptureMs = (t1 - t0) / 1.0e6;
            lastProxyMs = (System.nanoTime() - t1) / 1.0e6;
        }
        // Degrades to "never occlude" in the shader (see fogged_softOcclusion) rather than sampling
        // stale/garbage depth when occlusion is off or the capture above failed (see SceneDepth).
        float depthValid = softEdges && SceneDepth.SCENE.depthAvailable() ? 1.0F : 0.0F;
        lastDepthValid = depthValid > 0.0F;

        // Per-entity dissolve discs so crossing mobs and machines poke through instead of being
        // hard-cut. They ride the same switch as the depth-buffer fade (planeSoftOcclusion): both are
        // the same idea -- soften what the boundary cuts -- and off means a hard cut for everything,
        // with the per-frame entity scan here skipped as well.
        int holes = softEdges ? gatherEntityHoles(mc.level, cam, surfaceY) : 0;
        lastHoles = holes;

        // Quad half-size, and how the rim goes. In the murk the plane spans the render distance and
        // fades out over the last fifth of what can be seen (visibleReach), so it never shows past
        // where the world fades away. On the dry side the rim fade begins only once the last terrain
        // is gone -- past both the render distance and the scene fog's end -- so it never dithers
        // over terrain that is still crisp; by then the plane is fully fogged into the backdrop
        // (frameClearColor) and the dither only finishes what the fog began. It ends well inside the
        // projection's far plane, or the clip there would leave a straight cut instead of a fade, and
        // the quad runs out to the fade's end (its corners further, already gone).
        float reach = visibleReach(mc, event.getCamera(), below);
        lastFogStart = RenderSystem.getShaderFogStart();
        lastFogEnd = RenderSystem.getShaderFogEnd();
        lastFogShape = RenderSystem.getShaderFogShape().getIndex();
        System.arraycopy(RenderSystem.getShaderFogColor(), 0, lastFogColor, 0, 4);
        float s;
        float fadeStart;
        float fadeEnd;
        if (below) {
            s = mc.options.getEffectiveRenderDistance() * 16.0F + 32.0F;
            fadeStart = reach * 0.8F;
            fadeEnd = reach;
        } else {
            float depthFar = mc.gameRenderer.getDepthFar();
            fadeStart = Math.min(Math.max(reach, lastFogEnd), depthFar * 0.7F);
            fadeEnd = Math.min(fadeStart * 1.125F, depthFar);
            s = fadeEnd;
        }
        lastFadeEnd = fadeEnd;

        float[] plane = Config.planeColor();
        float r = plane[0];
        float g = plane[1];
        float b = plane[2];
        // Config alpha (plane[3]) is ignored: the plane is drawn opaque both sides so it always reads
        // as murk (a low config alpha made it look like clear glass up close).

        if (pack) {
            drawThroughPack(mc, event.getModelViewMatrix(), cam, relY, below, s, fadeStart, fadeEnd, holes, depthValid, r, g, b);
            lastStageMs = (System.nanoTime() - stageStart) / 1.0e6;
            if (hud) {
                gpu.end();
            }
            return;
        }

        // The shader anchors foam in world space via worldXZ = Position.xz + WorldOffset, so the
        // Position attribute must stay raw camera-relative coords with the camera rotation living in
        // ModelViewMat. The PoseStack's top matrix is only the camera view at some stages;
        // getModelViewMatrix() is the camera view at every stage. Push it onto RenderSystem's
        // modelview and emit the quad untransformed.
        Matrix4f view = event.getModelViewMatrix();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); // terrain occludes the plane
        RenderSystem.depthMask(true);   // opaque murk: write depth so far-side water is culled by it
        RenderSystem.disableCull();     // visible from both sides

        // Sampler0 = waterline map (foam rings). Sampler3 = scene depth snapshot for soft edges.
        RenderSystem.setShaderTexture(0, WaterlineMap.textureId());
        RenderSystem.setShaderTexture(3, SceneDepth.SCENE.depthTextureId());

        // Our shader once loaded; fall back to the plain one otherwise.
        ShaderInstance shader = FogShaders.FOG_PLANE;
        if (shader != null) {
            RenderSystem.setShader(() -> shader);
            // Anchor world-space coords to a large tile near the camera (see NOISE_ANCHOR): the shader
            // floors worldXZ onto a pixel grid, and full world coords far from spawn lose float32
            // precision, so the snapped noise/foam boils and flickers as the camera moves sub-block.
            // Both WorldOffset and WaterlineOrigin shift by the same anchor, so foam UVs stay exact.
            // Round (not floor) so the anchor tiles are centred on the origin: their seams fall at
            // +/-NOISE_ANCHOR/2, NOT at 0. A floor here put a seam exactly on x=0 / z=0, so crossing
            // spawn flipped the anchor and made the snapped noise jump. Rounding keeps the whole
            // -2048..2048 spawn region on one tile.
            double ax = Math.rint(cam.x / NOISE_ANCHOR) * NOISE_ANCHOR;
            double az = Math.rint(cam.z / NOISE_ANCHOR) * NOISE_ANCHOR;
            shader.safeGetUniform("WorldOffset").set((float) (cam.x - ax), (float) cam.y, (float) (cam.z - az));
            shader.safeGetUniform("FoamWidth").set((float) (double) Config.FOAM_WIDTH.get());
            shader.safeGetUniform("DebugView").set(Config.DEBUG_VIEW.get().ordinal());
            // The baked noise fields, for the surface spots (see NoiseField).
            RenderSystem.setShaderTexture(1, NoiseField.textureId());
            setNoiseUniforms(shader, ax, az);
            float[] foam = Config.foamColor();
            shader.safeGetUniform("FoamColor").set(foam[0], foam[1], foam[2], foam[3]);
            // Where the waterline map sits in the world, and how its stored distance is scaled.
            shader.safeGetUniform("WaterlineOrigin").set((float) (WaterlineMap.originX() - ax), (float) (WaterlineMap.originZ() - az));
            shader.safeGetUniform("WaterlineSize").set((float) WaterlineMap.size());
            shader.safeGetUniform("WaterlineMaxDist").set(WaterlineMap.MAX_DIST);
            // Foam/spot pixel-snap grid must match the map's actual resolution (Config.waterlineCellsPerBlock).
            shader.safeGetUniform("FoamPixelsPerBlock").set((float) WaterlineMap.cellsPerBlock());
            shader.safeGetUniform("PlaneFadeStart").set(fadeStart);
            shader.safeGetUniform("PlaneFadeEnd").set(fadeEnd);
            // Framebuffer size so the shader maps gl_FragCoord into the scene-depth snapshot.
            shader.safeGetUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            shader.safeGetUniform("DepthValid").set(depthValid);
            // Dissolve holes only on the fogged side: from the dry side they'd be a clear window down
            // through the murk. On the fogged side the revealed content is hidden by the murk fog.
            shader.safeGetUniform("HolesActive").set(below ? 1.0F : 0.0F);
            shader.safeGetUniform("FoamFog").set(below ? 0.0F : 1.0F);
            shader.safeGetUniform("EntityHoleCount").set(holes);
            shader.safeGetUniform("EntityHoles").set(entityHoleBuf);
            // Near-camera dither (see the shader), as the pack path gets it too.
            float[] nd = nearDither();
            shader.safeGetUniform("NearDither").set(nd[0], nd[1], nd[2], nd[3]);
            shader.safeGetUniform("DitherPixelSize").set((float) Config.DITHER_PIXEL_SIZE.getAsInt());
            shader.safeGetUniform("StepDither").set(Config.STEP_DITHER.getAsBoolean() ? 1.0F : 0.0F);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        }

        // Only force our green murk fog when actually IN the murk (below the plane): there it reaches
        // full within a few blocks so the ceiling reads as murk. Above the plane -- dry OR underwater --
        // we leave the vanilla scene fog, so the plane dissolves into whatever the world fades to (the
        // horizon/world-edge fog when dry, the water fog when submerged) instead of tinting the far view
        // its own colour or cutting a hard edge at the loaded-chunk boundary.
        float far = Config.FOG_DISTANCE.getAsInt();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] savedFogColor = RenderSystem.getShaderFogColor();
        if (below) {
            RenderSystem.setShaderFogStart(0.0F);
            RenderSystem.setShaderFogEnd(far * 0.15F);
            RenderSystem.setShaderFogColor(r, g, b, 1.0F); // plane colour, like the fog beneath it
        } else if (frameClearValid) {
            // The backdrop's colour, not the scene fog's -- see frameClearColor.
            RenderSystem.setShaderFogColor(frameClearColor[0], frameClearColor[1], frameClearColor[2], 1.0F);
        }

        // Put the camera view into RenderSystem's modelview for the draw (its leftover state varies by
        // stage), then emit raw camera-relative vertices so the shader keeps Position world-anchored.
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(view);
        RenderSystem.applyModelViewMatrix();

        drawQuad(s, relY, r, g, b);

        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);
        RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);

        // Cold-vapour mist sheets are drawn separately in FogVapor, AFTER the translucent water pass,
        // so the mist veils over water instead of water tracing dark outlines over it.

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        lastStageMs = (System.nanoTime() - stageStart) / 1.0e6;
        if (hud) {
            gpu.end();
        }
    }

    // One camera-centred quad at the boundary height, opaque (see the note on config alpha above).
    private static void drawQuad(float s, float relY, float r, float g, float b) {
        float va = 1.0F;
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(-s, relY, -s).setColor(r, g, b, va);
        bb.addVertex(-s, relY, s).setColor(r, g, b, va);
        bb.addVertex(s, relY, s).setColor(r, g, b, va);
        bb.addVertex(s, relY, -s).setColor(r, g, b, va);
        BufferUploader.drawWithShader(bb.buildOrThrow());
    }

    // Add the water crossing the boundary to the depth snapshot: every column the waterline map found
    // crossing liquid in at the boundary row (a stream, or standing water open to the air -- see
    // WaterlineMap), within reach of the camera, as one depth-only box per liquid block a few rows
    // either side of the surface -- the rows the soft edge can see. Drawn
    // straight into the snapshot's target, depth-tested against what it already holds, so the live
    // depth buffer is never touched and the plane and the real water pass sort exactly as before.
    //
    // Re-rendering the whole translucent layer for this was tried and was far too dear at a large
    // render distance; a few dozen boxes cost nothing. Each box's top is the fluid surface as the
    // liquid renderer draws it -- the four corner heights averaged from the neighbours, so a spreading
    // current is a ramp and not a stair -- and a fall is the full block. Anything else the soft edge
    // reads as a step the water does not have, and lets the murk show at the mismatch.
    private static final int WATER_PROXY_RANGE = 48;  // blocks from the camera, horizontally
    private static final int WATER_PROXY_ROWS = 4;    // rows either side of the boundary row

    private static void waterDepthProxies(Minecraft mc, Matrix4f view, Vec3 cam, int boundaryY) {
        int count = WaterlineMap.flowingColumnCount();
        if (count == 0 || !SceneDepth.SCENE.beginOverlay()) {
            return;
        }
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int boxes = 0;
        for (int i = 0; i < count; i++) {
            int bx = WaterlineMap.flowingColumnX(i);
            int bz = WaterlineMap.flowingColumnZ(i);
            if (Math.abs(bx + 0.5 - cam.x) > WATER_PROXY_RANGE || Math.abs(bz + 0.5 - cam.z) > WATER_PROXY_RANGE) {
                continue;
            }
            for (int by = boundaryY - WATER_PROXY_ROWS; by <= boundaryY + WATER_PROXY_ROWS; by++) {
                pos.set(bx, by, bz);
                FluidState fluid = mc.level.getFluidState(pos);
                if (fluid.isEmpty()) {
                    continue;
                }
                float x0 = (float) (bx - cam.x);
                float y0 = (float) (by - cam.y);
                float z0 = (float) (bz - cam.z);
                fluidCorners(mc.level, pos, fluid.getType(), corners);
                box(bb, x0, y0, z0, x0 + 1.0F, z0 + 1.0F, corners);
                boxes++;
            }
        }
        lastWaterBoxes = boxes;
        if (boxes > 0) {
            Matrix4fStack mvStack = RenderSystem.getModelViewStack();
            mvStack.pushMatrix();
            mvStack.set(view);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.disableCull();
            RenderSystem.setShader(GameRenderer::getPositionShader);
            // Vanilla's position shader; under a pack Iris hands out its own replacement, whose apply()
            // binds the pack's framebuffer, so the draw is rebound to the snapshot in between.
            IrisCompatibility.drawInto(bb.buildOrThrow(), RenderSystem.getShader(), SceneDepth.SCENE.frameBufferId());
            RenderSystem.enableCull();
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
        } else {
            bb.build(); // nothing emitted: release the builder
        }
        SceneDepth.SCENE.endOverlay();
    }

    // Scratch for fluidCorners: the surface height at the block's NW, SW, SE and NE corners.
    private static final float[] corners = new float[4];

    // A fluid block's four corner heights as LiquidBlockRenderer computes them (its getHeight /
    // calculateAverageHeight, corner order as its top-face vertices): a block with the same fluid
    // above is full; otherwise each corner averages the own height with the two edge neighbours and
    // the diagonal, weighting anything at 0.8 or above ten times, so a current ramps down smoothly.
    private static void fluidCorners(ClientLevel level, BlockPos pos, Fluid fluid, float[] out) {
        float own = fluidHeight(level, pos, fluid);
        if (own >= 1.0F) {
            java.util.Arrays.fill(out, 1.0F);
            return;
        }
        float n = fluidHeight(level, pos.north(), fluid);
        float s = fluidHeight(level, pos.south(), fluid);
        float e = fluidHeight(level, pos.east(), fluid);
        float w = fluidHeight(level, pos.west(), fluid);
        out[0] = cornerHeight(level, fluid, own, n, w, pos.north().west()); // NW (x0, z0)
        out[1] = cornerHeight(level, fluid, own, s, w, pos.south().west()); // SW (x0, z1)
        out[2] = cornerHeight(level, fluid, own, s, e, pos.south().east()); // SE (x1, z1)
        out[3] = cornerHeight(level, fluid, own, n, e, pos.north().east()); // NE (x1, z0)
    }

    // LiquidBlockRenderer#getHeight: 1 under more of the same fluid, the fluid's own height in it,
    // 0 in air and anything else non-solid, -1 (ignored) in a solid.
    private static float fluidHeight(ClientLevel level, BlockPos pos, Fluid fluid) {
        var state = level.getBlockState(pos);
        var fluidState = state.getFluidState();
        if (fluid.isSame(fluidState.getType())) {
            return fluid.isSame(level.getBlockState(pos.above()).getFluidState().getType()) ? 1.0F : fluidState.getOwnHeight();
        }
        return state.isSolid() ? -1.0F : 0.0F;
    }

    // LiquidBlockRenderer#calculateAverageHeight with addWeightedHeight inlined.
    private static float cornerHeight(ClientLevel level, Fluid fluid, float own, float edge1, float edge2, BlockPos diagonal) {
        if (edge1 >= 1.0F || edge2 >= 1.0F) {
            return 1.0F;
        }
        cornerSum = 0.0F;
        cornerWeight = 0.0F;
        if (edge1 > 0.0F || edge2 > 0.0F) {
            float d = fluidHeight(level, diagonal, fluid);
            if (d >= 1.0F) {
                return 1.0F;
            }
            weigh(d);
        }
        weigh(own);
        weigh(edge2);
        weigh(edge1);
        return cornerSum / cornerWeight;
    }

    private static float cornerSum;
    private static float cornerWeight;

    private static void weigh(float h) {
        if (h >= 0.8F) {
            cornerSum += h * 10.0F;
            cornerWeight += 10.0F;
        } else if (h >= 0.0F) {
            cornerSum += h;
            cornerWeight += 1.0F;
        }
    }

    // A fluid block as depth-only geometry: flat bottom, the top through the four corner heights (a
    // bilinear quad, as the liquid renderer draws it), sides up to the corners. Culling is off, so
    // winding is free.
    private static void box(BufferBuilder bb, float x0, float y0, float z0, float x1, float z1, float[] c) {
        float nw = y0 + c[0];
        float sw = y0 + c[1];
        float se = y0 + c[2];
        float ne = y0 + c[3];
        bb.addVertex(x0, y0, z0); bb.addVertex(x1, y0, z0); bb.addVertex(x1, y0, z1); bb.addVertex(x0, y0, z1); // bottom
        bb.addVertex(x0, nw, z0); bb.addVertex(x0, sw, z1); bb.addVertex(x1, se, z1); bb.addVertex(x1, ne, z0); // top
        bb.addVertex(x0, y0, z0); bb.addVertex(x0, nw, z0); bb.addVertex(x1, ne, z0); bb.addVertex(x1, y0, z0); // north
        bb.addVertex(x0, y0, z1); bb.addVertex(x1, y0, z1); bb.addVertex(x1, se, z1); bb.addVertex(x0, sw, z1); // south
        bb.addVertex(x0, y0, z0); bb.addVertex(x0, y0, z1); bb.addVertex(x0, sw, z1); bb.addVertex(x0, nw, z0); // west
        bb.addVertex(x1, y0, z0); bb.addVertex(x1, ne, z0); bb.addVertex(x1, se, z1); bb.addVertex(x1, y0, z1); // east
    }

    // Hand a core shader the baked noise fields (see NoiseField): where the texture sits, anchored as
    // the shader's worldXZ is, and the clock it was baked at for the shader's fallback to match.
    static void setNoiseUniforms(ShaderInstance shader, double ax, double az) {
        boolean valid = NoiseField.valid();
        shader.safeGetUniform("NoiseValid").set(valid ? 1.0F : 0.0F);
        shader.safeGetUniform("NoiseOrigin").set((float) (NoiseField.originX() - ax), (float) (NoiseField.originZ() - az));
        shader.safeGetUniform("NoiseCells").set((float) NoiseField.cells());
        shader.safeGetUniform("NoiseCellsPerBlock").set((float) NoiseField.cellsPerBlock());
        shader.safeGetUniform("NoiseTime").set(valid ? NoiseField.time() : FogShaders.animTimeSeconds());
        shader.safeGetUniform("WispScale").set(FogVapor.WISP_SCALE);
    }

    // The near-camera dither's shader parameters: start, end, min visibility, enabled. The end is kept
    // past the start so the smoothstep between them is well defined.
    static float[] nearDither() {
        float start = (float) (double) Config.NEAR_DITHER_START.get();
        float end = Math.max((float) (double) Config.NEAR_DITHER_END.get(), start + 0.01F);
        return new float[] { start, end, (float) (double) Config.NEAR_DITHER_MIN_VISIBILITY.get(),
                Config.PLANE_NEAR_DITHER.getAsBoolean() ? 1.0F : 0.0F };
    }

    // The plane under an Iris shader pack. Vanilla's position_tex_color shader is Iris' replacement
    // for it, the pack's gbuffers_textured program, so the draw is lit, shadowed and fogged by the
    // pack like any block -- and that program carries this mod's shaping code (IrisShaderPatcher),
    // switched on for this draw alone and fed here (IrisCompatibility#draw). All the shading the pack's
    // program can be handed is texture times vertex colour, so the foam arrives baked into the
    // texture (WaterlineMap's foam colour map); the rim fade, the entity discs and the near dither
    // arrive as uniforms and come out as one Bayer discard. One pass, depth writes on: a discard is
    // all-or-nothing per pixel, so nothing see-through ever writes depth and the two-pass split the
    // core shader needs does not arise. The soft occlusion reads this mod's depth snapshot from a
    // texture unit of its own. Only the surface spots stay out: they are colour, which the pack owns.
    private static void drawThroughPack(Minecraft mc, Matrix4f view, Vec3 cam, float relY, boolean below,
            float s, float fadeStart, float fadeEnd, int holes, float depthValid, float r, float g, float b) {
        // Bail before any render state is touched: the plane IS its colour map, and painting a
        // render-distance-wide quad with texture 0 would black out the world.
        int foamColorTex = WaterlineMap.foamColorTextureId();
        if (foamColorTex == 0) {
            return;
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, foamColorTex);
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);

        // The murk fog on the fogged side, as the core path sets it; whether the pack's fog uniforms
        // follow is the pack's business, but a pack that reads vanilla's fog gets the same answer.
        float far = Config.FOG_DISTANCE.getAsInt();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] savedFogColor = RenderSystem.getShaderFogColor();
        if (below) {
            RenderSystem.setShaderFogStart(0.0F);
            RenderSystem.setShaderFogEnd(far * 0.15F);
            RenderSystem.setShaderFogColor(r, g, b, 1.0F);
        }

        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(view);
        RenderSystem.applyModelViewMatrix();

        // One quad for the whole plane, its UVs running off the foam map's footprint and out past
        // [0,1] wherever the plane reaches further. The map's texture is CLAMP_TO_EDGE (see
        // WaterlineMap.newFoamColorTexture), so everything beyond the footprint samples the same border
        // texels a pinned UV would have -- "far water", i.e. bare plane colour. Vertex colour stays
        // white: all colour is in the map.
        //
        // This was a 3x3 patch, on the grounds that one quad would put |u| in the tens of thousands and
        // lose the foam's alignment to float32 interpolation. It does not: u is (x - mx0) / mapSize with
        // x camera-relative, so at a 32-chunk render distance it spans -2.33 to 3.33, and the worst case
        // the clamps allow (a 48-block map) reaches 11.8 -- where float32 resolves about 1.4e-6 against
        // a texel of 1/192, some three thousand times finer than the foam needs.
        //
        // What the patch did cost was three different UV gradients on one surface: both coordinates
        // moving across the centre quad, one across each edge quad, neither across the corners. A pack
        // that reads anything off the albedo's screen-space derivatives -- Complementary derives normals
        // that way -- shades those three regimes differently, and the plane came out with a plus and
        // four corners drawn on it in a 3x3 around the player. One quad has one gradient everywhere.
        float mapSize = Math.max(1.0F, (float) WaterlineMap.size());
        float mx0 = (float) (WaterlineMap.originX() - cam.x);
        float mz0 = (float) (WaterlineMap.originZ() - cam.z);
        float u0 = (-s - mx0) / mapSize;
        float u1 = (s - mx0) / mapSize;
        float v0 = (-s - mz0) / mapSize;
        float v1 = (s - mz0) / mapSize;
        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(-s, relY, -s).setUv(u0, v0).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        bb.addVertex(-s, relY, s).setUv(u0, v1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        bb.addVertex(s, relY, s).setUv(u1, v1).setColor(1.0F, 1.0F, 1.0F, 1.0F);
        bb.addVertex(s, relY, -s).setUv(u1, v0).setColor(1.0F, 1.0F, 1.0F, 1.0F);

        float[] nd = nearDither();
        float pixelSize = Config.DITHER_PIXEL_SIZE.getAsInt();
        // World anchoring as the core path does it (see NOISE_ANCHOR there), for the waterline lookup.
        double ax = Math.rint(cam.x / NOISE_ANCHOR) * NOISE_ANCHOR;
        double az = Math.rint(cam.z / NOISE_ANCHOR) * NOISE_ANCHOR;
        IrisCompatibility.draw(bb.buildOrThrow(), RenderSystem.getShader(), true, program -> {
            IrisCompatibility.uniform1f(program, "fogged_PixelSize", pixelSize);
            IrisCompatibility.uniform1i(program, "fogged_DebugView", Config.DEBUG_VIEW.get().ordinal());
            IrisCompatibility.uniform3f(program, "fogged_WorldOffset", (float) (cam.x - ax), (float) cam.y, (float) (cam.z - az));
            IrisCompatibility.sampler(program, "fogged_Waterline", WaterlineMap.textureId(), 1);
            IrisCompatibility.uniform4f(program, "fogged_WaterlineInfo", (float) (WaterlineMap.originX() - ax),
                    (float) (WaterlineMap.originZ() - az), (float) WaterlineMap.size(), WaterlineMap.MAX_DIST);
            IrisCompatibility.uniform2f(program, "fogged_Fade", fadeStart, fadeEnd);
            IrisCompatibility.uniform4f(program, "fogged_NearDither", nd[0], nd[1], nd[2], nd[3]);
            IrisCompatibility.uniform1f(program, "fogged_HolesActive", below ? 1.0F : 0.0F);
            IrisCompatibility.uniform1i(program, "fogged_HoleCount", holes);
            IrisCompatibility.uniform4fv(program, "fogged_Holes", entityHoleBuf);
            IrisCompatibility.uniform1f(program, "fogged_DepthValid", depthValid);
            IrisCompatibility.sampler(program, "fogged_SceneDepth", SceneDepth.SCENE.depthTextureId(), 0);
        });

        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);
        RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    // Distance at which the world fades away on the camera's side, i.e. how far the plane (and the
    // vapour above it) may reach before it must be gone. Below the boundary that wall is the murk fog;
    // above it the render distance -- except with the camera in a fluid, where the vanilla scene fog
    // ends the view much sooner (and closer still in the seconds after diving, while water vision ramps
    // up). Without that clamp a plane far below the camera stayed a hard, depth-writing sheet across
    // water the eye reads as empty: submerging over sections that were still rebuilding painted the
    // whole gap murk, with a straight horizon cut where the plane ran out.
    static float visibleReach(Minecraft mc, Camera camera, boolean below) {
        float reach = below ? Config.FOG_DISTANCE.getAsInt() : mc.options.getEffectiveRenderDistance() * 16.0F;
        if (camera.getFluidInCamera() != FogType.NONE) {
            reach = Math.min(reach, RenderSystem.getShaderFogEnd());
        }
        // Hard ceiling: the projection's own far plane. GameRenderer.getDepthFar() is
        // renderDistance * 4 -- 768 blocks at 12 chunks -- and geometry past it is clipped away
        // entirely, so a reach beyond this does not draw more plane, it just moves the fade out to
        // where the quad has already been cut off and leaves a hard ring at the far plane instead of a
        // fade. Mods that stretch the view (Distant Horizons draws its LODs with its own extended
        // projection) do not move ours, so this is the real limit rather than a guess.
        return Math.min(reach, mc.gameRenderer.getDepthFar());
    }

    // Fill entityHoleBuf with up to MAX_ENTITY_HOLES dissolve discs for entities near the plane, packed as
    // (camera-relative X, camera-relative Z, horizontal radius, vertical gap). An entity qualifies when its
    // hitbox is within ENTITY_HOLE_VERT of the plane vertically and ENTITY_HOLE_RANGE of the camera
    // horizontally. On overflow the farthest disc is dropped so the nearest ones survive. Returns the count.
    //
    // Queried via an AABB bounded to ENTITY_HOLE_RANGE/ENTITY_HOLE_VERT (mirroring WaterlineMap's entity
    // query) rather than level.entitiesForRendering(), which scans every rendering entity in the whole
    // loaded world every frame regardless of how far it is from the boundary.
    //
    // The camera's own entity is excluded. A disc centred on the player is a hole that follows them
    // around -- the surface opening up wherever they stand, and closing behind them -- which is the one
    // shape this is not for. Everything else crossing the boundary still gets one.
    private static int gatherEntityHoles(ClientLevel level, Vec3 cam, double surfaceY) {
        int count = 0;
        final double rangeSq = ENTITY_HOLE_RANGE * ENTITY_HOLE_RANGE;
        double vertMargin = ENTITY_HOLE_VERT + 4.0; // generous margin for tall mobs/boats straddling the plane
        AABB area = new AABB(cam.x - ENTITY_HOLE_RANGE, surfaceY - vertMargin, cam.z - ENTITY_HOLE_RANGE,
                cam.x + ENTITY_HOLE_RANGE, surfaceY + vertMargin, cam.z + ENTITY_HOLE_RANGE);
        for (Entity e : level.getEntities(Minecraft.getInstance().getCameraEntity(), area, e -> true)) {
            AABB b = e.getBoundingBox();
            // Vertical gap from the plane to the entity's box (0 while it straddles); skip once it clears.
            double vgap = Math.max(0.0, Math.max(surfaceY - b.maxY, b.minY - surfaceY));
            if (vgap > ENTITY_HOLE_VERT) {
                continue;
            }
            double dx = (b.minX + b.maxX) * 0.5 - cam.x;
            double dz = (b.minZ + b.maxZ) * 0.5 - cam.z;
            double dSq = dx * dx + dz * dz;
            if (dSq > rangeSq) {
                continue;
            }
            double radius = 0.5 * Math.max(b.maxX - b.minX, b.maxZ - b.minZ) + ENTITY_HOLE_MARGIN;
            count = addHole(count, dx, dz, dSq, radius, vgap);
        }

        // Sable ships and contraptions are not entities, so the query above never sees them; their
        // discs are sampled off the hulls themselves (see SableCompatibility.sampleHoles). Added after
        // the entities so that, with more discs than slots, the nearest of the two sets survive
        // together rather than a big hull crowding every mob out.
        if (SABLE) {
            final int[] held = { count };
            SableCompatibility.sampleHoles(level, surfaceY, ENTITY_HOLE_VERT, HULL_HOLE_SPACING,
                    HULL_HOLE_RADIUS, cam.x, cam.z, ENTITY_HOLE_RANGE,
                    (worldX, worldZ, radius, gap) -> {
                        double hx = worldX - cam.x;
                        double hz = worldZ - cam.z;
                        double dSq = hx * hx + hz * hz;
                        if (dSq <= rangeSq) {
                            held[0] = addHole(held[0], hx, hz, dSq, radius, gap);
                        }
                    });
            count = held[0];
        }
        return count;
    }

    // Take one disc into the buffer: a free slot while there is one, else the farthest disc held, and
    // only if this one is nearer than it. Returns the new count.
    private static int addHole(int count, double dx, double dz, double dSq, double radius, double vgap) {
        int slot;
        if (count < MAX_ENTITY_HOLES) {
            slot = count++;
        } else {
            int farthest = 0;
            for (int i = 1; i < MAX_ENTITY_HOLES; i++) {
                if (entityHoleDistSq[i] > entityHoleDistSq[farthest]) {
                    farthest = i;
                }
            }
            if (dSq >= entityHoleDistSq[farthest]) {
                return count;
            }
            slot = farthest;
        }
        entityHoleDistSq[slot] = dSq;
        entityHoleBuf[slot * 4] = (float) dx;
        entityHoleBuf[slot * 4 + 1] = (float) dz;
        entityHoleBuf[slot * 4 + 2] = (float) radius;
        entityHoleBuf[slot * 4 + 3] = (float) vgap;
        return count;
    }
}
