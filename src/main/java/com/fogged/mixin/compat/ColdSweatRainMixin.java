package com.fogged.mixin.compat;

import com.fogged.Config;
import com.fogged.PlaneSensor;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * No rain under the murk, as far as Cold Sweat's wetness goes. The murk is a roof: rain is already
 * stopped at its surface where it is drawn (LevelRendererMixin, ParticleRainWeatherMixin), but Cold
 * Sweat asks the weather itself whether it is raining on a player, and the sky over a player in the
 * murk is open to that, so they were soaked by rain that never reached them. Every one of its rain
 * checks -- whether to wet a player, and the wet modifier's own -- goes through this one helper.
 *
 * <p>Lives in {@code fogged.coldsweat.mixins.json}, which is not required: with the mod absent the
 * target class is never loaded and this never applies.
 */
@Mixin(targets = "com.momosoftworks.coldsweat.util.world.WorldHelper", remap = false)
public abstract class ColdSweatRainMixin {

    @Inject(method = "isRainingAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void fogged$noRainUnderMurk(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (level != null && pos != null && Config.fogged(level, PlaneSensor.worldY(level, pos))) {
            cir.setReturnValue(false);
        }
    }
}
