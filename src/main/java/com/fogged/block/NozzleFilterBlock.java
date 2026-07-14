package com.fogged.block;

import com.fogged.CreateFan;
import com.fogged.NozzleFilterEvents;
import com.fogged.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

/**
 * The block a Create nozzle becomes once 3 fluff balls are applied to it (see
 * {@code com.fogged.NozzleFilterEvents}). It is never placed from an item -- it only ever replaces an
 * in-world {@code create:nozzle}, inheriting that nozzle's {@code FACING} so it points away from the
 * fan it is attached to (the fan therefore sits on the {@code FACING.getOpposite()} side).
 *
 * <p>Its {@link NozzleFilterBlockEntity} reads the attached fan's speed each tick and opens a breathing
 * sphere around itself, so the block must genuinely replace the nozzle rather than sit alongside it --
 * that removes Create's own nozzle behaviour and avoids two air sources fighting over the same spot.
 */
public class NozzleFilterBlock extends BaseEntityBlock {
    public static final MapCodec<NozzleFilterBlock> CODEC = simpleCodec(NozzleFilterBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public NozzleFilterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** The block carries its own model (BlockBench-style), so render it as a normal model, not a BE renderer. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // --- Attachment: the filter rides on its fan, so it pops off if that fan is broken ---------------

    /**
     * Survives only while an Encased Fan sits on the attachment side (opposite the filter's FACING) AND
     * still faces the filter. The nozzle points away from its fan, so an aligned fan's own facing equals
     * the filter's FACING; rotate the fan away and the filter pops off.
     */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!CreateFan.installed()) {
            return true; // no Create to validate against: leave the block be
        }
        Direction facing = state.getValue(FACING);
        return CreateFan.fanFacing(level, pos.relative(facing.getOpposite())) == facing;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!canSurvive(state, level, pos)) {
            // Fan gone -> pop off. The fluff comes back via the loot table; drop the nozzle here too.
            if (level instanceof Level lvl) {
                NozzleFilterEvents.dropNozzle(lvl, pos);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NozzleFilterBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (type != ModBlockEntities.NOZZLE_FILTER.get()) {
            return null;
        }
        if (level.isClientSide) {
            // Client: spawn the air particles the nozzle used to make around itself.
            return (lvl, pos, st, be) -> NozzleFilterBlockEntity.clientTick(lvl, pos, st, (NozzleFilterBlockEntity) be);
        }
        return (lvl, pos, st, be) -> NozzleFilterBlockEntity.serverTick(lvl, pos, st, (NozzleFilterBlockEntity) be);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
}
