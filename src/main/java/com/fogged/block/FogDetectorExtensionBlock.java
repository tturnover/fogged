package com.fogged.block;

import com.mojang.serialization.MapCodec;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Stackable extension segment of a fog detector tower. Not obtainable as an item — it is created by
 * right-clicking a {@link FogDetectorBlock} (or an extension above it) with a {@code fog_detector}
 * item, and it drops a {@code fog_detector} when broken (see the block loot).
 *
 * <ul>
 *   <li>Survives only while supported from below by a detector or another extension; otherwise pops
 *       off and drops — so breaking the base cascades the whole column down.</li>
 *   <li>Facing/vertical orientation is copied from the segment below it when placed.</li>
 * </ul>
 */
public class FogDetectorExtensionBlock extends FogDetectorBlock {
    public static final MapCodec<FogDetectorExtensionBlock> CODEC = simpleCodec(FogDetectorExtensionBlock::new);

    // Extension model is the single tall antenna (pixels); it rises above the block top (y up to 19),
    // so the shape overhangs the block -- Block.box yields an ArrayVoxelShape for the out-of-cell coords.
    private static final VoxelShape EXTENSION_SHAPE = Shapes.or(
            Block.box(6.5, 3.0, 4.0, 9.5, 19.0, 12.0));

    public FogDetectorExtensionBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected VoxelShape baseShape() {
        return EXTENSION_SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        // Supported by the previous segment toward the base (opposite the stacking direction).
        Direction toward = state.getValue(VERTICAL_DIRECTION).getOpposite();
        return level.getBlockState(pos.relative(toward)).getBlock() instanceof FogDetectorBlock;
    }
    // updateShape (pop-off + waterlog) is inherited from FogDetectorBlock and uses this canSurvive.

    // Extensions are not signal sources and carry no block entity (only the base does).
    @Override
    @Nullable
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return null;
    }

    @Override
    @Nullable
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        return null;
    }
}
