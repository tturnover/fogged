package com.fogged;

import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

// Replaces the world fog with a short, dense fog whenever the camera is below the breathing
// boundary, so being "under" the plane looks like a low render distance (underwater-ish).
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public class FogModifier {

    @SubscribeEvent
    static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!belowBoundary(event.getCamera())) {
            return;
        }
        float far = Config.FOG_DISTANCE.getAsInt();
        event.setNearPlaneDistance(far * 0.25F);
        event.setFarPlaneDistance(far);
        event.setCanceled(true); // use our distances instead of the vanilla ones
    }

    @SubscribeEvent
    static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        boolean below = belowBoundary(event.getCamera());
        // Clouds sit far above the boundary and shine bright through the plane's faded rim; hide them
        // while submerged so the surface reads as an opaque ceiling, then restore the player's choice.
        manageClouds(below);
        if (!below) {
            return;
        }
        // Match the fog to the separation plane's colour so passing under it feels continuous.
        float[] c = Config.planeColor();
        event.setRed(c[0]);
        event.setGreen(c[1]);
        event.setBlue(c[2]);
    }

    // Clouds option we temporarily forced OFF (null = we are not currently overriding it).
    private static CloudStatus savedClouds = null;

    private static void manageClouds(boolean below) {
        var clouds = Minecraft.getInstance().options.cloudStatus();
        if (below) {
            if (savedClouds == null && clouds.get() != CloudStatus.OFF) {
                savedClouds = clouds.get();
                clouds.set(CloudStatus.OFF);
            }
        } else if (savedClouds != null) {
            clouds.set(savedClouds);
            savedClouds = null;
        }
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
