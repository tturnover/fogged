package com.fogged;

import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// Cold-vapour ("liquid nitrogen") layer of terraced mist steps over the separation plane: stacked flat
// sheets gated by a shared world-anchored noise field, where each higher layer only appears where the
// noise is higher, so they nest into discrete noise-shaped steps. The otherwise dead-flat plane gains
// blocky terraces, faint and stacked so a grazing line of sight reads them as "overall variation far
// from the player". Pixel-snapped by the fog_vapor shader to match the plane's blocky look.
//
// Drawn at up to two stages (see onRenderLevelStage): one after the water pass so the mist veils over
// water, and one before it so water veils over the mist. Together they keep the layer continuous
// where a lake meets the shore.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class FogVapor {

    // World-space frequency of the terrace/wisp noise. Chosen so NOISE_ANCHOR * WISP_SCALE (4096 *
    // 0.09375 = 384) is a whole number: the tileable noise wraps at that integer lattice period, so the
    // wisps repeat exactly with the anchor -> no seam.
    private static final float WISP_SCALE = 0.09375F;

    private FogVapor() {
    }

    // LOW priority so that when the murk composite is at this same stage (the default) it has already
    // run: two handlers on one stage are otherwise ordered by registration, and the mist has to veil
    // OVER the murk, not be painted out by it.
    @SubscribeEvent(priority = EventPriority.LOW)
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!Config.RENDER_PLANE.getAsBoolean() || !Config.RENDER_VAPOR.getAsBoolean()) {
            return;
        }
        // The mist is drawn at up to TWO stages, and the same sheets at that.
        //
        // vaporStage (default the murk's own) is after the water pass: terrain, murk and water are all
        // in the buffers, so the mist veils over them. That copy alone floats on top of a lake.
        //
        // vaporUnderwaterStage (default AFTER_ENTITIES) is before the water pass, so water composites
        // over the mist instead and it reads as lying beneath the surface. Drawing BOTH is what makes
        // the layer continuous across a shoreline: over land the first copy shows, under a lake the
        // second, and neither has to know where the water ends.
        RenderLevelStageEvent.Stage main = FogPlaneRenderer.stage(Config.VAPOR_STAGE.get());
        RenderLevelStageEvent.Stage under = Config.VAPOR_UNDERWATER.getAsBoolean()
                ? FogPlaneRenderer.stage(Config.VAPOR_UNDERWATER_STAGE.get())
                : null;
        // `under != main` keeps a matching pair of settings from drawing the same sheets twice into
        // the same buffer, which would just double the mist's density.
        if (event.getStage() != main && !(event.getStage() == under && under != main)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        double surfaceY = Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET;
        // Follows the surface it sits on: both are hidden inside the liquid the camera is in.
        if (SubmergedMurk.hidesSurface(event.getCamera(), mc.level, surfaceY)) {
            return;
        }
        float relY = (float) (surfaceY - cam.y);
        boolean below = (cam.y < surfaceY) != Config.FLIP_FOG.getAsBoolean();
        // Same fade range the plane uses (see FogPlaneRenderer#visibleReach).
        float fogFar = FogPlaneRenderer.visibleReach(mc, below);

        // Monotonic clock so the boil never runs backward (see FogShaders#animTimeSeconds).
        double timeSeconds = FogShaders.animTimeSeconds();

        // Use the camera view matrix (reliable at every stage) and emit raw camera-relative vertices,
        // so the shader can world-anchor the noise via Position + WorldOffset. Baking the pose into the
        // vertices instead would leave Position in view space, making the noise swim/flicker with the
        // camera. Mirrors FogPlaneRenderer.
        render(event.getModelViewMatrix(), cam, surfaceY, relY, fogFar, timeSeconds);
    }

    // view       : camera view matrix (raw camera-relative vertices are anchored to world in the shader)
    // cam        : camera world position
    // surfaceY   : world Y of the visible plane surface
    // relY       : surfaceY - cam.y (camera-relative plane height)
    // fogFar     : distance at which the world fades out on the visible side (== plane PlaneFadeEnd)
    // timeSeconds: smooth game time in seconds for the drift animation
    private static void render(Matrix4f view, Vec3 cam, double surfaceY, float relY, float fogFar,
            double timeSeconds) {
        ShaderInstance shader = FogShaders.FOG_VAPOR;
        if (shader == null) {
            return;
        }

        float[] vapor = Config.vaporColor();
        float vr = vapor[0];
        float vg = vapor[1];
        float vb = vapor[2];
        float va = vapor[3]; // overall strength multiplier

        int sheets = Config.VAPOR_SHEETS.getAsInt();
        double undulation = Config.VAPOR_UNDULATION.get();
        if (sheets <= 0) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        float reach = Math.min(mc.options.getEffectiveRenderDistance() * 16.0F, fogFar);

        // The mist always sits ON TOP of the surface, and is always drawn -- it does not follow the
        // camera to the underside. From below the murk itself hides it wherever the ceiling is solid,
        // and it shows through the dithered gaps around whatever crosses the boundary, which is where
        // you would expect to catch sight of it.
        final float side = 1.0F;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); // terrain and the murk occlude the vapour
        RenderSystem.depthMask(false);  // translucent: never writes depth
        RenderSystem.disableCull();     // seen from both sides

        // Sampler0 = waterline map, so the mist can thin on the same foam ring the surface draws.
        RenderSystem.setShaderTexture(0, WaterlineMap.textureId());
        RenderSystem.setShader(() -> shader);
        // Anchor world coords to a tile near the camera so the floor()-snapped wisp noise stays precise
        // far from spawn instead of boiling/flickering. Same period as the plane (see NOISE_ANCHOR).
        // Round (not floor) so anchor seams fall at +/-NOISE_ANCHOR/2, not on x=0 / z=0 -- a floor
        // put a seam on spawn, so crossing 0 flipped the anchor and made the snapped noise jump.
        double ax = Math.rint(cam.x / FogPlaneRenderer.NOISE_ANCHOR) * FogPlaneRenderer.NOISE_ANCHOR;
        double az = Math.rint(cam.z / FogPlaneRenderer.NOISE_ANCHOR) * FogPlaneRenderer.NOISE_ANCHOR;
        shader.safeGetUniform("WorldOffset").set((float) (cam.x - ax), (float) cam.y, (float) (cam.z - az));
        shader.safeGetUniform("Time").set((float) timeSeconds);
        shader.safeGetUniform("WispScale").set(WISP_SCALE);
        // The same grid the plane's foam and spots are drawn on (see WaterlineMap.effectPixelsPerBlock),
        // so the mist's pixels line up with theirs instead of straddling them.
        shader.safeGetUniform("PixelsPerBlock").set((float) WaterlineMap.effectPixelsPerBlock());
        // Where the foam map sits and how its stored distance scales -- the same values the surface
        // passes, anchored to the same tile, or the two would disagree about where the ring is.
        shader.safeGetUniform("WaterlineOrigin").set((float) (WaterlineMap.originX() - ax),
                (float) (WaterlineMap.originZ() - az));
        shader.safeGetUniform("WaterlineSize").set((float) WaterlineMap.size());
        shader.safeGetUniform("WaterlineMaxDist").set(WaterlineMap.MAX_DIST);
        shader.safeGetUniform("FoamPixelsPerBlock").set((float) WaterlineMap.effectPixelsPerBlock());
        shader.safeGetUniform("MapPixelsPerBlock").set((float) WaterlineMap.cellsPerBlock());
        shader.safeGetUniform("FoamWidth").set((float) (double) Config.FOAM_WIDTH.get());
        shader.safeGetUniform("PlaneFadeStart").set(fogFar * 0.8F);
        shader.safeGetUniform("PlaneFadeEnd").set(fogFar);
        // Overall vapour tint, shared by every sheet: Color's rgb channels are repurposed below to pack
        // per-sheet data instead (see the sheet loop), so the tint now travels as a uniform.
        shader.safeGetUniform("VaporColor").set(vr, vg, vb);

        // Fade the vapour out across the visible range (the shader dissolves its alpha by FogStart/End;
        // it keeps its own vertex colour, so no fog colour is needed here).
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        RenderSystem.setShaderFogStart(fogFar * 0.25F);
        RenderSystem.setShaderFogEnd(fogFar);

        // Equally-spaced flat planes from just above the boundary up to the configured height. Each is a
        // single big quad; the shader keeps it only where the noise field reaches that plane's rising
        // threshold (sampled at the plane's pixel resolution), so the planes nest into terraced steps.
        // Put the camera view into RenderSystem's modelview and emit raw camera-relative vertices, so
        // Position stays world-anchorable in the shader (see the note at the call site).
        Matrix4fStack mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.set(view);
        RenderSystem.applyModelViewMatrix();

        // All sheets are built into ONE buffer and drawn in ONE call instead of one draw per sheet: each
        // sheet still needs its own StepThreshold (a per-vertex, not per-frame, value), but rather than
        // adding a whole new custom vertex attribute to carry it, the Color attribute already varies
        // per-vertex within a single buffer -- so StepThreshold rides in Color.r (0..1, plenty of
        // precision for a value compared via smoothstep with a 0.12-wide band) and the per-sheet alpha
        // rides in Color.a exactly as before; Color.g/b are unused. See fog_vapor.fsh for the unpacking.
        double spacing = undulation / sheets;
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < sheets; i++) {
            // Bottom plane carries most of the alpha (so the mist reads up close, face-on); higher
            // planes fall off fast so grazing overlaps at distance don't pile into a solid wall.
            float baseA = va * 0.5F * (float) Math.pow(0.55, i);
            float threshold = (i + 1.0F) / (sheets + 1.0F);
            float y = relY + side * (float) (0.3 + (i + 1) * spacing);
            bb.addVertex(-reach, y, -reach).setColor(threshold, 0.0F, 0.0F, baseA);
            bb.addVertex(-reach, y, reach).setColor(threshold, 0.0F, 0.0F, baseA);
            bb.addVertex(reach, y, reach).setColor(threshold, 0.0F, 0.0F, baseA);
            bb.addVertex(reach, y, -reach).setColor(threshold, 0.0F, 0.0F, baseA);
        }
        BufferUploader.drawWithShader(bb.buildOrThrow());

        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
