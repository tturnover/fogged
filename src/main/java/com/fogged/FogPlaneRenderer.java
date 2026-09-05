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
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// Renders a large flat quad at the breathing boundary, centred on the camera so it reads as an
// "infinite" separation surface. Foam comes from a world-space waterline map (WaterlineMap).
//
// Drawn after terrain, entities, block entities and Flywheel's instanced visuals, but BEFORE the
// translucent water pass. It writes depth, so the passes that follow are depth-tested against it:
// far-side water below is culled by it, while near-side water still sorts over it. Everything
// already drawn is simply painted over where the plane is nearer -- terrain, mobs and machines on
// the far side all disappear behind the murk the same way.
//
// The plane may only write depth where it is fully SOLID, so it is drawn twice (see drawQuad / the
// DepthPass uniform): the solid core with depth writes on, then everything the shader softens --
// the occlusion dissolve, the dissolve holes, the far rim and camera-height falloff -- with them
// off. A single depth-writing draw made the plane see-through while it still culled the translucent
// water pass and everything else drawn after it, and the vertical part of the fade did that to the
// entire plane at once as soon as the camera climbed above PlaneFadeStart.
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

    // Last frame's render state, for the debug HUD (FogDebugOverlay) to read back. Written only here.
    static double lastSurfaceY;
    static boolean lastBelow;
    static float lastFadeEnd;
    static int lastHoles;
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
        lastDrawn = true;

        // Draw the plane before the translucent water pass and let it write depth, so it reads as a
        // solid murk barrier: real water on the far side of the plane (deep water below it when looking
        // down, the surface above it when submerged) is depth-culled and hidden, while near-side water
        // still sorts over it. Terrain, drawn earlier, occludes the plane normally.
        //
        // AFTER_BLOCK_ENTITIES is the LAST stage before that water pass, and everything that can cross
        // the boundary is already in the depth buffer by then: the entity solid/cutout batches, the
        // block-entity pass, and Flywheel's instanced visuals (it dispatches at the "blockentities"
        // profiler push, just ahead of vanilla's own block entities). That is what the soft-occlusion
        // edge needs -- SceneDepth is captured right here, so a mob or a Create machine crossing the
        // plane dissolves into the murk exactly like a terrain block does. Captured any earlier the
        // snapshot holds terrain only, and everything else gets razor-cut at the boundary instead.
        //
        // The plane sat at AFTER_CUTOUT_BLOCKS for this reason: a Sable sub-level above the boundary
        // has to still render over the fog rather than be painted over by it. It is drawn well before
        // this stage, so its depth wins the test here; if a future Sable version moves its sub-level
        // pass past the block entities that would need revisiting.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }

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
        WaterlineMap.update(mc.level, cam, Mth.floor(surfaceY), mapBlocks);

        // Snapshot the scene depth BEFORE drawing the plane, so the plane and the later vapour pass can
        // soft-fade against occluding geometry (the plane writes depth, so sampling the live depth buffer
        // would be a read/write feedback loop). At this stage that snapshot covers mobs and machines as
        // well as terrain, which is what softens their cut at the boundary. Reused by FogVapor. Skipped entirely when soft occlusion
        // is disabled by config -- the only case where capturing it buys nothing this frame.
        boolean wantSoftOcclusion = Config.PLANE_SOFT_OCCLUSION.getAsBoolean();
        if (wantSoftOcclusion) {
            SceneDepth.capture();
        }
        // Degrades to "never occlude" in the shader (see fogged_softOcclusion) rather than sampling
        // stale/garbage depth when occlusion is off or the capture above failed (see SceneDepth).
        float depthValid = wantSoftOcclusion && SceneDepth.depthAvailable() ? 1.0F : 0.0F;
        lastDepthValid = depthValid > 0.0F;

        float[] plane = Config.planeColor();
        float r = plane[0];
        float g = plane[1];
        float b = plane[2];
        // Config alpha (plane[3]) is ignored: the plane is drawn opaque both sides so it always reads
        // as murk (a low config alpha made it look like clear glass up close).

        float s = mc.options.getEffectiveRenderDistance() * 16.0F + 32.0F;

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
                                        // (the soft pass below turns this back off -- see drawQuad)
        RenderSystem.disableCull();     // visible from both sides

        // Sampler0 = waterline map (foam rings). Sampler3 = scene depth snapshot for soft edges.
        RenderSystem.setShaderTexture(0, WaterlineMap.textureId());
        RenderSystem.setShaderTexture(3, SceneDepth.depthTextureId());

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
            // Fade the rim out so the plane never shows past where the world fades away (visibleReach).
            float fadeEnd = visibleReach(mc, event.getCamera(), below);
            lastFadeEnd = fadeEnd;
            shader.safeGetUniform("PlaneFadeStart").set(fadeEnd * 0.8F);
            shader.safeGetUniform("PlaneFadeEnd").set(fadeEnd);
            // Framebuffer size so the shader maps gl_FragCoord into the scene-depth snapshot.
            shader.safeGetUniform("ScreenSize").set((float) mc.getMainRenderTarget().width, (float) mc.getMainRenderTarget().height);
            shader.safeGetUniform("DepthValid").set(depthValid);
            // Dissolve holes only on the fogged side: from the dry side they'd be a clear window down
            // through the murk. On the fogged side the revealed content is hidden by the murk fog.
            shader.safeGetUniform("HolesActive").set(below ? 1.0F : 0.0F);
            // Per-entity dissolve discs so crossing mobs/players poke through instead of being hard-cut.
            int holes = gatherEntityHoles(mc.level, cam, surfaceY);
            lastHoles = holes;
            shader.safeGetUniform("EntityHoleCount").set(holes);
            shader.safeGetUniform("EntityHoles").set(entityHoleBuf);
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
        }

        // Put the camera view into RenderSystem's modelview for the draw (its leftover state varies by
        // stage), then emit raw camera-relative vertices so the shader keeps Position world-anchored.
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(view);
        RenderSystem.applyModelViewMatrix();

        // Two passes over the same quad, split in the shader by the plane's own opacity (DepthPass): the
        // solid core keeps the depth write that makes the plane a murk barrier, then every softened
        // fragment -- occlusion dissolve, dissolve holes, the far rim -- is blended with depth writes
        // off, so nothing the player can see through goes on hiding what is behind it. That split is
        // also what lets the softness be plain alpha instead of a screen-door dither (see the shader).
        // Without the shader the fallback has no DepthPass uniform, so it stays one opaque draw.
        if (shader != null) {
            shader.safeGetUniform("DepthPass").set(1.0F);
            drawQuad(s, relY, r, g, b);

            RenderSystem.depthMask(false);
            shader.safeGetUniform("DepthPass").set(0.0F);
            drawQuad(s, relY, r, g, b);
        } else {
            drawQuad(s, relY, r, g, b);
        }

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
    }

    // One camera-centred quad at the boundary height, opaque (see the note on config alpha above):
    // both plane passes emit exactly this, differing only in the DepthPass uniform and the depth mask.
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
            int slot;
            if (count < MAX_ENTITY_HOLES) {
                slot = count++;
            } else {
                // Full: replace the farthest disc, but only if this entity is closer than it.
                int farthest = 0;
                for (int i = 1; i < MAX_ENTITY_HOLES; i++) {
                    if (entityHoleDistSq[i] > entityHoleDistSq[farthest]) {
                        farthest = i;
                    }
                }
                if (dSq >= entityHoleDistSq[farthest]) {
                    continue;
                }
                slot = farthest;
            }
            entityHoleDistSq[slot] = dSq;
            entityHoleBuf[slot * 4] = (float) dx;
            entityHoleBuf[slot * 4 + 1] = (float) dz;
            entityHoleBuf[slot * 4 + 2] = (float) radius;
            entityHoleBuf[slot * 4 + 3] = (float) vgap;
        }
        return count;
    }
}
