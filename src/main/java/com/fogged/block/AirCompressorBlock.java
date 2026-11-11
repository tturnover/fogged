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
import net.minecraft.world.level.LevelReader;
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
 * An air compressor: a fan in a casing, set into the wall of a room. It draws air in through its
 * intake (the {@code FACING.getOpposite()} face, which needs open air behind it) and pushes it out of
 * its outlet ({@code FACING}) into the room, which it then holds airtight -- see
 * {@link AirCompressorBlockEntity} for the fill and {@link com.fogged.AirFill} for what seals.
 *
 * <p>Placed with the outlet toward whoever places it: you stand in the room and set it into the wall.
 * Compressors facing the same way side by side join into 2x2 and 3x3 units that hold proportionally
 * more room (see {@link AirCompressorBlockEntity#reform}).
 *
 * <p>The casing is a full cube, so it is part of the room's shell like any wall block.
 */
public class AirCompressorBlock extends BaseEntityBlock {
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
        Player player = context.getPlayer();
        boolean sneaking = player != null && player.isSecondaryUseActive();
        // Set against the side of another compressor: face the way it does, so a wall of them joins
        // into a unit however steeply the placer had to look to reach the top row. Sneaking opts out.
        if (!sneaking) {
            BlockState against = context.getLevel().getBlockState(
                    context.getClickedPos().relative(context.getClickedFace().getOpposite()));
            if (against.getBlock() instanceof AirCompressorBlock
                    && against.getValue(FACING).getAxis() != context.getClickedFace().getAxis()) {
                return fits(context, against.getValue(FACING));
            }
        }
        // Otherwise the outlet toward the placer; sneaking turns it round, for one set from outside.
        Direction toPlacer = context.getNearestLookingDirection().getOpposite();
        return fits(context, sneaking ? toPlacer.getOpposite() : toPlacer);
    }

    // The state facing `facing`, or null -- no placement -- when the cell its housing stands out into
    // is taken by something that could not be built over.
    private BlockState fits(BlockPlaceContext context, Direction facing) {
        return housingFree(context.getLevel(), context.getClickedPos(), facing)
                ? defaultBlockState().setValue(FACING, facing) : null;
    }

    /** The cell a compressor at {@code pos} facing {@code facing} pushes its housing half into. */
    public static BlockPos housingCell(BlockPos pos, Direction facing) {
        return pos.relative(facing.getOpposite());
    }

    /** Whether that cell is free for the housing: air, or anything placing a block would replace. */
    public static boolean housingFree(LevelReader level, BlockPos pos, Direction facing) {
        return level.getBlockState(housingCell(pos, facing)).canBeReplaced();
    }

    // --- Shape: a block and a half, the half standing out on the intake side ----------------------

    // The housing pushes half a block out of the wall into the unpressurized side, where its intake
    // draws; the outlet face sits flush with the room. Outlet authored facing NORTH, housing to SOUTH.
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(0, 0, 0, 16, 16, 24),
            Direction.SOUTH, Block.box(0, 0, -8, 16, 16, 16),
            Direction.EAST, Block.box(-8, 0, 0, 16, 16, 16),
            Direction.WEST, Block.box(0, 0, 0, 24, 16, 16),
            Direction.UP, Block.box(0, -8, 0, 16, 16, 16),
            Direction.DOWN, Block.box(0, 0, 0, 16, 24, 16)));

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    // Faces and light as a plain full block: the housing is decoration on the far side of the wall.
    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return Shapes.block();
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
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
        // A fresh placement joins from its block entity's onLoad: this runs before that exists.
        if (!level.isClientSide && oldState.is(this) && oldState.getValue(FACING) != state.getValue(FACING)) {
            AirCompressorBlockEntity.reformAround(level, pos, oldState.getValue(FACING));
            AirCompressorBlockEntity.reform(level, pos, state.getValue(FACING));
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!level.isClientSide && !newState.is(this)) {
            AirCompressorBlockEntity.reformAround(level, pos, state.getValue(FACING));
        }
    }

    // --- Block entity ------------------------------------------------------------------------------

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AirCompressorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return createTickerHelper(type, ModBlockEntities.AIR_COMPRESSOR.get(),
                level.isClientSide ? AirCompressorBlockEntity::clientTick : AirCompressorBlockEntity::serverTick);
    }

    /**
     * Keeps the housing's half-cell clear once the compressor is down: nothing can be placed into the
     * cell a neighbouring compressor's housing stands out into (it would sit inside the housing, and
     * shut the intake besides).
     */
    @EventBusSubscriber(modid = Fogged.MODID)
    public static final class HousingGuard {
        @SubscribeEvent
        static void onPlace(BlockEvent.EntityPlaceEvent event) {
            BlockPos pos = event.getPos();
            for (Direction d : Direction.values()) {
                BlockState next = event.getLevel().getBlockState(pos.relative(d));
                // A compressor one step `d` away has its housing here when it faces `d`.
                if (next.getBlock() instanceof AirCompressorBlock && next.getValue(FACING) == d) {
                    event.setCanceled(true);
                    return;
                }
            }
        }

        private HousingGuard() {}
    }
}
