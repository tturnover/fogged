package com.fogged;

import org.joml.Matrix4f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/**
 * The murk as seen from inside a liquid, and the liquid's veto over the murk surface.
 *
 * <p>The murk is cut out of liquids: a lake the boundary passes through keeps its own surface, its own
 * fog and its own rules ({@link FogModifier}, {@link WaterlineMap}'s liquid mask). Seen from inside
 * one, that left two things wrong. The world above the water read as clear daylight even though it is
 * still under the plane, because the murk fog is off while the eye is in a liquid and fog measures
 * from the eye, not from the surface. And the murk surface itself was drawn while it lay under the
 * water the camera was swimming in, where there is nothing to see it through.
 *
 * <p>Both are the same question -- which part of the view is actually outside the liquid -- so both
 * are answered here, in screen space, against the eye's own liquid: a ray leaves it at the liquid's
 * surface, and the stretch from there to the plane (or to whatever solid stops it first) is murk.
 * {@link #hidesSurface} is the same test applied to the murk surface as a whole.
 *
 * <p>The liquid is treated as everything below its surface height. That is exact looking up out of
 * it, which is the view this exists for, and it stops the pass from having to trace a fluid volume
 * per pixel.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class SubmergedMurk {

    // How far above the eye a liquid's surface is looked for. Past this the water is deep enough that
    // its own fog has ended the view long before the surface, so there is nothing for this to draw.
    private static final int MAX_RISE = 64;

    // Sentinel for "the murk has no ceiling this way" -- flipFog puts the murk above the plane, where
    // it runs all the way up instead of ending at the surface.
    private static final float NO_CEILING = 1.0e6F;

    private static final Matrix4f invViewProj = new Matrix4f();

    // What the last frame's pass decided, for the debug HUD (FogDebugOverlay) to show. This pass draws
    // nothing at all in most frames by design, so "no murk" and "never ran" look identical in game.
    private static volatile String status = "eye in air";

    /** One line on what the submerged pass did last frame. */
    public static String status() {
        return status;
    }

    private SubmergedMurk() {
    }

    /**
     * True when the murk surface lies inside the liquid the camera is in, so there is no view of it:
     * the surface is drawn as if the water were not there, and it shows through as a slab of murk
     * across a lake the camera is swimming in. Read by {@link FogPlaneRenderer} and {@link FogVapor}.
     */
    static boolean hidesSurface(Camera camera, Level level, double surfaceY) {
        if (camera.getFluidInCamera() == FogType.NONE) {
            return false;
        }
        double top = fluidTopY(level, camera.getPosition());
        return !Double.isNaN(top) && top > surfaceY;
    }

    // Drawn LAST at the murk's own stage: it fogs whatever is already in the buffer, the murk surface
    // and its vapour included, so it must not be painted over by either.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || event.getStage() != FogPlaneRenderer.murkStage()) {
            return;
        }
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() == FogType.NONE) {
            status = "eye in air";
            return; // the ordinary murk fog already covers this
        }
        Vec3 cam = camera.getPosition();
        if (!Config.fogged(level, cam.y)) {
            status = "swimming above the boundary";
            return;
        }
        ShaderInstance shader = FogShaders.SUBMERGED_MURK;
        if (shader == null) {
            status = "SHADER MISSING";
            return;
        }
        double top = fluidTopY(level, cam);
        if (Double.isNaN(top)) {
            status = "deeper than " + MAX_RISE + " blocks";
            return;
        }
        float fluidTopRel = (float) (top - cam.y);
        if (fluidTopRel <= 0.0F) {
            status = "eye at the surface";
            return;
        }

        // The murk ends at the plane on the way up -- unless flipFog put the murk on the upper side,
        // where it has no ceiling at all.
        double surfaceY = FogBand.surfaceY(level);
        float murkEndRel = Config.FLIP_FOG.getAsBoolean() ? NO_CEILING : (float) (surfaceY - cam.y);
        if (murkEndRel <= fluidTopRel) {
            // The water reaches above the plane, so leaving it puts the eye in clear air: no band.
            status = String.format("no band (surface %.1f above eye, plane %.1f)", fluidTopRel, murkEndRel);
            return;
        }

        // This pass needs the scene depth to know where each ray stops; without it every pixel would
        // be fogged as though nothing but sky were out there, painting murk over near terrain. The
        // murk surface captures the same snapshot earlier in the stage when it draws, but it is
        // skipped exactly when the camera is under the surface (see hidesSurface), so take it here.
        SceneDepth.capture();
        if (!SceneDepth.depthAvailable()) {
            status = "NO SCENE DEPTH";
            return;
        }

        invViewProj.set(event.getProjectionMatrix()).mul(event.getModelViewMatrix()).invert();
        float far = Config.FOG_DISTANCE.getAsInt();
        float[] murk = Config.planeColor();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest(); // the depth snapshot is what this pass tests against
        RenderSystem.depthMask(false);
        RenderSystem.disableCull(); // the clip-space quad below is wound back-facing
        RenderSystem.setShaderTexture(0, SceneDepth.depthTextureId());
        RenderSystem.setShader(() -> shader);

        shader.safeGetUniform("InvViewProj").set(invViewProj);
        shader.safeGetUniform("ScreenSize").set((float) mc.getMainRenderTarget().width,
                (float) mc.getMainRenderTarget().height);
        shader.safeGetUniform("MurkColor").set(murk[0], murk[1], murk[2], 1.0F);
        shader.safeGetUniform("FluidTopRel").set(fluidTopRel);
        shader.safeGetUniform("MurkEndRel").set(murkEndRel);
        // Tied to the murk fog's own reach (see FogModifier): at fogDistance blocks of murk this is
        // ~95% opaque, which is what that fog hides the world at, so surfacing hands the view over
        // between the two without a step in density.
        shader.safeGetUniform("MurkExtinction").set(far / 3.0F);

        status = String.format("band %.1f blk (surface +%.1f, plane +%.1f), extinction %.1f",
                murkEndRel - fluidTopRel, fluidTopRel, murkEndRel, far / 3.0F);

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder bb = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        bb.addVertex(-1.0F, -1.0F, 0.0F);
        bb.addVertex(-1.0F, 1.0F, 0.0F);
        bb.addVertex(1.0F, 1.0F, 0.0F);
        bb.addVertex(1.0F, -1.0F, 0.0F);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /**
     * World Y of the top of the liquid the eye is in, or NaN when no surface is found within
     * {@link #MAX_RISE} blocks. A partly filled block (a flowing column) tops out at its own height,
     * so the surface sits where it is drawn rather than at the block's ceiling.
     */
    private static double fluidTopY(Level level, Vec3 eye) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int x = Mth.floor(eye.x);
        int z = Mth.floor(eye.z);
        int y0 = Mth.floor(eye.y);
        for (int y = y0; y <= y0 + MAX_RISE; y++) {
            pos.set(x, y, z);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.isEmpty()) {
                return y; // the liquid ended below this block, so its top is this block's floor
            }
            float height = fluid.getHeight(level, pos);
            if (height < 1.0F) {
                return y + height;
            }
        }
        return Double.NaN;
    }
}
