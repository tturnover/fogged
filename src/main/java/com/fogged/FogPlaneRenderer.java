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

// Renders a large, flat, water-like quad at the breathing boundary height, centred on the camera so
// it reads as an "infinite" separation surface. Foam comes from a world-space waterline map (see
// WaterlineMap); the surface pattern comes from a procedural tile (see WaterTexture).
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogPlaneRenderer {

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Render after particles so particles below the surface are covered by the plane instead of
        // showing through it.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        if (!Config.RENDER_PLANE.getAsBoolean()) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }

        Vec3 cam = event.getCamera().getPosition();
        double surfaceY = Config.BREATH_HEIGHT.get() + Config.PLANE_SURFACE_OFFSET;
        // Camera-relative so the pose matrix maps straight to clip space.
        float relY = (float) (surfaceY - cam.y);

        // Keep the world-space waterline foam map up to date around the camera.
        WaterlineMap.update(mc.level, cam, Mth.floor(surfaceY));

        float r = Config.PLANE_RED.getAsInt() / 255.0F;
        float g = Config.PLANE_GREEN.getAsInt() / 255.0F;
        float b = Config.PLANE_BLUE.getAsInt() / 255.0F;
        float a = Config.PLANE_ALPHA.getAsInt() / 255.0F;

        float s = mc.options.getEffectiveRenderDistance() * 16.0F + 32.0F;

        Matrix4f mat = event.getPoseStack().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false); // translucent: don't write depth
        RenderSystem.disableCull();    // visible from both above and below

        // Sampler0 = world-space waterline distance map (foam), Sampler1 = procedural water tile.
        RenderSystem.setShaderTexture(0, WaterlineMap.textureId());
        RenderSystem.setShaderTexture(1, WaterTexture.textureId());

        // Use our water shader once it has loaded; fall back to the plain one otherwise.
        ShaderInstance shader = FogShaders.FOG_PLANE;
        if (shader != null) {
            RenderSystem.setShader(() -> shader);
            shader.safeGetUniform("WorldOffset").set((float) cam.x, (float) cam.y, (float) cam.z);
            shader.safeGetUniform("FoamWidth").set((float) (double) Config.FOAM_WIDTH.get());
            shader.safeGetUniform("FoamDebug").set(Config.FOAM_DEBUG.getAsBoolean() ? 1.0F : 0.0F);
            // Where the waterline map sits in the world, and how its stored distance is scaled.
            shader.safeGetUniform("WaterlineOrigin").set(WaterlineMap.originX(), WaterlineMap.originZ());
            shader.safeGetUniform("WaterlineSize").set((float) WaterlineMap.SIZE);
            shader.safeGetUniform("WaterlineMaxDist").set(WaterlineMap.MAX_DIST);
            // Fade only the quad's far rim (3D distance) so the surface stays solid everywhere you
            // look and just the outer edge hides itself against the horizon.
            shader.safeGetUniform("PlaneFadeStart").set(s * 0.85F);
            shader.safeGetUniform("PlaneFadeEnd").set(s * 1.35F);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        }

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(mat, -s, relY, -s).setColor(r, g, b, a);
        bb.addVertex(mat, -s, relY, s).setColor(r, g, b, a);
        bb.addVertex(mat, s, relY, s).setColor(r, g, b, a);
        bb.addVertex(mat, s, relY, -s).setColor(r, g, b, a);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
