package com.fogged.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.fogged.MurkLayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Tells the murk layer about any block that changes in it, however it changed -- placed, broken,
 * pushed by a piston, flowed into, set by a command. There is no event that catches all of those; this
 * is the one place they all pass. A cell opened to air fills with murk again, and the murk round a
 * block set into it is redrawn.
 */
@Mixin(LevelChunk.class)
abstract class LevelChunkMixin {

    @Shadow
    @Final
    Level level;

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void fogged$wakeMurkLayer(BlockPos pos, BlockState state, boolean moving,
            CallbackInfoReturnable<BlockState> cir) {
        if (cir.getReturnValue() != null && level instanceof ServerLevel server
                && server.getServer().isSameThread()) {
            MurkLayer.onBlockChanged(server, pos, cir.getReturnValue(), state);
        }
    }
}
