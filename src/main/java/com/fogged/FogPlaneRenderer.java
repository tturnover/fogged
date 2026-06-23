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

        // Draw the plane BEFORE the translucent water pass and let it write depth, so it reads as a
        // solid murk barrier: real water on the far side of the plane (deep water below it when looking
        // down, the surface above it when submerged) is depth-culled and hidden, while near-side water
        // still sorts over it. Terrain, drawn earlier, occludes the plane normally.
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
        RenderSystem.enableDepthTest(); // terrain occludes the plane
        RenderSystem.depthMask(true);   // opaque murk: write depth so far-side water is culled by it
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
            // Fade the rim out so the plane never shows past where the world fades away. Above the
            // boundary that wall is the render distance; below it, the much closer murk fog distance,
            // so the ceiling and its foam are obscured once out of the fog's reach.
            float fogFar = Config.FOG_DISTANCE.getAsInt();
            float fadeEnd = below ? fogFar : mc.options.getEffectiveRenderDistance() * 16.0F;
            shader.safeGetUniform("PlaneFadeStart").set(fadeEnd * 0.8F);
            shader.safeGetUniform("PlaneFadeEnd").set(fadeEnd);
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

        // Cold-vapour mist sheets are drawn separately in FogVapor, AFTER the translucent water pass,
        // so the mist veils over water instead of water tracing dark outlines over it.

        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
