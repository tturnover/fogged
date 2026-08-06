package com.fogged.block;

import com.fogged.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/** Root-anchor data for a {@link FogEyeStemBlock}. See {@link FogEyeAnchorBlockEntity}. */
public class FogEyeStemBlockEntity extends FogEyeAnchorBlockEntity {
    public FogEyeStemBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FOG_EYE_STEM.get(), pos, state);
    }
}
