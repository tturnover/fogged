package com.fogged.block;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fogged.AirFill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * An air duct: carries an air compressor's output away from it, to come out somewhere else, or brings
 * it air from an intake set further off. It behaves as Create's fluid pipe does
 * (com.simibubi.create.content.fluids.pipes.FluidPipeBlock#updateBlockState):
 * <ul>
 * <li>it joins every duct beside it, a compressor's outlet or back, and an intake feeding it;
 * <li>joined on one side only, it opens the side across from it as well, so it runs on straight;
 * <li>joined to nothing, it runs straight along the way it was placed (or keeps the two ends it had);
 * <li>an open side with nothing to join -- an open end -- is where the air comes out ({@link #ends}).
 * </ul>
 * Each side is {@link Side#NONE} (closed), {@link Side#DUCT} (joined to another duct: a collar) or
 * {@link Side#RIM} (an open end, or fed into a compressor or intake: a rim), which picks its model the
 * way Create's pipe picks its attachments. Its outline is the tube, but it collides as a full block, so
 * a duct run through a wall is as tight as the wall.
 *
 * <p>A duct is full of the air it carries: every duct cell of a compressor's networks counts as a
 * block of its room, held and paid for like any other (see {@link AirCompressorCore}).
 */
public class AirDuctBlock extends Block {

    /** Most duct cells one compressor face's network walks, so a vast tangle cannot stall a tick. */
    public static final int MAX_NETWORK = 512;

    /** What a duct does on one side. */
    public enum Side implements StringRepresentable {
        /** Closed. */
        NONE,
        /** Joined to another duct. */
        DUCT,
        /** Open, onto something that is not a duct: a compressor, an intake, or the world (a vent). */
        RIM;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }

        public boolean open() {
            return this != NONE;
        }
    }

    public static final Map<Direction, EnumProperty<Side>> SIDES = new EnumMap<>(Direction.class);

    static {
        for (Direction d : Direction.values()) {
            SIDES.put(d, EnumProperty.create(d.getSerializedName(), Side.class));
        }
    }

    // The bore, and per open side the stub to the face (a duct beside it) or the rim round it.
    private static final VoxelShape BORE = Block.box(1, 1, 1, 15, 15, 15);
    private static final Map<Direction, VoxelShape> STUBS = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(1, 1, 0, 15, 15, 1),
            Direction.SOUTH, Block.box(1, 1, 15, 15, 15, 16),
            Direction.WEST, Block.box(0, 1, 1, 1, 15, 15),
            Direction.EAST, Block.box(15, 1, 1, 16, 15, 15),
            Direction.DOWN, Block.box(1, 0, 1, 15, 1, 15),
            Direction.UP, Block.box(1, 15, 1, 15, 16, 15)));
    private static final Map<Direction, VoxelShape> RIMS = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(0, 0, 0, 16, 16, 2),
            Direction.SOUTH, Block.box(0, 0, 14, 16, 16, 16),
            Direction.WEST, Block.box(0, 0, 0, 2, 16, 16),
            Direction.EAST, Block.box(14, 0, 0, 16, 16, 16),
            Direction.DOWN, Block.box(0, 0, 0, 16, 2, 16),
            Direction.UP, Block.box(0, 14, 0, 16, 16, 16)));
    private static final Map<BlockState, VoxelShape> OUTLINES = new HashMap<>();

    public AirDuctBlock(Properties properties) {
        super(properties);
        BlockState state = stateDefinition.any();
        for (EnumProperty<Side> side : SIDES.values()) {
            state = state.setValue(side, Side.NONE);
        }
        registerDefaultState(state);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SIDES.values().toArray(new EnumProperty<?>[0]));
    }

    /** Whether the duct is open on {@code side}. */
    public static boolean isOpen(BlockState state, Direction side) {
        return state.getValue(SIDES.get(side)).open();
    }

    /**
     * Whether a duct reaches out to {@code neighbour}, which lies to its {@code side}: another duct --
     * any, as Create's pipes join any pipe beside them -- a compressor's outlet or back, or an intake
     * feeding into it.
     */
    private static boolean canConnectTo(BlockState neighbour, Direction side) {
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
        return update(defaultBlockState(), context.getNearestLookingDirection(), null, context.getLevel(),
                context.getClickedPos());
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        return update(state, side, side.getOpposite(), level, pos);
    }

    /**
     * Create's FluidPipeBlock#updateBlockState, step for step: join what is beside it (all sides but
     * {@code ignore}, which keeps what it was); joined on two sides or more, that is it; on one, open
     * the side across too; on none, keep the two ends it had, or open along {@code preferred}. Then
     * each open side is a collar or a rim by what is beside it.
     */
    private static BlockState update(BlockState state, Direction preferred, Direction ignore, BlockGetter level,
            BlockPos pos) {
        int prevOpen = 0;
        for (Direction d : Direction.values()) {
            if (isOpen(state, d)) {
                prevOpen++;
            }
        }
        boolean[] open = new boolean[6];
        Direction only = null;
        int count = 0;
        for (Direction d : Direction.values()) {
            boolean o = d == ignore ? isOpen(state, d) : canConnectTo(level.getBlockState(pos.relative(d)), d);
            open[d.ordinal()] = o;
            if (o) {
                count++;
                only = d;
            }
        }
        if (count == 1) {
            open[only.getOpposite().ordinal()] = true;
        } else if (count == 0) {
            if (prevOpen == 2) {
                return withKinds(state, level, pos);
            }
            open[preferred.ordinal()] = true;
            open[preferred.getOpposite().ordinal()] = true;
        }
        for (Direction d : Direction.values()) {
            state = state.setValue(SIDES.get(d), open[d.ordinal()] ? Side.RIM : Side.NONE);
        }
        return withKinds(state, level, pos);
    }

    // Each open side a collar where a duct is beside it, a rim anywhere else.
    private static BlockState withKinds(BlockState state, BlockGetter level, BlockPos pos) {
        for (Direction d : Direction.values()) {
            if (isOpen(state, d)) {
                boolean duct = level.getBlockState(pos.relative(d)).getBlock() instanceof AirDuctBlock;
                state = state.setValue(SIDES.get(d), duct ? Side.DUCT : Side.RIM);
            }
        }
        return state;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockState out = state;
        for (Direction d : Direction.values()) {
            out = out.setValue(SIDES.get(rotation.rotate(d)), state.getValue(SIDES.get(d)));
        }
        return out;
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockState out = state;
        for (Direction d : Direction.values()) {
            out = out.setValue(SIDES.get(mirror.mirror(d)), state.getValue(SIDES.get(d)));
        }
        return out;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return OUTLINES.computeIfAbsent(state, s -> {
            VoxelShape shape = BORE;
            for (Direction d : Direction.values()) {
                Side side = s.getValue(SIDES.get(d));
                if (side == Side.DUCT) {
                    shape = Shapes.or(shape, STUBS.get(d));
                } else if (side == Side.RIM) {
                    shape = Shapes.or(shape, STUBS.get(d), RIMS.get(d));
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
     * {@code from}: its open ends, where the air comes out, each with the way it is travelling. Empty
     * when {@code start} is not a duct joined to the compressor -- the air then goes straight out of
     * the outlet. Every duct cell walked is added to {@code walked}, for the room to watch and to fill.
     */
    public static List<AirFill.Seed> ends(Level level, BlockPos start, Direction from, Set<BlockPos> walked) {
        List<AirFill.Seed> out = new ArrayList<>();
        List<BlockPos> network = network(level, start, from);
        for (BlockPos at : network) {
            BlockState here = level.getBlockState(at);
            // An open end vents straight out: a rim with nothing beyond it that it feeds.
            for (Direction d : Direction.values()) {
                BlockPos next = at.relative(d);
                if (here.getValue(SIDES.get(d)) == Side.RIM
                        && (!level.isLoaded(next) || !canConnectTo(level.getBlockState(next), d))) {
                    out.add(new AirFill.Seed(next.immutable(), d));
                }
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
                BlockPos next = at.relative(d);
                if (here.getValue(SIDES.get(d)) != Side.RIM || !level.isLoaded(next)) {
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
    // `start` is no duct open that way.
    private static List<BlockPos> network(Level level, BlockPos start, Direction from) {
        List<BlockPos> queue = new ArrayList<>();
        BlockState first = level.getBlockState(start);
        if (!(first.getBlock() instanceof AirDuctBlock) || !isOpen(first, from.getOpposite())) {
            return queue;
        }
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        for (int i = 0; i < queue.size(); i++) {
            BlockPos at = queue.get(i);
            BlockState here = level.getBlockState(at);
            for (Direction d : Direction.values()) {
                if (here.getValue(SIDES.get(d)) != Side.DUCT) {
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
