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

// Skip the vanilla rain/snow pass while the camera is on the fogged side of the breathing boundary,
// so weather does not streak through the murk under the separation plane. The plane writes depth and
// hides weather that falls on the far side of it, but rain between the camera and the plane (i.e.
// inside the murk when you are below) is in front of it and would otherwise still render.
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // Skip the whole sky pass (sky gradient, sun, moon, stars) while on the fogged side, so none of it
    // shows through the murk under the plane -- e.g. at the faded plane rim or past its edge. The
    // framebuffer is already cleared to the fog colour, so cancelling leaves a uniform murk background.
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void fogged$skipSkyInMurk(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                      Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camera.getPosition().y)) {
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void fogged$skipWeatherInMurk(LightTexture lightTexture, float partialTick,
                                          double camX, double camY, double camZ, CallbackInfo ci) {
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camY)) {
            ci.cancel();
        }
    }

    // tickRain spawns the rain-splash particles on the ground around the camera; suppress them in the
    // murk too, otherwise splashes keep popping under the plane even with the weather pass cancelled.
    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true)
    private void fogged$skipRainSplashesInMurk(Camera camera, CallbackInfo ci) {
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camera.getPosition().y)) {
            ci.cancel();
        }
    }
}
