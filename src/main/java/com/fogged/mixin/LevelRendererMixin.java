package com.fogged.mixin;

import com.fogged.Config;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Skip the vanilla sky/rain/cloud passes while the camera is on the fogged side of the breathing
// boundary, so none of them show through the murk under the separation plane. The plane writes depth
// and hides things behind it, but sky/weather/clouds rendered between the camera and the plane (i.e.
// inside the murk when you are below) are in front of it and would otherwise still render.
//
// Every injector below is require=0 (unlike this mod's other mixins, which keep the file-wide
// defaultRequire=1): a renderer that replaces/refactors LevelRenderer (a common thing for chunk-
// rendering-replacement mods to do) could rename or restructure any of these methods, and this mod
// should degrade to "sky/rain/clouds occasionally visible through the fog" rather than fail to load
// at all. FogModifier's short, dense murk fog is the deliberate visual backstop for that case -- see
// its comment. MixinHealthCheck does a best-effort check of whether each injector actually ran.
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // Set unconditionally the moment each injected method actually runs, regardless of whether the
    // fogged check below ends up cancelling it -- proof the injection applied and the vanilla hook is
    // still being invoked by whatever renderer is active. Read by MixinHealthCheck.
    public static volatile boolean skyFired = false;
    public static volatile boolean weatherFired = false;
    public static volatile boolean rainTickFired = false;
    public static volatile boolean cloudsFired = false;

    // Skip the whole sky pass (sky gradient, sun, moon, stars) while on the fogged side, so none of it
    // shows through the murk under the plane -- e.g. at the faded plane rim or past its edge. The
    // framebuffer is already cleared to the fog colour, so cancelling leaves a uniform murk background.
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipSkyInMurk(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                      Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        skyFired = true;
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camera.getPosition().y)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipWeatherInMurk(LightTexture lightTexture, float partialTick,
                                          double camX, double camY, double camZ, CallbackInfo ci) {
        weatherFired = true;
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camY)) {
            ci.cancel();
        }
    }

    // tickRain spawns the rain-splash particles on the ground around the camera; suppress them in the
    // murk too, otherwise splashes keep popping under the plane even with the weather pass cancelled.
    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipRainSplashesInMurk(Camera camera, CallbackInfo ci) {
        rainTickFired = true;
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camera.getPosition().y)) {
            ci.cancel();
        }
    }

    // Hide clouds while submerged: they sit far above the boundary and would otherwise shine through the
    // plane's faded rim, breaking the "opaque ceiling" read. Cancelling the render (rather than toggling
    // the player's live Clouds option, as an earlier version of this mod did in FogModifier) never
    // touches user settings and needs no restore-on-exit handling. Only CallbackInfo is captured (not
    // renderClouds' own parameters) since this mixin doesn't need them and it keeps the injector valid
    // across any signature NeoForge/vanilla might use for this method. Uses the local player's eye
    // height as a stand-in for the camera position (close enough for this check; same pattern already
    // used by BreathHandler/MobSuppressor) rather than reaching for the render camera from a mixin.
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipCloudsInMurk(CallbackInfo ci) {
        cloudsFired = true;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        var player = mc.player;
        if (level != null && player != null && Config.fogged(level, player.getEyeY())) {
            ci.cancel();
        }
    }
}
