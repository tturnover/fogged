package com.fogged;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
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

    // True when the camera should get our thick fog: the fogged side of the boundary. Normally that is
    // below it (also winning while submerged in real water); flipFog moves the fog to the side above.
    private static boolean belowBoundary(Camera cam) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        return Config.fogged(level, cam.getPosition().y);
    }
}
