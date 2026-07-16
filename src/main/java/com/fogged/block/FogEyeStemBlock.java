package com.fogged.block;

import com.fogged.FogMoss;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * The fog eye stem: the trail a {@link FogEyeBlock} leaves behind as it climbs toward the fog surface.
 *
 * <p>This is vanilla's chorus plant with the serial numbers filed off — same {@link PipeBlock}
 * connection properties, same six-way geometry, same support rule — because a forked plant needs all
 * of it. The stem carries no growth logic of its own; the eye does the growing and the stem is what it
 * leaves behind. What changed is only what counts as ground: {@link FogMoss} instead of end stone.
 *
 * <p>Support: a stem stands on moss or on the stem below it, and a stem out on an arm instead hangs
 * from a single horizontal stem that is itself carried. Insisting on exactly one such connection is
 * what stops a cut arm from holding itself up in a loop — cut a plant and each orphaned stem gets its
 * own break tick, so the whole thing unzips a block at a time.
 */
public class FogEyeStemBlock extends PipeBlock {
    public static final MapCodec<FogEyeStemBlock> CODEC = simpleCodec(FogEyeStemBlock::new);

    /** Vanilla's chorus-plant arm thickness: a 10x10 core with 8-wide arms out to each face. */
    private static final float APOTHEM = 0.3125F;

    public FogEyeStemBlock(Properties properties) {
        super(APOTHEM, properties);
        registerDefaultState(stateDefinition.any()
                .setValue(NORTH, false)
                .setValue(EAST, false)
                .setValue(SOUTH, false)
                .setValue(WEST, false)
                .setValue(UP, false)
                .setValue(DOWN, false));
    }

    @Override
    protected MapCodec<? extends PipeBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return getStateWithConnections(context.getLevel(), context.getClickedPos(), defaultBlockState());
    }

    /** A stem state wired up to whatever plant / moss already touches {@code pos}. */
    public static BlockState getStateWithConnections(BlockGetter level, BlockPos pos, BlockState state) {
        BlockState below = level.getBlockState(pos.below());
        return state.trySetValue(DOWN, connects(below) || FogMoss.isFogMoss(below))
                .trySetValue(UP, connects(level.getBlockState(pos.above())))
                .trySetValue(NORTH, connects(level.getBlockState(pos.north())))
                .trySetValue(EAST, connects(level.getBlockState(pos.east())))
                .trySetValue(SOUTH, connects(level.getBlockState(pos.south())))
                .trySetValue(WEST, connects(level.getBlockState(pos.west())));
    }

    /** Stems join to other stems and to the eyes riding them; sideways moss is not part of the plant. */
    private static boolean connects(BlockState state) {
        return state.getBlock() instanceof FogEyeStemBlock || state.getBlock() instanceof FogEyeBlock;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction facing, BlockState facingState,
            LevelAccessor level, BlockPos pos, BlockPos facingPos) {
        if (!canSurvive(state, level, pos)) {
            level.scheduleTick(pos, this, 1);
            return super.updateShape(state, facing, facingState, level, pos, facingPos);
        }
        boolean connected = connects(facingState)
                || (facing == Direction.DOWN && FogMoss.isFogMoss(facingState));
        return state.setValue(PROPERTY_BY_DIRECTION.get(facing), connected);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!canSurvive(state, level, pos)) {
            level.destroyBlock(pos, true);
        }
    }

    /** A stem stands on moss / more stem, or hangs from one carried horizontal stem out on an arm. */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        // A stem with something solid above and below is mid-column, so a stem to its side would be a
        // loop rather than an arm. Vanilla's chorus plant makes exactly this distinction.
        boolean midColumn = !level.getBlockState(pos.above()).isAir() && !below.isAir();
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos sidePos = pos.relative(dir);
            if (!level.getBlockState(sidePos).is(this)) {
                continue;
            }
            if (midColumn) {
                return false;
            }
            if (isGround(level.getBlockState(sidePos.below()))) {
                return true; // hanging off an arm whose stem is itself carried
            }
        }
        return isGround(below);
    }

    /** What a stem may rest its weight on: the moss it sprouted from, or more stem. */
    private boolean isGround(BlockState state) {
        return state.is(this) || FogMoss.isFogMoss(state);
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }
}
