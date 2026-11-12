package com.fogged.create;

import java.util.List;

import com.fogged.block.AirCompressorIntakeBlock;
import com.fogged.block.AirCompressorIntakeBlockEntity;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/** The intake as Create's goggles see it: its compressor's readout. Only made with Create installed. */
public class GoggledIntakeBlockEntity extends AirCompressorIntakeBlockEntity implements IHaveGoggleInformation {

    public GoggledIntakeBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (level == null) {
            return false;
        }
        BlockPos compressor = AirCompressorIntakeBlock.compressorPos(worldPosition, getBlockState());
        return level.getBlockEntity(compressor) instanceof IHaveGoggleInformation goggled
                && goggled.addToGoggleTooltip(tooltip, isPlayerSneaking);
    }
}
