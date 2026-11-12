package com.fogged.block;

import java.util.EnumMap;
import java.util.Map;

import com.fogged.AirFill;
import com.fogged.Config;
import com.fogged.PlaneSensor;
import com.fogged.registry.ModTags;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An air intake: where an air compressor draws its air from. A block of its own -- set it right
 * behind a compressor, or anywhere along an air duct run from a compressor's back, and the compressor
 * draws through it. A compressor with no intake feeding it does nothing.
 *
 * <p>{@link #FACING} points at what it feeds (a compressor's back, or a duct); the air comes in at
 * its mouth, the open end the other way, from the cell beyond ({@link #mouth}). That air has to be
 * clean for the room: either the mouth is out of the murk, or the intake has a filter in it -- the
 * same filter media a nozzle filter takes ({@link ModTags#FILTER_MEDIA}, wool by default). A filter
 * goes in with a right-click and comes back out with an empty hand. See {@link #supply}.
 */
public class AirCompressorIntakeBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = AirCompressorBlock.FACING;
    /** Whether a filter is fitted. Kept in the state so the model shows it and the room re-checks. */
    public static final BooleanProperty FILTERED = BooleanProperty.create("filtered");

    /** What an intake is drawing in. */
    public enum Supply {
        /** Out of the murk: clean air, no filter needed. */
        CLEAN,
        /** In the murk, through a filter: clean air. */
        FILTERED,
        /** In the murk with no filter: nothing the room can use. */
        MURK,
        /** Something stands against the mouth. */
        BLOCKED,
        /** The mouth is in a chunk not loaded yet. */
        UNKNOWN;

        public boolean clean() {
            return this == CLEAN || this == FILTERED;
        }
    }

    // The half of its cell on the FACING side, where it meets what it feeds. Authored facing NORTH.
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(0, 0, 0, 16, 16, 8),
            Direction.SOUTH, Block.box(0, 0, 8, 16, 16, 16),
            Direction.EAST, Block.box(8, 0, 0, 16, 16, 16),
            Direction.WEST, Block.box(0, 0, 0, 8, 16, 16),
            Direction.UP, Block.box(0, 8, 0, 16, 16, 16),
            Direction.DOWN, Block.box(0, 0, 0, 16, 8, 16)));

    public AirCompressorIntakeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FILTERED, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FILTERED);
    }

    /**
     * Fed into the block clicked: set on a compressor's back or a duct's end, it feeds that, mouth
     * toward the placer. Sneaking turns it round.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction feeds = context.getClickedFace().getOpposite();
        Player player = context.getPlayer();
        if (player != null && player.isSecondaryUseActive()) {
            feeds = feeds.getOpposite();
        }
        return defaultBlockState().setValue(FACING, feeds);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** The cell the intake draws its air from, beyond its open end. */
    public static BlockPos mouth(BlockPos pos, BlockState state) {
        return pos.relative(state.getValue(FACING).getOpposite());
    }

    /** What the intake at {@code pos} draws in now. Safe on either side. */
    public static Supply supply(Level level, BlockPos pos, BlockState state) {
        BlockPos mouth = mouth(pos, state);
        if (!level.isLoaded(mouth)) {
            return Supply.UNKNOWN;
        }
        if (AirFill.closes(level, mouth, level.getBlockState(mouth), state.getValue(FACING))) {
            return Supply.BLOCKED;
        }
        if (!Config.fogged(level, PlaneSensor.worldY(level, mouth))) {
            return Supply.CLEAN;
        }
        return state.getValue(FILTERED) ? Supply.FILTERED : Supply.MURK;
    }

    // --- The filter ---------------------------------------------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(ModTags.FILTER_MEDIA) || state.getValue(FILTERED)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AirCompressorIntakeBlockEntity be) {
            be.setFilter(stack.copyWithCount(1));
            stack.consume(1, player);
            level.setBlock(pos, state.setValue(FILTERED, true), 3);
            level.playSound(null, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
            BlockHitResult hit) {
        if (!state.getValue(FILTERED)) {
            return InteractionResult.PASS;
        }
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof AirCompressorIntakeBlockEntity be) {
            ItemStack filter = be.takeFilter();
            level.setBlock(pos, state.setValue(FILTERED, false), 3);
            if (!filter.isEmpty()) {
                player.getInventory().placeItemBackInInventory(filter);
            }
            level.playSound(null, pos, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // Gone (not just its filter changed): the filter drops with it.
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!newState.is(this) && level.getBlockEntity(pos) instanceof AirCompressorIntakeBlockEntity be) {
            ItemStack filter = be.takeFilter();
            if (!filter.isEmpty()) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), filter);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AirCompressorIntakeBlockEntity.create(pos, state);
    }
}
