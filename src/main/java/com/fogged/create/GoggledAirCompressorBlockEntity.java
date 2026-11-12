package com.fogged.create;

import java.util.List;

import com.fogged.block.AirCompressorBlockEntity;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The FE air compressor as Create's goggles see it: its air and its FE. Only made with Create
 * installed (see {@link AirCompressorBlockEntity#create}), so the plain one never names Create.
 */
public class GoggledAirCompressorBlockEntity extends AirCompressorBlockEntity implements IHaveGoggleInformation {

    public GoggledAirCompressorBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        goggleLines(tooltip);
        return true;
    }
}
