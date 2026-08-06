package com.fogged.block;

import com.fogged.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Root-anchor data for a {@link FogEyeBlock}. See {@link FogEyeAnchorBlockEntity}. */
public class FogEyeBlockEntity extends FogEyeAnchorBlockEntity {
    public FogEyeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FOG_EYE.get(), pos, state);
    }
}
