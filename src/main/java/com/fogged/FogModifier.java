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

    // What each of the two paths below decided last frame, for the debug HUD. They can disagree, and
    // when the murk fog fails to appear which one ran -- and what distance it was handed -- is the
    // whole question, so both are reported rather than recomputed by the HUD.
    private static volatile String eventStatus = "not run";
    private static volatile String enforceStatus = "not run";

    /** What the fog EVENT did last frame. */
    public static String eventStatus() {
        return eventStatus;
    }

    /** What the terrain-pass enforcement did last frame. */
    public static String enforceStatus() {
        return enforceStatus;
    }

    // LOW priority: run after any other mod's own RenderFog listener, so the min-compose below sees
    // whatever distance that mod already computed rather than a stale vanilla default.
    @SubscribeEvent(priority = EventPriority.LOW)
    static void onRenderFog(ViewportEvent.RenderFog event) {
        Camera cam = event.getCamera();
        if (!murkSide(cam)) {
            eventStatus = "off (dry side)";
            return;
        }
        // On the fogged side the murk OWNS the fog -- distance and colour both. This used to compose,
        // taking whichever was tighter and leaving another mod's fog alone otherwise, which reads as
        // politeness but is wrong here: the murk is the whole point of being under the boundary, and a
        // biome fog that happens to be shorter left the world under the plane fogged in that biome's
        // colour with the murk nowhere in it. Above the boundary every other mod's fog is untouched.
        float far = Config.FOG_DISTANCE.getAsInt();
        // ...except inside a liquid, where it only ever SHORTENS the view and never recolours it (see
        // liquidEye). A liquid already denser than the murk keeps its own fog untouched.
        if (liquidEye(cam)) {
            if (event.getFarPlaneDistance() <= far) {
                eventStatus = String.format("%s fog %.0f already tighter than murk %.0f",
                        cam.getFluidInCamera(), event.getFarPlaneDistance(), far);
                return;
            }
            eventStatus = String.format("%s %.0f -> murk %.0f (distance only)",
                    cam.getFluidInCamera(), event.getFarPlaneDistance(), far);
        } else {
            eventStatus = String.format("murk %.0f + colour", far);
        }
        event.setNearPlaneDistance(far * 0.25F);
        event.setFarPlaneDistance(far);
        // Cancelling is what makes NeoForge apply these at all -- see ClientHooks.onFogRender, which
        // only writes the event's values back when the event was cancelled.
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onComputeFogColor(ViewportEvent.ComputeFogColor event) {
        // Colour, unlike distance, stays off inside a liquid: tinting the water the player is swimming
        // in murk-green makes it disagree with the same water seen from outside.
        if (!murkSide(event.getCamera()) || liquidEye(event.getCamera())) {
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
        if (mc.level == null || !murkSide(camera)) {
            enforceStatus = "off (dry side)";
            return;
        }
        // Unconditionally, for the same reason the event listener above is unconditional. The earlier
        // version bailed out here whenever something else had already set a shorter fog -- and since
        // that check came first, it skipped the COLOUR too, so under a biome fog tighter than the murk
        // the world kept that biome's colour and the murk was neither hiding the plane nor tinting it.
        float murkEnd = Config.FOG_DISTANCE.getAsInt();
        // Same split the event listener makes: in a liquid the murk only tightens the distance and
        // leaves the liquid's own colour alone.
        if (liquidEye(camera)) {
            float live = RenderSystem.getShaderFogEnd();
            if (live > murkEnd) {
                RenderSystem.setShaderFogStart(murkEnd * 0.25F);
                RenderSystem.setShaderFogEnd(murkEnd);
                enforceStatus = String.format("%s: shader %.0f -> murk %.0f", camera.getFluidInCamera(), live, murkEnd);
            } else {
                enforceStatus = String.format("%s: shader %.0f already tighter", camera.getFluidInCamera(), live);
            }
            return;
        }
        enforceStatus = String.format("murk %.0f + colour (shader was %.0f)", murkEnd, RenderSystem.getShaderFogEnd());
        float[] c = Config.planeColor();
        RenderSystem.setShaderFogStart(murkEnd * 0.25F);
        RenderSystem.setShaderFogEnd(murkEnd);
        RenderSystem.setShaderFogColor(c[0], c[1], c[2], 1.0F);
    }

    // True when the camera is on the fogged side of the boundary. Says nothing about what it is
    // standing or swimming in -- that is liquidEye's job. flipFog moves the fogged side to the one above.
    private static boolean murkSide(Camera cam) {
        var level = Minecraft.getInstance().level;
        return level != null && Config.fogged(level, cam.getPosition().y);
    }

    // True when the eye is inside a liquid, where the murk tightens the fog distance but leaves the
    // colour to the liquid.
    //
    // Being in a liquid used to switch the murk fog off outright, on the grounds that a liquid keeps
    // its own fog: the murk is cut out of liquids (see WaterlineMap's mask and the fog_plane shader),
    // so a lake the boundary passes through should not be tinted or shortened by it. That is right at
    // the boundary and badly wrong below it. Dive under the plane and vanilla's water fog is the only
    // thing left limiting the view -- which at any normal render distance limits nothing -- so the
    // whole world under the murk was visible through the water, plane included. Water was a hole in
    // the murk. The distance now still applies; only the colour is left to the liquid, which is what
    // kept the two disagreeing before.
    private static boolean liquidEye(Camera cam) {
        return cam.getFluidInCamera() != FogType.NONE;
    }
}
