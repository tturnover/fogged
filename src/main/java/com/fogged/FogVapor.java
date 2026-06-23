package com.fogged;

import org.joml.Matrix4f;

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

    // Pixel grid the shader snaps the noise to (matches WaterTexture/foam's 4 px per block), and the
    // world-space frequency of the terrace/wisp noise.
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

        Vec3 cam = event.getCamera().getPosition();
        double surfaceY = Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET;
        float relY = (float) (surfaceY - cam.y);
        boolean below = (cam.y < surfaceY) != Config.FLIP_FOG.getAsBoolean();
        // Same fade range the plane uses: the murk distance below the boundary, render distance above.
        float fogFar = below ? Config.FOG_DISTANCE.getAsInt() : mc.options.getEffectiveRenderDistance() * 16.0F;

        float partial = mc.getTimer().getGameTimeDeltaPartialTick(false);
        double timeSeconds = (mc.level.getGameTime() + partial) / 20.0;

        render(event.getPoseStack().last().pose(), cam, surfaceY, relY, fogFar, timeSeconds);
    }

    // cam        : camera world position
    // surfaceY   : world Y of the visible plane surface
    // relY       : surfaceY - cam.y (camera-relative plane height)
    // fogFar     : distance at which the world fades out on the visible side (== plane PlaneFadeEnd)
    // timeSeconds: smooth game time in seconds for the drift animation
    private static void render(Matrix4f mat, Vec3 cam, double surfaceY, float relY, float fogFar,
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
        shader.safeGetUniform("WorldOffset").set((float) cam.x, (float) cam.y, (float) cam.z);
        shader.safeGetUniform("Time").set((float) timeSeconds);
        shader.safeGetUniform("WispScale").set(WISP_SCALE);
        shader.safeGetUniform("PixelsPerBlock").set(PIXELS_PER_BLOCK);
        shader.safeGetUniform("PlaneFadeStart").set(fogFar * 0.8F);
        shader.safeGetUniform("PlaneFadeEnd").set(fogFar);

        // Dissolve the vapour into fog of the plane's own colour across the visible range.
        float[] plane = Config.planeColor();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] savedFogColor = RenderSystem.getShaderFogColor();
        RenderSystem.setShaderFogStart(fogFar * 0.25F);
        RenderSystem.setShaderFogEnd(fogFar);
        RenderSystem.setShaderFogColor(plane[0], plane[1], plane[2], 1.0F);

        // Equally-spaced flat planes from just above the boundary up to the configured height. Each is a
        // single big quad; the shader keeps it only where the noise field reaches that plane's rising
        // threshold (sampled at the plane's pixel resolution), so the planes nest into terraced steps.
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
            bb.addVertex(mat, -reach, y, -reach).setColor(vr, vg, vb, baseA);
            bb.addVertex(mat, -reach, y, reach).setColor(vr, vg, vb, baseA);
            bb.addVertex(mat, reach, y, reach).setColor(vr, vg, vb, baseA);
            bb.addVertex(mat, reach, y, -reach).setColor(vr, vg, vb, baseA);
            BufferUploader.drawWithShader(bb.buildOrThrow());
        }

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);
        RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
