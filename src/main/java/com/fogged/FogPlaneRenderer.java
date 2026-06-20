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
// It is drawn AFTER the translucent (water) pass and depth-tested against it: water above the plane
// blends OVER it (you see the plane through the water), while water below the plane sits behind it and
// is tinted by the plane like any other block below the boundary. Opaque terrain occludes it normally.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogPlaneRenderer {

    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        // Draw AFTER translucent water. Vanilla water writes depth, so the depth test then sorts the
        // plane against it per pixel: water ABOVE the plane is nearer and draws over it (you see the
        // plane through the water), while water BELOW the plane is behind it -- the semi-transparent
        // plane draws over and tints that water just like it tints a normal block below the boundary.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
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
        // Config alpha (plane[3]) is intentionally ignored: the plane is drawn opaque on both sides so
        // it always reads as murk (a low config alpha is what made it look like clear glass up close).

        float s = mc.options.getEffectiveRenderDistance() * 16.0F + 32.0F;

        Matrix4f mat = event.getPoseStack().last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest(); // depth-sorted against terrain AND the already-drawn water
        RenderSystem.depthMask(false);  // translucent overlay: test depth but don't write it
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
            // Fade only the very outer rim of the quad so the world's edge fog shows at the horizon.
            // Keep it tight to the geometric edge (s = half-size), otherwise a shallow, close-up view --
            // which fills the screen with far fragments -- fades most of the surface away and the plane
            // looks like it doesn't render. Edge midpoints sit at ~s, corners at ~s*1.41.
            shader.safeGetUniform("PlaneFadeStart").set(s * 1.0F);
            shader.safeGetUniform("PlaneFadeEnd").set(s * 1.5F);
        } else {
            RenderSystem.setShader(GameRenderer::getPositionColorShader);
        }

        // Force the plane's fog to our boundary fog instead of whatever the scene is using. Same on
        // BOTH sides: start the murk almost at the camera and reach full quickly, so the surface reads
        // murky right up close at a shallow angle instead of see-through, viewed from above or below.
        // Foam is applied AFTER fog in the shader, so it stays visible on top even of full murk.
        float far = Config.FOG_DISTANCE.getAsInt();
        float savedFogStart = RenderSystem.getShaderFogStart();
        float savedFogEnd = RenderSystem.getShaderFogEnd();
        float[] savedFogColor = RenderSystem.getShaderFogColor();
        RenderSystem.setShaderFogStart(0.0F);
        RenderSystem.setShaderFogEnd(far * 0.15F);
        RenderSystem.setShaderFogColor(r, g, b, 1.0F); // plane colour, like the fog beneath it

        // Render the plane opaque on BOTH sides so it always reads as a murk surface. The configured
        // alpha can be low (so the plane shows through water), but that low alpha is what made it look
        // like clear glass at a shallow angle -- so force it opaque here. Foam still overlays on top.
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
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}
