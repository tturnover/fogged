package com.fogged.block;

import com.fogged.Config;
import com.fogged.FogMoss;
import com.fogged.registry.ModBlocks;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
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
 * it does upright, sharing that block's age/cap properties and its models (tipped on their side).
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

    private static final Direction[] WALLS = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

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
        BlockState state = defaultBlockState();
        // Prefer the face actually clicked; failing that, any wall around the spot will do.
        Direction clicked = context.getClickedFace();
        if (clicked.getAxis().isHorizontal()
                && canAttachTo(context.getLevel(), context.getClickedPos(), clicked)) {
            return state.setValue(FACING, clicked);
        }
        for (Direction dir : context.getNearestLookingDirections()) {
            if (dir.getAxis().isHorizontal() && canAttachTo(context.getLevel(), context.getClickedPos(), dir)) {
                return state.setValue(FACING, dir);
            }
        }
        return null;
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        return canAttachTo(level, pos, state.getValue(FACING));
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
            for (Direction dir : Direction.values()) {
                BlockPos next = pos.relative(dir);
                if (dir != state.getValue(FACING) && tryPlantSide(level, next, random, true)) {
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
        if (!isFree(level, at)) {
            return false;
        }
        double chance = Config.FOGGY_GRASS_SIDE_CHANCE.get() * FoggyGrassBlock.density(level, at);
        if (random.nextDouble() >= chance) {
            return false;
        }
        Direction facing = pickWall(level, at, random);
        if (facing == null) {
            return false;
        }
        int cap = 1 + random.nextInt(FoggyGrassBlock.MAX_AGE);
        int age = randomBase ? random.nextInt(cap + 1) : 0;
        level.setBlock(at, ModBlocks.FOGGY_GRASS_SIDE.get().defaultBlockState()
                .setValue(FACING, facing).setValue(CAP, cap).setValue(AGE, age), UPDATE_CLIENTS);
        level.sendParticles(new SculkChargeParticleOptions(0.0F),
                at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.0);
        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5, 2, 0.2, 0.2, 0.2, 0.0);
        return true;
    }

    /**
     * A spot on the wave's fringe: free space in this column near {@code refY} (allowing the same
     * one-block step the moss wave uses to follow terrain) that has a wall to cling to. Fog-moss columns
     * are skipped — those belong to the upright grass, which the moss wave plants itself.
     */
    public static BlockPos fringeSpotNear(ServerLevel level, BlockPos col, int refY) {
        for (int dy : new int[] {0, 1, -1}) {
            BlockPos at = new BlockPos(col.getX(), refY + dy, col.getZ());
            if (FogMoss.isFogMoss(level.getBlockState(at.below()))) {
                continue;
            }
            if (isFree(level, at) && hasWall(level, at)) {
                return at;
            }
        }
        return null;
    }

    private static boolean isFree(ServerLevel level, BlockPos at) {
        BlockState here = level.getBlockState(at);
        return (here.isAir() || here.canBeReplaced()) && level.getFluidState(at).isEmpty();
    }

    private static boolean hasWall(ServerLevel level, BlockPos at) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (canAttachTo(level, at, dir)) {
                return true;
            }
        }
        return false;
    }

    // One of the walls around `at`, chosen at random so a run of tufts along a corner does not all
    // pick the same face. Null when nothing there will hold a tuft.
    private static Direction pickWall(ServerLevel level, BlockPos at, RandomSource random) {
        int start = random.nextInt(WALLS.length);
        for (int i = 0; i < WALLS.length; i++) {
            Direction dir = WALLS[(start + i) % WALLS.length];
            if (canAttachTo(level, at, dir)) {
                return dir;
            }
        }
        return null;
    }

    // Whether a tuft at `pos` pointing `facing` has a solid face behind it to grow out of.
    private static boolean canAttachTo(LevelReader level, BlockPos pos, Direction facing) {
        if (!level.getFluidState(pos).isEmpty()) {
            return false;
        }
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }
}
