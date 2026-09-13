package com.fogged.mixin.compat;

import com.fogged.FogSnuff;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * The boiler's half of ColdSweatHearthMixin. A boiler is a hearth, and its tick starts by running the
 * hearth's -- which the hearth mixin already holds -- but goes on to work of its own afterwards, so
 * that tick is held at its head too.
 */
@Mixin(targets = "com.momosoftworks.coldsweat.common.blockentity.BoilerBlockEntity", remap = false)
public abstract class ColdSweatBoilerMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/level/block/state/BlockState;"
            + "Lnet/minecraft/core/BlockPos;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private void fogged$smother(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        if (be.getLevel() != null && FogSnuff.snuffedAt(be.getLevel(), be.getBlockPos())) {
            ci.cancel();
        }
    }
}
