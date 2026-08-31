package com.fogged.mixin;

import com.fogged.Config;
import com.fogged.WaterlineMap;
import com.fogged.FogModifier;
import com.fogged.MixinHealthCheck;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
// its comment. Each injector reports that it ran to MixinHealthCheck.{flag} rather than a field
// declared here: Mixin merges this class's members directly into LevelRenderer at apply time, and its
// validator rejects any new non-private static field a mixin tries to add to the target class -- the
// flags have to live in an ordinary class instead. MixinHealthCheck does a best-effort check of
// whether each injector actually ran.
@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    // Skip the whole sky pass (sky gradient, sun, moon, stars) while on the fogged side, so none of it
    // shows through the murk under the plane -- e.g. at the faded plane rim or past its edge. The
    // framebuffer is already cleared to the fog colour, so cancelling leaves a uniform murk background.
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipSkyInMurk(Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick,
                                      Camera camera, boolean isFoggy, Runnable skyFogSetup, CallbackInfo ci) {
        MixinHealthCheck.skyFired = true;
        if (murk(camera)) {
            // Repaint the background to the murk colour before dropping the sky pass. Cancelling alone
            // used to be enough because the framebuffer had already been cleared to the fog colour --
            // but that clear colour comes out of FogRenderer.setupColor, and a mod injecting at its
            // RETURN owns it. The result was the horizon left in that mod's colour while everything
            // fogged came out murk: an empty hole above the terrain line. Nothing has been drawn yet
            // at this point in renderLevel, so only the colour buffer is touched and only here.
            float[] c = Config.planeColor();
            RenderSystem.clearColor(c[0], c[1], c[2], 1.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
            ci.cancel();
        }
    }

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipWeatherInMurk(LightTexture lightTexture, float partialTick,
                                          double camX, double camY, double camZ, CallbackInfo ci) {
        MixinHealthCheck.weatherFired = true;
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camY) && !Config.inFluid(level, camX, camY, camZ)) {
            ci.cancel();
        }
    }

    // tickRain spawns the rain-splash particles on the ground around the camera; suppress them in the
    // murk too, otherwise splashes keep popping under the plane even with the weather pass cancelled.
    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipRainSplashesInMurk(Camera camera, CallbackInfo ci) {
        MixinHealthCheck.rainTickFired = true;
        if (murk(camera)) {
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
        MixinHealthCheck.cloudsFired = true;
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        var player = mc.player;
        if (level != null && player != null && Config.fogged(level, player.getEyeY())
                && player.getEyeInFluidType().isAir()) {
            ci.cancel();
        }
    }

    // Re-assert the murk fog at the head of every terrain layer draw.
    //
    // NeoForge's fog event fires inside FogRenderer.setupFog, so a mod injecting at that method's
    // RETURN -- Distant Horizons does, to suppress vanilla fog -- overwrites the result and the murk
    // disappears. There is no render stage between the terrain fog setup and this call, so this is the
    // last place the fog can be claimed before the world is drawn with it. FogModifier only ever
    // shortens what is already there, so a mod with genuinely tighter fog still wins.
    //
    // Cheap: five calls a frame, each of which usually decides it has nothing to do.
    @Inject(method = "renderSectionLayer", at = @At("HEAD"), require = 0)
    private void fogged$enforceMurkFog(net.minecraft.client.renderer.RenderType renderType, double camX,
                                       double camY, double camZ, org.joml.Matrix4f frustumMatrix,
                                       org.joml.Matrix4f projectionMatrix, CallbackInfo ci) {
        MixinHealthCheck.terrainFogFired = true;
        FogModifier.enforceMurkFog();
    }

    // The client's only notice that a block changed: there is no event for it, and the waterline map
    // has to know or its foam keeps describing terrain that is no longer there. Both of these are the
    // whole reason the map can be event-driven at all rather than rescanning on a timer.
    @Inject(method = "blockChanged", at = @At("HEAD"), require = 0)
    private void fogged$blockChanged(net.minecraft.world.level.BlockGetter level,
                                     net.minecraft.core.BlockPos pos,
                                     net.minecraft.world.level.block.state.BlockState oldState,
                                     net.minecraft.world.level.block.state.BlockState newState,
                                     int flags, CallbackInfo ci) {
        MixinHealthCheck.blockChangeFired = true;
        WaterlineMap.markDirtyAt(pos.getX(), pos.getY(), pos.getZ());
    }

    // Chunks loading in, and edits large enough to rebuild a whole section, never reach blockChanged.
    @Inject(method = "setSectionDirty(III)V", at = @At("HEAD"), require = 0)
    private void fogged$sectionDirty(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        WaterlineMap.markSectionDirty(sectionX, sectionY, sectionZ);
    }

    // Whether the camera is in the murk itself -- on the fogged side AND not inside a liquid. A liquid
    // keeps its own fog and its own view of the sky (see FogModifier), so nothing is suppressed there.
    @Unique
    private static boolean murk(Camera camera) {
        Level level = Minecraft.getInstance().level;
        if (level == null || camera.getFluidInCamera() != FogType.NONE) {
            return false;
        }
        return Config.fogged(level, camera.getPosition().y);
    }
}
