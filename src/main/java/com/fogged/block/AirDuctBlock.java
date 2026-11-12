package com.fogged.block;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fogged.AirFill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An air duct: carries an air compressor's output away from it, to come out somewhere else, or brings
 * it air from an intake set further off. Laid like one of Create's fluid pipes -- each duct joins every
 * duct beside it, a compressor's outlet or back, and an intake feeding it, on its own -- but thick, a 12-pixel box with riveted collars where it joins, in the
 * look of Create's industrial iron.
 *
 * <p>The air comes out of the network's dead ends: a duct joined on one side only vents straight out
 * of the side across from it (see {@link #ends}). Every other side of every duct is closed, so a
 * network can branch freely and each branch ends in a vent. Its outline is the tube, but it collides as
 * a full block, so a duct run through a wall is as tight as the wall.
 *
 * <p>A duct is full of the air it carries: every duct cell of a compressor's networks counts as a
 * block of its room, held and paid for like any other (see {@link AirCompressorCore}).
 */
public class AirDuctBlock extends Block {

    /** Most duct cells one compressor face's network walks, so a vast tangle cannot stall a tick. */
    public static final int MAX_NETWORK = 512;

    private static final Map<Direction, BooleanProperty> SIDES = PipeBlock.PROPERTY_BY_DIRECTION;

    private static final VoxelShape CORE = Block.box(2, 2, 2, 14, 14, 14);
    private static final Map<Direction, VoxelShape> COLLARS = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(1, 1, 0, 15, 15, 2),
            Direction.SOUTH, Block.box(1, 1, 14, 15, 15, 16),
            Direction.WEST, Block.box(0, 1, 1, 2, 15, 15),
            Direction.EAST, Block.box(14, 1, 1, 16, 15, 15),
            Direction.DOWN, Block.box(1, 0, 1, 15, 2, 15),
            Direction.UP, Block.box(1, 14, 1, 15, 16, 15)));
    private static final Map<BlockState, VoxelShape> OUTLINES = new HashMap<>();

    public AirDuctBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (BooleanProperty side : SIDES.values()) {
            state = state.setValue(side, false);
        }
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.NORTH, BlockStateProperties.SOUTH, BlockStateProperties.EAST,
                BlockStateProperties.WEST, BlockStateProperties.UP, BlockStateProperties.DOWN);
    }

    /**
     * Whether a duct joins {@code neighbour}, which lies to its {@code side}: another duct, a
     * compressor's outlet or back, or an intake feeding into it.
     */
    private static boolean joins(BlockState neighbour, Direction side) {
        if (neighbour.getBlock() instanceof AirDuctBlock) {
            return true;
        }
        if (neighbour.getBlock() instanceof CompressorBlock) {
            return neighbour.getValue(BlockStateProperties.FACING).getAxis() == side.getAxis();
        }
        return neighbour.getBlock() instanceof AirCompressorIntakeBlock
                && neighbour.getValue(AirCompressorIntakeBlock.FACING) == side.getOpposite();
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction d : Direction.values()) {
            state = state.setValue(SIDES.get(d), joins(context.getLevel().getBlockState(context.getClickedPos().relative(d)), d));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        return state.setValue(SIDES.get(side), joins(neighbour, side));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINES.computeIfAbsent(state, s -> {
            VoxelShape shape = CORE;
            for (Direction d : Direction.values()) {
                if (s.getValue(SIDES.get(d))) {
                    shape = Shapes.or(shape, COLLARS.get(d));
                }
            }
            return shape;
        });
    }

    // A full block to walk into and to the air: a duct through a wall must keep the wall tight.
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.block();
    }

    /**
     * The vents of the duct network that starts at {@code start}, entered from a compressor going
     * {@code from}: where the air comes out, each with the way it is travelling. Empty when
     * {@code start} is not a duct joined to the compressor -- the air then goes straight out of the
     * outlet. Every duct cell walked is added to {@code walked}, for the room to watch and to fill.
     */
    public static List<AirFill.Seed> ends(Level level, BlockPos start, Direction from, Set<BlockPos> walked) {
        List<AirFill.Seed> out = new ArrayList<>();
        List<BlockPos> network = network(level, start, from);
        for (BlockPos at : network) {
            BlockState here = level.getBlockState(at);
            int joined = 0;
            Direction only = null;
            for (Direction d : Direction.values()) {
                if (here.getValue(SIDES.get(d))) {
                    joined++;
                    only = d;
                }
            }
            // A dead end vents straight on, out of the side across from the one it is joined by.
            if (joined == 1) {
                Direction vent = only.getOpposite();
                out.add(new AirFill.Seed(at.relative(vent).immutable(), vent));
            }
        }
        walked.addAll(network);
        return out;
    }

    /**
     * The intakes feeding the duct network that starts at {@code start}, entered from a compressor's
     * back going {@code from}. Every duct cell walked is added to {@code walked}.
     */
    public static List<BlockPos> intakes(Level level, BlockPos start, Direction from, Set<BlockPos> walked) {
        List<BlockPos> out = new ArrayList<>();
        List<BlockPos> network = network(level, start, from);
        for (BlockPos at : network) {
            BlockState here = level.getBlockState(at);
            for (Direction d : Direction.values()) {
                if (!here.getValue(SIDES.get(d))) {
                    continue;
                }
                BlockPos next = at.relative(d);
                if (!level.isLoaded(next)) {
                    continue;
                }
                BlockState there = level.getBlockState(next);
                if (there.getBlock() instanceof AirCompressorIntakeBlock
                        && there.getValue(AirCompressorIntakeBlock.FACING) == d.getOpposite()) {
                    out.add(next.immutable());
                }
            }
        }
        walked.addAll(network);
        return out;
    }

    // Every duct cell joined to `start`, which a compressor face going `from` enters; empty when
    // `start` is no duct joined that way.
    private static List<BlockPos> network(Level level, BlockPos start, Direction from) {
        List<BlockPos> queue = new ArrayList<>();
        BlockState first = level.getBlockState(start);
        if (!(first.getBlock() instanceof AirDuctBlock) || !first.getValue(SIDES.get(from.getOpposite()))) {
            return queue;
        }
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        for (int i = 0; i < queue.size(); i++) {
            BlockPos at = queue.get(i);
            BlockState here = level.getBlockState(at);
            for (Direction d : Direction.values()) {
                if (!here.getValue(SIDES.get(d))) {
                    continue;
                }
                BlockPos next = at.relative(d);
                if (!seen.contains(next) && seen.size() < MAX_NETWORK && level.isLoaded(next)
                        && level.getBlockState(next).getBlock() instanceof AirDuctBlock) {
                    seen.add(next.immutable());
                    queue.add(next.immutable());
                }
            }
        }
        return queue;
    }
}
