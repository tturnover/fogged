package com.fogged.block;

import com.fogged.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The intake's block entity. It keeps nothing: it is there because Create's goggles only read blocks
 * that have one, and the intake is as much the compressor as the compressor is. With Create installed
 * it is the kind that hands the goggles over to its compressor (com.fogged.create).
 */
public class AirCompressorIntakeBlockEntity extends BlockEntity {

    public AirCompressorIntakeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.AIR_COMPRESSOR_INTAKE.get(), pos, state);
    }

    /** A new intake block entity: the goggled kind with Create installed. */
    public static AirCompressorIntakeBlockEntity create(BlockPos pos, BlockState state) {
        return CREATE ? com.fogged.create.CreateContent.goggledIntake(pos, state) : new AirCompressorIntakeBlockEntity(pos, state);
    }

    private static final boolean CREATE = net.neoforged.fml.ModList.get().isLoaded("create");
}
