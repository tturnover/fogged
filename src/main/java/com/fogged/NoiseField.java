package com.fogged;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

// The surface's noise fields baked into a texture: the vapour's terrace field and the plane's spot
// field (see fogged_field.glsl), one texel per pixel-snap cell, over a square of the world centred
// on the camera. Both fields are functions of the world position and a slow clock, and every one of
// the mist's ten sheets evaluated the same field for its pixel -- two fbm passes of four octaves
// each, per sheet, per pixel, which was most of what the mod spent on the GPU. Now the field is
// evaluated once per cell, a few times a second, and each pixel fetches its texel.
//
// World-anchored the way the shaders are: the fields tile at NOISE_ANCHOR blocks, so baking them
// with the same anchor-relative coordinates the shaders use gives the same values, and an anchor
// flip changes nothing in the texture. Rebaked when the camera drifts far enough from the texture's
// centre, when the map's cell resolution changes, and every BAKE_INTERVAL_MS for the boil -- at its
// rate (one reshuffle per ten seconds) that is more than smooth enough.
//
// Baked at the start of the frame (RenderFrameEvent.Pre), before the world render: under an Iris
// shader pack that is the one window where this mod's own shaders draw (see IrisCompatibility).
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class NoiseField {

    private static final int REACH_BLOCKS = 256;        // the texture covers +/- this many blocks around the camera
    private static final int RECENTRE_BLOCKS = 48;      // rebake once the camera is this far off the centre
    private static final long BAKE_INTERVAL_MS = 100;   // how often the boil advances

    private static TextureTarget target;
    private static int cells;                            // texel edge length
    private static int cellsPerBlock;
    private static double originX;                       // WORLD XZ of texel (0, 0)'s cell corner
    private static double originZ;
    private static float time;                           // the boil clock the texture was baked at
    private static long lastBakeMs;
    private static boolean valid;

    private NoiseField() {
    }

    public static boolean valid() {
        return valid && target != null;
    }

    public static int textureId() {
        return target == null ? 0 : target.getColorTextureId();
    }

    public static int cells() {
        return cells;
    }

    public static int cellsPerBlock() {
        return cellsPerBlock;
    }

    /** World X of the texture's corner; subtract the frame's noise anchor before handing it to a shader. */
    public static double originX() {
        return originX;
    }

    public static double originZ() {
        return originZ;
    }

    /** The clock the texture was baked at, for a shader's fallback evaluation to match it. */
    public static float time() {
        return time;
    }

    @SubscribeEvent
    static void onRenderFrame(RenderFrameEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.RENDER_PLANE.getAsBoolean()) {
            valid = false;
            return;
        }
        ShaderInstance shader = FogShaders.NOISE_FIELD;
        if (shader == null) {
            valid = false;
            return;
        }
        // Last frame's camera: this runs before the frame's own is set up, and the texture's reach
        // has far more slack than a frame of movement.
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        int wantCells = Mth.clamp(WaterlineMap.cellsPerBlock(), 1, 4);
        int wantSize = wantCells * REACH_BLOCKS * 2;
        boolean rebuild = target == null || wantSize != cells;
        if (rebuild) {
            if (target != null) {
                target.destroyBuffers();
            }
            target = new TextureTarget(wantSize, wantSize, false, false);
            target.setFilterMode(GL11.GL_NEAREST);
            cells = wantSize;
            cellsPerBlock = wantCells;
        }
        long now = System.currentTimeMillis();
        boolean drifted = Math.abs(cam.x - (originX + REACH_BLOCKS)) > RECENTRE_BLOCKS
                || Math.abs(cam.z - (originZ + REACH_BLOCKS)) > RECENTRE_BLOCKS;
        if (!rebuild && !drifted && now - lastBakeMs < BAKE_INTERVAL_MS) {
            return;
        }
        if (rebuild || drifted) {
            // Snap the corner to whole blocks so texel (i, j) is the cell at exactly origin + (i, j) / cells.
            originX = Math.floor(cam.x) - REACH_BLOCKS;
            originZ = Math.floor(cam.z) - REACH_BLOCKS;
        }
        lastBakeMs = now;
        time = FogShaders.animTimeSeconds();

        // Anchor-relative, as the readers' worldXZ is (see FogPlaneRenderer.NOISE_ANCHOR).
        double ax = Math.rint(cam.x / FogPlaneRenderer.NOISE_ANCHOR) * FogPlaneRenderer.NOISE_ANCHOR;
        double az = Math.rint(cam.z / FogPlaneRenderer.NOISE_ANCHOR) * FogPlaneRenderer.NOISE_ANCHOR;

        int previousFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int mainW = mc.getMainRenderTarget().width;
        int mainH = mc.getMainRenderTarget().height;
        target.bindWrite(true);
        RenderSystem.disableBlend();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(() -> shader);
        shader.safeGetUniform("NoiseOrigin").set((float) (originX - ax), (float) (originZ - az));
        shader.safeGetUniform("NoiseCellsPerBlock").set((float) cellsPerBlock);
        shader.safeGetUniform("Time").set(time);
        shader.safeGetUniform("WispScale").set(FogVapor.WISP_SCALE);

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bb.addVertex(-1.0F, -1.0F, 0.0F);
        bb.addVertex(1.0F, -1.0F, 0.0F);
        bb.addVertex(1.0F, 1.0F, 0.0F);
        bb.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
        GlStateManager._viewport(0, 0, mainW, mainH);
        valid = true;
    }
}
