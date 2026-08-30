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
        // On the fogged side the murk OWNS the fog -- distance and colour both. This used to compose,
        // taking whichever was tighter and leaving another mod's fog alone otherwise, which reads as
        // politeness but is wrong here: the murk is the whole point of being under the boundary, and a
        // biome fog that happens to be shorter left the world under the plane fogged in that biome's
        // colour with the murk nowhere in it. Above the boundary every other mod's fog is untouched.
        float far = Config.FOG_DISTANCE.getAsInt();
        event.setNearPlaneDistance(far * 0.25F);
        event.setFarPlaneDistance(far);
        // Cancelling is what makes NeoForge apply these at all -- see ClientHooks.onFogRender, which
        // only writes the event's values back when the event was cancelled.
        event.setCanceled(true);
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
     * <p>Overrides rather than composes, matching the event listener: in the murk this fog is the
     * effect, not a suggestion.
     */
    public static void enforceMurkFog() {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = mc.gameRenderer.getMainCamera();
        if (mc.level == null || !belowBoundary(camera)) {
            return;
        }
        // Unconditionally, for the same reason the event listener above is unconditional. The earlier
        // version bailed out here whenever something else had already set a shorter fog -- and since
        // that check came first, it skipped the COLOUR too, so under a biome fog tighter than the murk
        // the world kept that biome's colour and the murk was neither hiding the plane nor tinting it.
        float murkEnd = Config.FOG_DISTANCE.getAsInt();
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
