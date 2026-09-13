package com.fogged.mixin.compat;

import com.fogged.FogSnuff;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Smothers a Cold Sweat hearth the murk holds: its tick does not run, so it neither warms anything
 * nor burns anything, and keeps every bit of fuel it had for when the boundary lifts and the tick
 * resumes -- the hearth's own state sync then lights it again on its own.
 *
 * <p>Smothered rather than drained on purpose. A hearth's fuel is a number, not an item, and the
 * first version of this zeroed it: then whatever coal was put in was turned into that number by the
 * hearth's own tick and zeroed again at the next sweep, and the coal was simply gone, with nothing
 * to show for it. Holding the tick instead leaves the number alone, and coal put in while smothered
 * goes into the tank as it normally would, to burn once the murk has gone.
 *
 * <p>The boiler and the icebox extend the hearth; the boiler has a tick of its own that this does not
 * see (see ColdSweatBoilerMixin), and the icebox is not a listed device, so nothing here touches it.
 *
 * <p>Lives in {@code fogged.coldsweat.mixins.json}, which is not required: with the mod absent the
 * target class is never loaded and this never applies.
 */
@Mixin(targets = "com.momosoftworks.coldsweat.common.blockentity.HearthBlockEntity", remap = false)
public abstract class ColdSweatHearthMixin {

    @Inject(method = "tick(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void fogged$smother(CallbackInfo ci) {
        BlockEntity be = (BlockEntity) (Object) this;
        if (be.getLevel() != null && FogSnuff.snuffedAt(be.getLevel(), be.getBlockPos())) {
            ci.cancel();
        }
    }
}
