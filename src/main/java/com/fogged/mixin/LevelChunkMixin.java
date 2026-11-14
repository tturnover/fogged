package com.fogged.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.fogged.VolumeFog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Wakes the volumetric fog round any block that changes, however it changed -- placed, broken, pushed
 * by a piston, flowed into, set by a command. There is no event that catches all of those; this is the
 * one place they all pass. A solid block set into fog pushes it out, and an opened wall lets it through.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void fogged$wakeVolumeFog(BlockPos pos, BlockState state, boolean moving,
            CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && level instanceof ServerLevel server
                && server.getServer().isSameThread()) {
            VolumeFog.onBlockChanged(server, pos, cir.getReturnValue(), state);
        }
    }
}
