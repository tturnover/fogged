package com.fogged.mixin.compat;

import com.fogged.Config;

import net.minecraft.client.multiplayer.ClientLevel;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Tells Particle Rain that the murk's surface is the ground.
 *
 * <p>Everything it does with weather is worked out from one number: the height of the surface in a
 * column. Where rain and mist are spawned, whether a spot counts as having sky above it, where the
 * weather sounds come from -- all of it asks this. The separation plane is not made of blocks, so that
 * number goes straight past it to the sea floor, and the rain follows.
 *
 * <p>Raising the answer to the plane is a smaller and far more faithful fix than deleting the
 * particles afterwards: rain is never spawned under the murk in the first place, rather than being
 * spawned and taken away again a tick later. The removal in {@link ParticleRainWeatherMixin} stays as
 * the backstop for anything that drifts down past it.
 *
 * <p>Lives in {@code fogged.particlerain.mixins.json}, which is not required: with Particle Rain
 * absent the target class is never loaded and this never applies.
 */
@Mixin(targets = "pigcart.particlerain.ParticleSpawner", remap = false)
public class ParticleRainHeightMixin {

    @Inject(method = "getHeight", at = @At("RETURN"), cancellable = true, require = 0)
    private static void fogged$surfaceIsThePlane(ClientLevel level, int x, int z,
            CallbackInfoReturnable<Integer> cir) {
        int plane = Config.planeSurfaceY(level);
        // Only raises it. Ground standing out of the murk is still the ground, and reporting the plane
        // there would put rain through the top of anything that breaks the surface.
        if (plane != Integer.MIN_VALUE && cir.getReturnValueI() < plane) {
            cir.setReturnValue(plane);
        }
    }
}
