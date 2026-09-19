package com.fogged.mixin;

import com.fogged.Config;
import com.fogged.FogModifier;
import com.fogged.FogPlaneRenderer;
import com.fogged.MixinHealthCheck;

import com.mojang.blaze3d.systems.RenderSystem;

import org.lwjgl.opengl.GL11;

import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.LevelReader;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
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
        // The frame was cleared just before this and nothing has touched the clear value since; the
        // plane fogs into this colour on the dry side (FogPlaneRenderer.frameClearColor).
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, FogPlaneRenderer.frameClearColor);
        FogPlaneRenderer.frameClearValid = true;
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camera.getPosition().y)) {
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
        if (level != null && Config.fogged(level, camY)) {
            ci.cancel();
        }
    }

    // tickRain spawns the rain-splash particles on the ground around the camera; suppress them in the
    // murk too, otherwise splashes keep popping under the plane even with the weather pass cancelled.
    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$skipRainSplashesInMurk(Camera camera, CallbackInfo ci) {
        MixinHealthCheck.rainTickFired = true;
        Level level = Minecraft.getInstance().level;
        if (level != null && Config.fogged(level, camera.getPosition().y)) {
            ci.cancel();
        }
    }

    // Land the rain splashes on the murk instead of under it.
    //
    // tickRain finds the ground with the heightmap, which knows nothing of the separation plane -- so
    // on a flooded landscape it reports the sea floor and vanilla puts its splashes down there, beneath
    // a surface the rain never reaches through. The HEAD injector above only helps while the camera is
    // itself in the murk; stood on the surface looking out, the splashes were still going on below it.
    //
    // Raising the answer to the plane puts them where the rain actually lands. Only raises: ground
    // standing out of the murk keeps its own splashes.
    //
    // ordinal 0 on purpose. tickRain asks the heightmap twice -- once for where to put a splash, and
    // once, further down, to decide whether the rain sound should be the muffled "from above" one.
    // Only the first is this method's business; how the rain sounds under the murk is a question worth
    // answering on its own terms, not by a side effect of moving splashes.
    @Redirect(method = "tickRain",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/LevelReader;getHeightmapPos"
                            + "(Lnet/minecraft/world/level/levelgen/Heightmap$Types;"
                            + "Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
                    ordinal = 0),
            require = 0)
    private BlockPos fogged$splashOnTheMurk(LevelReader reader, Heightmap.Types types, BlockPos pos) {
        BlockPos found = reader.getHeightmapPos(types, pos);
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return found;
        }
        int plane = Config.planeSurfaceY(level);
        return plane != Integer.MIN_VALUE && found.getY() < plane
                ? new BlockPos(found.getX(), plane, found.getZ())
                : found;
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
        if (level != null && player != null && Config.fogged(level, player.getEyeY())) {
            ci.cancel();
        }
    }

    // Re-assert the murk fog at the head of every terrain layer draw.
    //
    // NeoForge's fog event fires inside FogRenderer.setupFog, so a mod injecting at that method's
    // RETURN -- Distant Horizons does, to suppress vanilla fog -- overwrites the result and the murk
    // disappears. There is no render stage between the terrain fog setup and this call, so this is the
    // last place the fog can be claimed before the world is drawn with it.
    //
    // Cheap: five calls a frame, each of which usually decides it has nothing to do.
    @Inject(method = "renderSectionLayer", at = @At("HEAD"), require = 0)
    private void fogged$enforceMurkFog(net.minecraft.client.renderer.RenderType renderType, double camX,
                                       double camY, double camZ, Matrix4f frustumMatrix,
                                       Matrix4f projectionMatrix, CallbackInfo ci) {
        MixinHealthCheck.terrainFogFired = true;
        FogModifier.enforceMurkFog();
        FogModifier.sampleTerrainFog("vanilla");
    }
}
