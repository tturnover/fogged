package com.fogged;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// Renders a large flat quad at the breathing boundary, centred on the camera so it reads as an
// "infinite" separation surface. Foam comes from a world-space waterline map (WaterlineMap).
//
// Drawn AFTER the translucent water pass and depth-tested against it (vanilla water writes depth):
// water above the plane draws over it, water below sits behind and is tinted by the plane like any
// other sub-boundary block. Opaque terrain occludes it normally.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogPlaneRenderer {

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (!Config.RENDER_PLANE.getAsBoolean()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        double surfaceY = Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET;
        // Below the boundary (incl. submerged in real water) the plane is the murk ceiling overhead.
        boolean below = cam.y < surfaceY;

        // Above the boundary: draw AFTER translucent water so water above the plane sorts over it.
        // Below it: draw BEFORE the translucent pass, so the real water surface overhead hasn't written
        // depth yet and can't occlude the plane. Depth test stays on either way so terrain still hides it.
        var wantStage = below
                ? RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES
                : RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS;
        if (event.getStage() != wantStage) {
            return;
        }

        // Camera-relative so the pose matrix maps straight to clip space.
        float relY = (float) (surfaceY - cam.y);

        // Keep the world-space waterline foam map up to date around the camera. Its radius follows the
        // render distance so the foam/light covers the part of the plane that is actually visible.
        int mapBlocks = mc.options.getEffectiveRenderDistance() * 16;
        WaterlineMap.update(mc.level, cam, Mth.floor(surfaceY), mapBlocks);

        float[] plane = Config.planeColor();
        float r = plane[0];
        float g = plane[1];
        float b = plane[2];
        // Config alpha (plane[3]) is ignored: the plane is drawn opaque both sides so it always reads
        // as murk (a low config alpha made it look like clear glass up close).

        float s = mc.options.getEffectiveRenderDistance() * 16.0F + 32.0F;

        Matrix4f mat = event.getPoseStack().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); // sorted against terrain (and, above the boundary, the water)
        RenderSystem.depthMask(false);  // translucent overlay: test depth but don't write it
        RenderSystem.disableCull();     // visible from both sides

        // Sampler0 = waterline distance map (foam), Sampler1 = procedural surface tile.
        RenderSystem.setShaderTexture(0, WaterlineMap.textureId());
        RenderSystem.setShaderTexture(1, WaterTexture.textureId());

        // Our shader once loaded; fall back to the plain one otherwise.
        ShaderInstance shader = FogShaders.FOG_PLANE;
        if (shader != null) {
            RenderSystem.setShader(() -> shader);
            shader.safeGetUniform("WorldOffset").set((float) cam.x, (float) cam.y, (float) cam.z);
            shader.safeGetUniform("FoamWidth").set((float) (double) Config.FOAM_WIDTH.get());
            shader.safeGetUniform("FoamDebug").set(Config.FOAM_DEBUG.getAsBoolean() ? 1.0F : 0.0F);
            float[] foam = Config.foamColor();
            shader.safeGetUniform("FoamColor").set(foam[0], foam[1], foam[2], foam[3]);
            // Where the waterline map sits in the world, and how its stored distance is scaled.
            shader.safeGetUniform("WaterlineOrigin").set(WaterlineMap.originX(), WaterlineMap.originZ());
            shader.safeGetUniform("WaterlineSize").set((float) WaterlineMap.size());
            shader.safeGetUniform("WaterlineMaxDist").set(WaterlineMap.MAX_DIST);
            // Fade the rim out at the render-distance fog wall so the plane never shows past where
            // terrain fades to sky. Keyed to render distance in blocks, not the (larger) geometry edge.
            float renderBlocks = mc.options.getEffectiveRenderDistance() * 16.0F;
            shader.safeGetUniform("PlaneFadeStart").set(renderBlocks * 0.8F);
            shader.safeGetUniform("PlaneFadeEnd").set(renderBlocks);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        }

        // Override scene fog with our boundary fog, same both sides: murk starts at the camera and
        // reaches full within a few blocks, so the surface is murky even up close at a shallow angle.
        // Foam is applied AFTER fog in the shader, so it stays visible on top.
        float far = Config.FOG_DISTANCE.getAsInt();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] savedFogColor = RenderSystem.getShaderFogColor();
        RenderSystem.setShaderFogStart(0.0F);
        RenderSystem.setShaderFogEnd(far * 0.15F);
        RenderSystem.setShaderFogColor(r, g, b, 1.0F); // plane colour, like the fog beneath it

        // Opaque both sides so it always reads as murk (see note on config alpha above).
        float va = 1.0F;
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(mat, -s, relY, -s).setColor(r, g, b, va);
        bb.addVertex(mat, -s, relY, s).setColor(r, g, b, va);
        bb.addVertex(mat, s, relY, s).setColor(r, g, b, va);
        bb.addVertex(mat, s, relY, -s).setColor(r, g, b, va);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.setShaderFogStart(savedFogStart);
        RenderSystem.setShaderFogEnd(savedFogEnd);
        RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
