package com.fogged.mixin.compat;

import com.fogged.Config;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Particle Rain's weather at the separation plane, the way vanilla's own weather already stops
 * there.
 *
 * <p>Vanilla needs no help: {@code LevelRendererMixin} drops the whole snow-and-rain pass on the
 * fogged side, and from above the plane writes depth and simply hides what falls behind it. Particle
 * Rain does not render rain as that pass at all -- it spawns real particles that fall under their own
 * gravity, in the particle pass -- so neither of those applies, and its rain sails straight down
 * through the murk surface to the sea floor.
 *
 * <p>This removes such a particle the moment it crosses onto the murk side, so rain, snow, mist and
 * spray all stop dead at the plane. Every one of Particle Rain's weather particles extends the class
 * targeted here, so one hook covers the lot.
 *
 * <p>Lives in {@code fogged.particlerain.mixins.json}, which is not required: with Particle Rain
 * absent the target class is never loaded and this never applies.
 */
@Mixin(targets = "pigcart.particlerain.particle.WeatherParticle", remap = false)
public abstract class ParticleRainWeatherMixin extends Particle {

    protected ParticleRainWeatherMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0)
    private void fogged$stopAtPlane(CallbackInfo ci) {
        // Only while the plane is actually drawn: with nothing there to land on, weather cutting out
        // in mid-air would read as a bug rather than as rain hitting a surface.
        if (!Config.RENDER_PLANE.getAsBoolean()) {
            return;
        }
        if (Config.fogged(this.level, this.y)) {
            remove();
            ci.cancel();
        }
    }
}
