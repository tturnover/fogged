package com.fogged.block;

import com.fogged.Fogged;
import com.fogged.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
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
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * An air compressor: a fan in a casing, set into the wall of a room. It draws air in at its back
 * ({@code FACING.getOpposite()}) from an {@link AirCompressorIntakeBlock} set right behind it or at the
 * far end of a duct run from there -- without one it does nothing -- and pushes it out of its outlet
 * ({@code FACING}) into the room, which it then holds airtight -- see
 * {@link AirCompressorBlockEntity} for the fill and {@link com.fogged.AirFill} for what seals.
 *
 * <p>Placed with the outlet toward whoever places it: you stand in the room and set it into the wall.
 * Compressors facing the same way side by side join into 2x2 and 3x3 units that hold proportionally
 * more room (see {@link AirCompressorCore#reform}).
 *
 * <p>The casing is a full cube, so it is part of the room's shell like any wall block.
 */
public class AirCompressorBlock extends BaseEntityBlock implements CompressorBlock {
    public static final MapCodec<AirCompressorBlock> CODEC = simpleCodec(AirCompressorBlock::new);
    /** The outlet: the face that pushes air into the room. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public AirCompressorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = placementFacing(context, this);
        return facing == null ? null : defaultBlockState().setValue(FACING, facing);
    }

    /**
     * Which way a compressor of block {@code self} placed in {@code context} faces.
     *
     * <p>Set against the side of another of the same compressor, it faces the way that one does, so a
     * wall of them joins into a unit however steeply the placer had to look to reach the top row.
     * Otherwise the outlet faces the placer. Sneaking opts out of the first and turns the second round,
     * for one set from outside.
     */
    public static Direction placementFacing(BlockPlaceContext context, Block self) {
        Player player = context.getPlayer();
        boolean sneaking = player != null && player.isSecondaryUseActive();
        Direction facing = null;
        if (!sneaking) {
            BlockState against = context.getLevel().getBlockState(
                    context.getClickedPos().relative(context.getClickedFace().getOpposite()));
            if (against.getBlock() == self && against.getValue(FACING).getAxis() != context.getClickedFace().getAxis()) {
                facing = against.getValue(FACING);
            }
        }
        if (facing == null) {
            Direction toPlacer = context.getNearestLookingDirection().getOpposite();
            facing = sneaking ? toPlacer.getOpposite() : toPlacer;
        }
        return facing;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /** A comparator reads the unit's intake filters' average pollution (AirCompressorCore#comparatorSignal). */
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return comparatorSignal(level, pos);
    }

    /** The comparator reading of the compressor at {@code pos}, either kind. */
    public static int comparatorSignal(Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof CompressorHost host ? host.core().comparatorSignal() : 0;
    }

    /** The block model shows the still fan; the block entity renderer spins it and spans a unit. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // --- Joining: every placement or removal re-forms the units around it ---------------------------

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        placed(this, state, level, pos, oldState);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        removed(this, state, level, pos, newState);
    }

    /**
     * A compressor of {@code self} was set down or turned at {@code pos}: re-form the units it left and
     * joined. A fresh placement joins from its core's onLoad instead -- this runs before its block
     * entity exists.
     */
    public static void placed(Block self, BlockState state, Level level, BlockPos pos, BlockState oldState) {
        if (!level.isClientSide && oldState.is(self) && oldState.getValue(FACING) != state.getValue(FACING)) {
            AirCompressorCore.reformAround(level, pos, self, oldState.getValue(FACING));
            AirCompressorCore.reform(level, pos, state.getValue(FACING));
        }
    }

    /** A compressor of {@code self} at {@code pos} is gone: re-form the units around where it was. */
    public static void removed(Block self, BlockState state, Level level, BlockPos pos, BlockState newState) {
        if (!level.isClientSide && !newState.is(self)) {
            AirCompressorCore.reformAround(level, pos, self, state.getValue(FACING));
        }
    }

    // --- Block entity ------------------------------------------------------------------------------

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AirCompressorBlockEntity.create(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.AIR_COMPRESSOR.get(),
                level.isClientSide ? AirCompressorBlockEntity::clientTick : AirCompressorBlockEntity::serverTick);
    }
}
