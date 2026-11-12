package com.fogged.block;


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
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.item.DyeColor;
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
    /** The fitted filter's colour, for the filter plane behind the mesh: a wool's own, white otherwise. */
    public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);

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

    public AirCompressorIntakeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(FILTERED, false)
                .setValue(COLOR, DyeColor.WHITE));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, FILTERED, COLOR);
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

    // Four stepped boxes following the model, as Create steps its shapes (see AirIntakeShapes). The block
    // it feeds -- a compressor, a duct -- is what seals the way through.
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return AirIntakeShapes.SHAPES.get(state.getValue(FACING));
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

    /**
     * Blocks of air per second the intake at {@code pos} supplies its compressor through {@code ducts}
     * ducts, drawing {@code supply}: {@link Config#INTAKE_FLOW}, less {@link Config#INTAKE_DUCT_LOSS}
     * a duct, and through a filter less again for depth under the murk, as a nozzle filter's sphere
     * shrinks ({@link Config#depthRadiusFactor}). None for air it cannot use.
     */
    public static double flow(Level level, BlockPos pos, BlockState state, Supply supply, int ducts) {
        if (!supply.clean()) {
            return 0.0;
        }
        double flow = Config.INTAKE_FLOW.getAsInt()
                * Math.pow(Math.max(0.0, 1.0 - Config.INTAKE_DUCT_LOSS.get() / 100.0), ducts);
        if (supply == Supply.FILTERED) {
            flow *= Config.depthRadiusFactor(level, PlaneSensor.worldY(level, mouth(pos, state)));
        }
        return flow;
    }

    /**
     * Wear the filter of the intake at {@code pos} by {@code blocks} blocks of air cleaned. Spent
     * ({@link Config#FILTER_LIFE}), it falls apart and is gone, and the intake draws murk again.
     */
    public static void wear(Level level, BlockPos pos, double blocks) {
        int life = Config.FILTER_LIFE.getAsInt();
        BlockState state = level.getBlockState(pos);
        if (life <= 0 || !(state.getBlock() instanceof AirCompressorIntakeBlock) || !state.getValue(FILTERED)
                || !(level.getBlockEntity(pos) instanceof AirCompressorIntakeBlockEntity be)) {
            return;
        }
        if (!be.wear(blocks, life)) {
            return;
        }
        ItemStack spent = be.takeFilter();
        level.setBlock(pos, state.setValue(FILTERED, false), 3);
        level.playSound(null, pos, SoundEvents.WOOL_BREAK, SoundSource.BLOCKS, 1.0F, 0.8F);
        if (level instanceof net.minecraft.server.level.ServerLevel server && !spent.isEmpty()) {
            net.minecraft.world.phys.Vec3 at = net.minecraft.world.phys.Vec3.atCenterOf(mouth(pos, state))
                    .add(net.minecraft.world.phys.Vec3.atCenterOf(pos)).scale(0.5);
            server.sendParticles(new net.minecraft.core.particles.ItemParticleOption(
                    net.minecraft.core.particles.ParticleTypes.ITEM, spent), at.x, at.y, at.z, 12, 0.2, 0.2, 0.2, 0.05);
        }
    }

    // --- The filter ---------------------------------------------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(ModTags.FILTER_MEDIA) || state.getValue(FILTERED)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide && fit(level, pos, stack)) {
            stack.consume(1, player);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Fit one of {@code stack} as the filter of the intake at {@code pos}, server side: by a player's
     * (or a Deployer's) hand, or put in through its inventory by a Mechanical Arm, hopper or funnel.
     * False, nothing done, when it is no filter media or a filter is in already. Takes nothing from
     * {@code stack}; the caller does.
     */
    public static boolean fit(Level level, BlockPos pos, ItemStack stack) {
        BlockState state = level.getBlockState(pos);
        if (stack.isEmpty() || !stack.is(ModTags.FILTER_MEDIA) || !(state.getBlock() instanceof AirCompressorIntakeBlock)
                || state.getValue(FILTERED) || !(level.getBlockEntity(pos) instanceof AirCompressorIntakeBlockEntity be)) {
            return false;
        }
        be.setFilter(stack.copyWithCount(1));
        level.setBlock(pos, state.setValue(FILTERED, true)
                .setValue(COLOR, com.fogged.NozzleFilterEvents.woolColor(stack)), 3);
        level.playSound(null, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1.0F, 1.0F);
        return true;
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
