package com.fogged.mixin;

import com.fogged.FogSnuff;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.SignalGetter;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Cuts the redstone input of the devices the murk has drowned (see FogSnuff), so a redstone-fed one
// stays off instead of firing back up the moment its block entity next looks at the wire. Only the two
// "what is reaching this block" queries are intercepted, and only for a listed device on the murk side:
// the wire keeps its power and the device's own settings are untouched.
//
// SignalGetter holds these as interface defaults, so this mixes into the interface rather than Level.
@Mixin(SignalGetter.class)
public interface SignalGetterMixin {

    @Inject(method = "hasNeighborSignal", at = @At("HEAD"), cancellable = true)
    private void fogged$cutNeighborSignal(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (FogSnuff.isolated((SignalGetter) this, pos)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getBestNeighborSignal", at = @At("HEAD"), cancellable = true)
    private void fogged$cutBestNeighborSignal(BlockPos pos, CallbackInfoReturnable<Integer> cir) {
        if (FogSnuff.isolated((SignalGetter) this, pos)) {
            cir.setReturnValue(0);
        }
    }
}
