package com.fogged;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.material.FogType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

// Replaces the world fog with a short, dense fog whenever the camera is below the breathing
// boundary, so being "under" the plane looks like a low render distance (underwater-ish).
//
// This fog is also the deliberate visual backstop for LevelRendererMixin's sky/weather/cloud
// suppression: that mixin is require=0 and can silently fail to apply under a renderer that
// restructures LevelRenderer (see its comment). FOG_DISTANCE is kept short/dense enough that an
// un-suppressed sky or rain reads as a faint smudge through dense murk rather than a jarring reveal --
// don't decouple this fog from "below the boundary" without preserving that margin.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogModifier {

    // LOW priority: run after any other mod's own RenderFog listener, so the min-compose below sees
    // whatever distance that mod already computed rather than a stale vanilla default.
    @SubscribeEvent(priority = EventPriority.LOW)
    static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!belowBoundary(event.getCamera())) {
            return;
        }
        // Compose with whatever vanilla or an earlier-run mod already set, rather than overwriting it
        // outright: our murk fog only wins when it is actually the tighter constraint, so another mod's
        // (or resource pack's) legitimate fog tightening is never silently discarded.
        float vanillaFar = event.getFarPlaneDistance();
        float far = Math.min(Config.FOG_DISTANCE.getAsInt(), vanillaFar);
        event.setNearPlaneDistance(far * 0.25F);
        event.setFarPlaneDistance(far);
        if (far < vanillaFar) {
            event.setCanceled(true); // only override vanilla's own fog math when ours is actually tighter
        }
    }

    @SubscribeEvent
    static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        if (!belowBoundary(event.getCamera())) {
            return;
        }
        // Match the fog to the separation plane's colour so passing under it feels continuous.
        float[] c = Config.planeColor();
        event.setRed(c[0]);
        event.setGreen(c[1]);
        event.setBlue(c[2]);
    }

    /**
     * Force the murk fog onto the shader uniforms, if the camera is in the murk and nothing tighter is
     * already in force. Called at the head of the terrain pass (see LevelRendererMixin) as well as
     * through the ordinary fog event.
     *
     * <p>The event alone is not enough. NeoForge fires it from inside {@code FogRenderer.setupFog}, and
     * a mod injecting at that method's RETURN runs afterwards and simply writes over the result --
     * Distant Horizons does exactly this to suppress vanilla fog, and it is a common enough shape that
     * it is not worth chasing one mod at a time. There is no render stage between the terrain fog setup
     * and the terrain draw, so the last word has to be taken at the draw itself.
     *
     * <p>Still composes rather than overrides: it only ever shortens the fog, so another mod's tighter
     * fog is left alone exactly as the event listener leaves it.
     */
    public static void enforceMurkFog() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (mc.level == null || !belowBoundary(camera)) {
            return;
        }
        float murkEnd = Config.FOG_DISTANCE.getAsInt();
        if (RenderSystem.getShaderFogEnd() <= murkEnd) {
            return; // something already ends the view sooner; leave it be
        }
        float[] c = Config.planeColor();
        RenderSystem.setShaderFogStart(murkEnd * 0.25F);
        RenderSystem.setShaderFogEnd(murkEnd);
        RenderSystem.setShaderFogColor(c[0], c[1], c[2], 1.0F);
    }

    // True when the camera should get our thick fog: the fogged side of the boundary, and not inside a
    // liquid. flipFog moves the fogged side to the one above.
    //
    // A liquid keeps its own fog. The murk is cut out of liquids entirely (see WaterlineMap's mask and
    // the fog_plane shader), so a lake or lava pool the boundary passes through must not be tinted or
    // shortened by it either -- otherwise the water you are swimming in disagrees with the water you
    // are looking at.
    private static boolean belowBoundary(Camera cam) {
        var level = Minecraft.getInstance().level;
        if (level == null || cam.getFluidInCamera() != FogType.NONE) {
            return false;
        }
        return Config.fogged(level, cam.getPosition().y);
    }
}
