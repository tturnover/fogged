package com.fogged;

import org.joml.Matrix4f;

import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

// What the rest of the mod knows about Distant Horizons this frame. No DH type is named here, so this
// class loads whether or not DH is installed; every one of them is confined to DhFarPass, which only
// register() ever touches, and only with the mod present.
//
// DH draws at the head of the solid layer, into a framebuffer of its own, and copies the colour into
// the main target with the depth test off. So LODs are never in the depth buffer anything else in the
// frame is tested against. Two things follow, and they are what this class carries:
//
// - The plane cannot reach them from out here. Past the render distance it is drawn from inside DH's
//   pass instead (DhFarPass), where LOD depth is the depth buffer.
// - The quad, drawn the usual way, still has to give way to them, and it cannot ask their depth: DH's
//   anti-aliasing shifts LODs a fraction of a pixel every frame and only its colour is ever smoothed, so
//   a test against that depth flickers along every LOD block edge. From where LODs can begin (lodStart)
//   the quad takes DH's finished colour instead, which has the far plane in it and the LODs in front.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
final class DistantHorizonsCompatibility {

    private static final boolean INSTALLED = ModList.get().isLoaded("distanthorizons");

    // How far past farInner the near mist runs, fading out as the far mist fades in, as a multiple of it.
    static final float HANDOFF_BAND = 1.125F;

    // All render thread: set by DhFarPass during the solid layer, read later in the same frame.
    private static boolean lodsThisFrame;
    private static int depthTexture;
    private static int colorTexture;
    private static float clearDepth;
    private static float lodStart;
    private static float farInner;
    private static final Matrix4f invViewProj = new Matrix4f();

    private DistantHorizonsCompatibility() {
    }

    /** Hook into DH, when it is here and new enough. Called once while the client is constructed. */
    static void register() {
        if (INSTALLED) {
            DhFarPass.register();
        }
    }

    /** Whether LODs were drawn this frame and the plane reaches them, so the shaders may ask about them. */
    static boolean lodsThisFrame() {
        return lodsThisFrame;
    }

    /** Radius at which the quad stops. Past lodStart it only shows DH's colour, so nothing changes here. */
    static float farInner() {
        return farInner;
    }

    /** Distance from the camera at which LODs can begin this frame, and the surface is DH's. */
    static float lodStart() {
        return lodStart;
    }

    static void publish(int depthTexture, int colorTexture, float clearDepth, float lodStart, float farInner,
            Matrix4f invViewProj) {
        DistantHorizonsCompatibility.depthTexture = depthTexture;
        DistantHorizonsCompatibility.colorTexture = colorTexture;
        DistantHorizonsCompatibility.clearDepth = clearDepth;
        DistantHorizonsCompatibility.lodStart = lodStart;
        DistantHorizonsCompatibility.farInner = farInner;
        DistantHorizonsCompatibility.invViewProj.set(invViewProj);
        lodsThisFrame = true;
    }

    /** Point a shader's LOD uniforms at this frame's snapshot, or switch them off. */
    static void applyUniforms(ShaderInstance shader) {
        shader.safeGetUniform("LodActive").set(lodsThisFrame ? 1.0F : 0.0F);
        if (!lodsThisFrame) {
            return;
        }
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(4, depthTexture);
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(5, colorTexture);
        shader.safeGetUniform("LodStart").set(lodStart);
        shader.safeGetUniform("LodClearDepth").set(clearDepth);
        shader.safeGetUniform("LodInvViewProj").set(invViewProj);
        // Which way is under the surface: with flipFog the murk is the side above it.
        shader.safeGetUniform("LodFoamSide").set(Config.synced(Config.FLIP_FOG) ? -1.0F : 1.0F);
    }

    // Everything above is only good for the frame that made it: DH can stop drawing at any moment (its
    // renderer switched off, the world still loading), and the shaders must not go on reading the last
    // snapshot it left.
    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            lodsThisFrame = false;
        }
    }
}
