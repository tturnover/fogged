package com.fogged.create;

import com.fogged.block.AirCompressorBlock;
import com.fogged.block.CompressorBlock;
import com.fogged.block.CompressorHost;
import com.fogged.registry.ModBlocks;
import com.simibubi.create.content.kinetics.base.KineticBlock;
import com.simibubi.create.foundation.block.IBE;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The kinetic air compressor: an {@link AirCompressorBlock} driven by a Create shaft instead of fed FE.
 * Same casing, intake and room. Not crafted -- an air compressor becomes one when a wrench is used on
 * one of its sides ({@link #fitShaft}): that side gets a socket with a shaft end to connect to. One
 * socket to a compressor. The wrench on the
 * socket takes it back out, and it is a plain air compressor again. Its stress on the network grows with the room it holds
 * (see {@link KineticAirCompressorBlockEntity}). Only registered with Create installed.
 */
public class KineticAirCompressorBlock extends KineticBlock implements IBE<KineticAirCompressorBlockEntity>, CompressorBlock {

    public static final DirectionProperty FACING = AirCompressorBlock.FACING;
    /** The shaft's axis: across the compressor, never along its outlet. */
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    /** A socket on the axis's positive side (east, up, south) and its negative side. */
    public static final BooleanProperty POSITIVE = BooleanProperty.create("positive");
    public static final BooleanProperty NEGATIVE = BooleanProperty.create("negative");

    static final ResourceLocation WRENCH = ResourceLocation.fromNamespaceAndPath("create", "wrench");

    public KineticAirCompressorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(AXIS, Direction.Axis.X)
                .setValue(POSITIVE, true).setValue(NEGATIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(FACING, AXIS, POSITIVE, NEGATIVE);
    }

    /** The socket property for the side {@code face}. */
    static BooleanProperty end(Direction face) {
        return face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? POSITIVE : NEGATIVE;
    }

    static Item wrenchItem() {
        return BuiltInRegistries.ITEM.get(WRENCH);
    }

    /**
     * A wrench on side {@code face} of the air compressor at {@code pos}: it becomes a kinetic one with a
     * shaft socket there. False -- nothing done -- for the intake or outlet, or a compressor that already
     * has its socket.
     */
    static boolean fitShaft(Level level, BlockPos pos, BlockState state, Direction face) {
        Direction facing = state.getValue(FACING);
        if (face.getAxis() == facing.getAxis() || !state.is(ModBlocks.AIR_COMPRESSOR.get())) {
            return false; // not the intake or outlet; and one shaft to a compressor
        }
        BlockState next = CreateContent.KINETIC_AIR_COMPRESSOR.get().defaultBlockState()
                .setValue(FACING, facing).setValue(AXIS, face.getAxis())
                .setValue(POSITIVE, false).setValue(NEGATIVE, false).setValue(end(face), true);
        level.setBlockAndUpdate(pos, next);
        level.playSound(null, pos, SoundEvents.NETHERITE_BLOCK_PLACE, SoundSource.BLOCKS, 0.8F, 1.2F);
        return true;
    }

    // The wrench takes the socket back out, rather than turning the compressor round.
    @Override
    public InteractionResult onWrenched(BlockState state, UseOnContext context) {
        Direction face = context.getClickedFace();
        if (face.getAxis() != state.getValue(AXIS) || !state.getValue(end(face))) {
            return InteractionResult.PASS;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        if (!level.isClientSide) {
            level.setBlockAndUpdate(pos, ModBlocks.AIR_COMPRESSOR.get().defaultBlockState().setValue(FACING, state.getValue(FACING)));
            level.playSound(null, pos, SoundEvents.NETHERITE_BLOCK_BREAK, SoundSource.BLOCKS, 0.6F, 1.4F);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    // A socket fitted or taken out changes which neighbours the shaft meets: reconnect, as for a turn.
    @Override
    protected boolean areStatesKineticallyEquivalent(BlockState oldState, BlockState newState) {
        return super.areStatesKineticallyEquivalent(oldState, newState)
                && oldState.getValue(POSITIVE) == newState.getValue(POSITIVE)
                && oldState.getValue(NEGATIVE) == newState.getValue(NEGATIVE);
    }

    // Below medium speed it does nothing; Create shows the requirement in its goggles.
    @Override
    public SpeedLevel getMinimumRequiredSpeedLevel() {
        return SpeedLevel.MEDIUM;
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == state.getValue(AXIS) && state.getValue(end(face));
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBlocks.AIR_COMPRESSOR.get());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        Direction facing = rotation.rotate(state.getValue(FACING));
        Direction.Axis axis = state.getValue(AXIS);
        boolean pos = state.getValue(POSITIVE), neg = state.getValue(NEGATIVE);
        if (axis.isHorizontal() && rotation != Rotation.NONE) {
            // Turn each socket's side with the block, then read off the new axis and ends.
            Direction p = rotation.rotate(Direction.fromAxisAndDirection(axis, Direction.AxisDirection.POSITIVE));
            boolean newPos = p.getAxisDirection() == Direction.AxisDirection.POSITIVE ? pos : neg;
            boolean newNeg = p.getAxisDirection() == Direction.AxisDirection.POSITIVE ? neg : pos;
            return state.setValue(FACING, facing).setValue(AXIS, p.getAxis()).setValue(POSITIVE, newPos).setValue(NEGATIVE, newNeg);
        }
        return state.setValue(FACING, facing);
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    // A comparator reads the room's intake filters' average pollution, as from the FE compressor.
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return AirCompressorBlock.comparatorSignal(level, pos);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        super.onRemove(state, level, pos, newState, movedByPiston);
        IBE.onRemove(state, level, pos, newState);
    }

    @Override
    public Class<KineticAirCompressorBlockEntity> getBlockEntityClass() {
        return KineticAirCompressorBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends KineticAirCompressorBlockEntity> getBlockEntityType() {
        return CreateContent.KINETIC_AIR_COMPRESSOR_BE.get();
    }
}
