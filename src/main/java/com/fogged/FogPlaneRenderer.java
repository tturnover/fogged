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
//
// It is drawn BEFORE the translucent (water) pass so real water blends OVER it -- i.e. you see the
// plane THROUGH the water -- and so opaque terrain still occludes it via the normal depth test.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogPlaneRenderer {

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Draw just before translucent terrain/water, so water renders on top of (through to) the plane.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
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
        double surfaceY = Config.breathHeight(mc.level) + Config.PLANE_SURFACE_OFFSET;
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
        float a = plane[3];

        float s = mc.options.getEffectiveRenderDistance() * 16.0F + 32.0F;

        Matrix4f mat = event.getPoseStack().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); // occluded by opaque terrain/objects in front of it
        RenderSystem.depthMask(true);   // write depth so water BELOW the plane is hidden; water ABOVE
                                        // it is nearer, passes the depth test, and still blends on top
        RenderSystem.disableCull();     // visible from both above and below

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
            float[] foam = Config.foamColor();
            shader.safeGetUniform("FoamColor").set(foam[0], foam[1], foam[2], foam[3]);
            // Where the waterline map sits in the world, and how its stored distance is scaled.
            shader.safeGetUniform("WaterlineOrigin").set(WaterlineMap.originX(), WaterlineMap.originZ());
            shader.safeGetUniform("WaterlineSize").set((float) WaterlineMap.size());
            shader.safeGetUniform("WaterlineMaxDist").set(WaterlineMap.MAX_DIST);
            // Fade only the quad's far rim (3D distance) so the surface stays solid everywhere you
            // look and just the outer edge hides itself against the horizon.
            shader.safeGetUniform("PlaneFadeStart").set(s * 0.85F);
            shader.safeGetUniform("PlaneFadeEnd").set(s * 1.35F);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        }

        // While below the surface, force the plane's fog to our boundary fog instead of whatever the
        // scene is using. Vanilla's dense, short water fog would otherwise blend the plane away, making
        // it invisible when the camera is submerged (FogModifier leaves real-fluid fog to vanilla).
        boolean below = cam.y < surfaceY;
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] savedFogColor = RenderSystem.getShaderFogColor();
        if (below) {
            float far = Config.FOG_DISTANCE.getAsInt();
            RenderSystem.setShaderFogStart(far * 0.25F);
            RenderSystem.setShaderFogEnd(far);
            RenderSystem.setShaderFogColor(r, g, b, 1.0F); // plane colour, like the fog beneath it
        }

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        bb.addVertex(mat, -s, relY, -s).setColor(r, g, b, a);
        bb.addVertex(mat, -s, relY, s).setColor(r, g, b, a);
        bb.addVertex(mat, s, relY, s).setColor(r, g, b, a);
        bb.addVertex(mat, s, relY, -s).setColor(r, g, b, a);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        if (below) {
            RenderSystem.setShaderFogStart(savedFogStart);
            RenderSystem.setShaderFogEnd(savedFogEnd);
            RenderSystem.setShaderFogColor(savedFogColor[0], savedFogColor[1], savedFogColor[2], savedFogColor[3]);
        }

        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
