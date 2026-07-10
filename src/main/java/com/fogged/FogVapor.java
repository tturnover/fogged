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
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// Cold-vapour ("liquid nitrogen") layer of terraced mist steps over the separation plane: stacked flat
// sheets gated by a shared world-anchored noise field, where each higher layer only appears where the
// noise is higher, so they nest into discrete noise-shaped steps. The otherwise dead-flat plane gains
// blocky terraces, faint and stacked so a grazing line of sight reads them as "overall variation far
// from the player". Pixel-snapped by the fog_vapor shader to match the plane's blocky look.
//
// Drawn AFTER the translucent water pass (its own later stage) so the light mist veils OVER water,
// instead of water compositing over it and tracing dark outlines along the waterline.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class FogVapor {

    // Pixel grid the shader snaps the noise to (matches the foam's 4 px per block), and the world-space
    // frequency of the terrace/wisp noise.
    private static final float PIXELS_PER_BLOCK = 4.0F;
    private static final float WISP_SCALE = 0.09F;

    private FogVapor() {
    }

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!Config.RENDER_PLANE.getAsBoolean() || !Config.RENDER_VAPOR.getAsBoolean()) {
            return;
        }
        // After the translucent water pass: the depth buffer holds water/terrain/plane, so the mist
        // sits on top of the water rather than being darkened by it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        // Submerged in real water: vanilla owns that view, so skip the mist -- otherwise it veils the far
        // underwater scene and sky-through-water in the vapour colour.
        if (event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        double surfaceY = Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET;
        float relY = (float) (surfaceY - cam.y);
        boolean below = (cam.y < surfaceY) != Config.FLIP_FOG.getAsBoolean();
        // Same fade range the plane uses: the murk distance below the boundary, render distance above.
        float fogFar = below ? Config.FOG_DISTANCE.getAsInt() : mc.options.getEffectiveRenderDistance() * 16.0F;

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

        // Vapour lives on whichever side of the plane the camera is on (the side you can actually see).
        float side = cam.y >= surfaceY ? 1.0F : -1.0F;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); // terrain / the plane occlude the vapour
        RenderSystem.depthMask(false);  // translucent: never writes depth
        RenderSystem.disableCull();     // seen from both sides

        RenderSystem.setShader(() -> shader);
        // Anchor world coords to a tile near the camera so the floor()-snapped wisp noise stays precise
        // far from spawn instead of boiling/flickering. Same period as the plane (see NOISE_ANCHOR).
        double ax = Math.floor(cam.x / FogPlaneRenderer.NOISE_ANCHOR) * FogPlaneRenderer.NOISE_ANCHOR;
        double az = Math.floor(cam.z / FogPlaneRenderer.NOISE_ANCHOR) * FogPlaneRenderer.NOISE_ANCHOR;
        shader.safeGetUniform("WorldOffset").set((float) (cam.x - ax), (float) cam.y, (float) (cam.z - az));
        shader.safeGetUniform("Time").set((float) timeSeconds);
        shader.safeGetUniform("WispScale").set(WISP_SCALE);
        shader.safeGetUniform("PixelsPerBlock").set(PIXELS_PER_BLOCK);
        shader.safeGetUniform("PlaneFadeStart").set(fogFar * 0.8F);
        shader.safeGetUniform("PlaneFadeEnd").set(fogFar);

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

        double spacing = undulation / sheets;
        Tesselator tess = Tesselator.getInstance();
        for (int i = 0; i < sheets; i++) {
            // Bottom plane carries most of the alpha (so the mist reads up close, face-on); higher
            // planes fall off fast so grazing overlaps at distance don't pile into a solid wall.
            float baseA = va * 0.5F * (float) Math.pow(0.55, i);
            float threshold = (i + 1.0F) / (sheets + 1.0F);
            float y = relY + side * (float) (0.3 + (i + 1) * spacing);
            shader.safeGetUniform("StepThreshold").set(threshold);
            BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            bb.addVertex(-reach, y, -reach).setColor(vr, vg, vb, baseA);
            bb.addVertex(-reach, y, reach).setColor(vr, vg, vb, baseA);
            bb.addVertex(reach, y, reach).setColor(vr, vg, vb, baseA);
            bb.addVertex(reach, y, -reach).setColor(vr, vg, vb, baseA);
            BufferUploader.drawWithShader(bb.buildOrThrow());
        }

        mvStack.popMatrix();
        RenderSystem.applyModelViewMatrix();

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
