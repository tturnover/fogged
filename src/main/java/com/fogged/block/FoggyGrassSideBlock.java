package com.fogged.block;

import com.fogged.Config;
import com.fogged.FogMoss;
import com.fogged.registry.ModBlocks;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The wall-clinging variant of {@link FoggyGrassBlock foggy grass}: the same wispy tuft, but growing
 * sideways out of a block face instead of up off the ground.
 *
 * <p>It is planted by the {@link com.fogged.FoggyGrassWave charge wave} on the fringe rings it throws
 * past the edge of a fog-moss puddle — the moss itself grows the upright tuft, and the walls of whatever
 * the puddle runs up against (a cliff, a cellar, a player's build) grow this one. {@link #FACING} is the
 * direction the tuft points, i.e. away from the face holding it, so the block behind it is always at
 * {@code pos.relative(FACING.getOpposite())}. Growth through {@link FoggyGrassBlock#AGE} works exactly as
 * it does upright, sharing that block's age/cap properties and the roll that sets them; the models,
 * though, are its own — one per age stage, authored against a wall.
 */
public class FoggyGrassSideBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<FoggyGrassSideBlock> CODEC = simpleCodec(FoggyGrassSideBlock::new);

    /** Shared with the upright tuft: same stages, same per-tuft random cap, same models. */
    public static final IntegerProperty AGE = FoggyGrassBlock.AGE;
    public static final IntegerProperty CAP = FoggyGrassBlock.CAP;

    // Thin slab hugging the supporting face, reaching half a block out. Only ever hit-tested — the
    // block has no collision.
    private static final VoxelShape NORTH = Block.box(2.0, 2.0, 8.0, 14.0, 14.0, 16.0);
    private static final VoxelShape SOUTH = Block.box(2.0, 2.0, 0.0, 14.0, 14.0, 8.0);
    private static final VoxelShape WEST = Block.box(8.0, 2.0, 2.0, 16.0, 14.0, 14.0);
    private static final VoxelShape EAST = Block.box(0.0, 2.0, 2.0, 8.0, 14.0, 14.0);

    // Direction.values() clones its array on every call; the spread loop runs per random tick.
    private static final Direction[] ALL_DIRECTIONS = Direction.values();

    public FoggyGrassSideBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(AGE, 0)
                .setValue(CAP, FoggyGrassBlock.MAX_AGE));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, AGE, CAP);
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case EAST -> EAST;
            default -> NORTH;
        };
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockPos pos = context.getClickedPos();
        LevelReader level = context.getLevel();
        if (!level.getFluidState(pos).isEmpty()) {
            return null;
        }
        BlockState state = defaultBlockState();
        // Prefer the face actually clicked; failing that, any wall around the spot will do.
        Direction clicked = context.getClickedFace();
        if (clicked.getAxis().isHorizontal() && canAttachTo(level, pos, clicked)) {
            return state.setValue(FACING, clicked);
        }
        for (Direction dir : context.getNearestLookingDirections()) {
            if (dir.getAxis().isHorizontal() && canAttachTo(level, pos, dir)) {
                return state.setValue(FACING, dir);
            }
        }
        return null;
    }

    /** As the upright tuft: needs its support, and drops if it ends up submerged. */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return level.getFluidState(pos).isEmpty() && canAttachTo(level, pos, state.getValue(FACING));
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour,
            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return canSurvive(state, level, pos) ? state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!Config.FLORA_ENABLED.get()) {
            return;
        }
        int age = state.getValue(AGE);
        if (age < state.getValue(CAP) && random.nextDouble() < Config.FOGGY_GRASS_GROW_CHANCE.get()) {
            level.setBlock(pos, state.setValue(AGE, age + 1), UPDATE_CLIENTS);
        }
        // Creep along the wall it is already on, one neighbouring face at a time.
        if (random.nextDouble() < Config.FOGGY_GRASS_SPREAD_CHANCE.get()) {
            Direction facing = state.getValue(FACING);
            for (Direction dir : ALL_DIRECTIONS) {
                if (dir != facing && tryPlantSide(level, pos.relative(dir), random, true)) {
                    break;
                }
            }
        }
    }

    // ==== planting, driven by FoggyGrassWave ====

    /**
     * Try to grow a side tuft in the free space at {@code at}, on whichever surrounding wall will hold
     * one, its chance scaled by the same temperature density the upright grass uses. {@code randomBase}
     * starts it part-grown, as spread (rather than freshly seeded) growth does. Returns whether it planted.
     */
    public static boolean tryPlantSide(ServerLevel level, BlockPos at, RandomSource random, boolean randomBase) {
        if (!FoggyGrassBlock.isFree(level, at)) {
            return false;
        }
        // Find the wall before rolling: the roll costs a biome lookup, and on the randomTick path most
        // candidate spots have no wall at all.
        Direction facing = pickWall(level, at, random);
        if (facing == null) {
            return false;
        }
        double chance = Config.FOGGY_GRASS_SIDE_CHANCE.get() * FoggyGrassBlock.density(level, at);
        if (random.nextDouble() >= chance) {
            return false;
        }
        BlockState planted = FoggyGrassBlock.rollAgeAndCap(
                ModBlocks.FOGGY_GRASS_SIDE.get().defaultBlockState(), random, randomBase);
        level.setBlock(at, planted.setValue(FACING, facing), UPDATE_CLIENTS);
        FoggyGrassBlock.plantedEffects(level, at, random, 0.5, 0.5);
        return true;
    }

    /**
     * A spot on the wave's fringe: free space in this column near {@code refY} (allowing the same
     * one-block step the moss wave uses to follow terrain) that has a wall to cling to. Fog-moss columns
     * are skipped — those belong to the upright grass, which the moss wave plants itself.
     */
    public static BlockPos fringeSpotNear(ServerLevel level, BlockPos col, int refY) {
        return FoggyGrassBlock.columnNear(col, refY, at ->
                !FogMoss.isFogMoss(level.getBlockState(at.below()))
                        && FoggyGrassBlock.isFree(level, at)
                        && pickWall(level, at, level.random) != null);
    }

    // One of the walls around `at`, chosen at random so a run of tufts along a corner does not all
    // pick the same face. Null when nothing there will hold a tuft. Walking the four horizontals from a
    // random start beats shuffledCopy() here: same effect, no list allocated per call.
    private static Direction pickWall(ServerLevel level, BlockPos at, RandomSource random) {
        int start = random.nextInt(4);
        for (int i = 0; i < 4; i++) {
            Direction dir = Direction.from2DDataValue((start + i) & 3);
            if (canAttachTo(level, at, dir)) {
                return dir;
            }
        }
        return null;
    }

    // Whether a tuft at `pos` pointing `facing` has a solid face behind it to grow out of. The fluid at
    // `pos` is the caller's business -- checking it here would re-read the same position once per face.
    private static boolean canAttachTo(LevelReader level, BlockPos pos, Direction facing) {
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }
}
