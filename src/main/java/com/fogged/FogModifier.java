package com.fogged;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;

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

    // LOW priority: run after any other mod's own RenderFog listener, so what we override is whatever
    // that mod already computed rather than a stale vanilla default.
    @SubscribeEvent(priority = EventPriority.LOW)
    static void onRenderFog(ViewportEvent.RenderFog event) {
        if (!belowBoundary(event.getCamera())) {
            return;
        }
        // On the fogged side the murk OWNS the fog -- distance and colour both. This used to compose,
        // taking whichever was tighter and leaving another mod's fog alone otherwise, which reads as
        // politeness but is wrong here: the murk is the whole point of being under the boundary, and a
        // biome fog that happens to be shorter (IMB11's Fog does this) left the world under the plane
        // fogged in that biome's colour with the murk nowhere in it. Above the boundary every other
        // mod's fog is untouched.
        float far = Config.FOG_DISTANCE.getAsInt();
        event.setNearPlaneDistance(far * 0.25F);
        event.setFarPlaneDistance(far);
        // The shape too, and SPHERE on purpose. Vanilla hands the terrain fog CYLINDER
        // (FogRenderer.setupFog), whose distance is max(length(xz), abs(y)) -- so a line of sight
        // running down at 45 degrees only reaches fogDistance after 1.41 * fogDistance of actual
        // travel, and the murk ends up further away looking down than looking level. The screen-space
        // pass measures plain length() (murk_composite.fsh), so with a cylinder here the two halves of
        // the murk disagree on its shape: a sphere under a shader pack, a cylinder without one.
        // A sphere is also what "fogDistance blocks of murk in every direction" means.
        event.setFogShape(FogShape.SPHERE);
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
     * Force the murk fog onto the shader uniforms while the camera is in the murk. Called at the head
     * of the terrain pass (see LevelRendererMixin) as well as through the ordinary fog event.
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
        if (mc.level == null || !belowBoundary(mc.gameRenderer.getMainCamera())) {
            return;
        }
        float murkEnd = Config.FOG_DISTANCE.getAsInt();
        float[] c = Config.planeColor();
        RenderSystem.setShaderFogStart(murkEnd * 0.25F);
        RenderSystem.setShaderFogEnd(murkEnd);
        RenderSystem.setShaderFogShape(FogShape.SPHERE); // as the event sets it; see there for why
        RenderSystem.setShaderFogColor(c[0], c[1], c[2], 1.0F);
    }

    // The fog state as it stood at the last terrain draw, for the debug HUD -- read back off
    // RenderSystem rather than recomputed, so what shows is what the terrain shaders were actually
    // handed. A murk that reaches further than fogDistance has to be one of two things: this saying
    // something other than fogDistance (someone overwrote the fog, or the hook that re-asserts it
    // never ran), or this saying fogDistance and the murk still reaching further, which puts it in the
    // screen-space pass instead. Sampled at the draw because there is no later point where the value
    // is still the terrain's.
    static volatile float lastStart = -1.0F;
    static volatile float lastEnd = -1.0F;
    static volatile int lastShape = -1;
    /** Which terrain path sampled it: "vanilla", "sodium", or null when neither hook has run. */
    static volatile String lastPath;

    /** Record what the terrain shaders are about to fog with. Called from the terrain-pass hooks. */
    public static void sampleTerrainFog(String path) {
        lastStart = RenderSystem.getShaderFogStart();
        lastEnd = RenderSystem.getShaderFogEnd();
        lastShape = RenderSystem.getShaderFogShape().getIndex();
        lastPath = path;
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
