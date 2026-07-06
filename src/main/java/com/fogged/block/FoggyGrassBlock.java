package com.fogged.block;

import com.fogged.Config;
import com.fogged.FogMoss;
import com.fogged.registry.ModBlocks;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * "Foggy grass": a wispy tuft that grows on top of {@link FogMoss fog moss} beneath the plane.
 *
 * <p>A tuft is seeded sparsely when a moss puddle forms (see {@link FogMoss}) and then, by random
 * ticks, both <em>grows upward</em> through its {@link #AGE} stages to a fixed limit and <em>spreads
 * sideways</em> onto neighbouring moss. How thickly it can carpet a patch is regulated by the biome
 * temperature: warm biomes support a dense sward, cold biomes only a sparse fuzz. The spread stops
 * once the live tufts in the surrounding area reach that temperature-derived cap, so a patch settles
 * at a stable density instead of swallowing every moss block.
 */
public class FoggyGrassBlock extends BushBlock {
    /** Growth stages 0..MAX_AGE; higher = a taller, bushier tuft (see the per-age stump models). */
    public static final int MAX_AGE = 3;
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, MAX_AGE);

    public static final MapCodec<FoggyGrassBlock> CODEC = simpleCodec(FoggyGrassBlock::new);

    public FoggyGrassBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AGE, 0));
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(AGE);
    }

    /** Foggy grass only clings to fog moss — that is the one surface it may stand on. */
    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return FogMoss.isFogMoss(state);
    }

    /** As BushBlock, but also drops if the tuft itself ends up submerged in fluid. */
    @Override
    public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return level.getFluidState(pos).isEmpty() && super.canSurvive(state, level, pos);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        // Tick at every stage: even a maxed-out tuft keeps a chance to spread onto bare moss.
        return true;
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!Config.FLORA_ENABLED.get()) {
            return;
        }
        // Grow upward toward the limit.
        int age = state.getValue(AGE);
        if (age < MAX_AGE && random.nextDouble() < Config.FOGGY_GRASS_GROW_CHANCE.get()) {
            level.setBlock(pos, state.setValue(AGE, age + 1), UPDATE_CLIENTS);
        }
        // Spread sideways onto a neighbouring patch of bare moss, density permitting.
        if (random.nextDouble() < Config.FOGGY_GRASS_SPREAD_CHANCE.get()) {
            trySpread(level, pos, random);
        }
    }

    // Pick a random horizontal neighbour; if it is bare moss and the patch is below its temperature
    // density cap, sprout a fresh (age 0) tuft there.
    private void trySpread(ServerLevel level, BlockPos pos, RandomSource random) {
        Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        BlockPos target = bareMossTopNear(level, pos.relative(dir), pos.getY());
        if (target == null) {
            return;
        }
        if (countTufts(level, target) >= densityCap(level, target)) {
            return; // patch already as thick as this temperature allows
        }
        level.setBlock(target, defaultBlockState(), UPDATE_CLIENTS);
    }

    // A position at refY (or one step up/down, to follow the moss puddle's slope) that sits on moss
    // and is currently empty — i.e. somewhere a new tuft could stand. Null if there is none.
    private static BlockPos bareMossTopNear(ServerLevel level, BlockPos col, int refY) {
        for (int dy : new int[] {0, 1, -1}) {
            BlockPos at = new BlockPos(col.getX(), refY + dy, col.getZ());
            BlockState here = level.getBlockState(at);
            if ((here.isAir() || here.canBeReplaced())
                    && level.getFluidState(at).isEmpty() // never sprout in water
                    && FogMoss.isFogMoss(level.getBlockState(at.below()))) {
                return at;
            }
        }
        return null;
    }

    /**
     * Sparsely seed a tuft on a freshly-placed moss block, with a probability scaled by the local
     * temperature density so warm puddles come up greener. Called by {@link FogMoss} as it spreads.
     */
    public static void trySeed(ServerLevel level, BlockPos mossPos, RandomSource random) {
        if (!Config.FLORA_ENABLED.get()) {
            return;
        }
        BlockPos top = mossPos.above();
        BlockState above = level.getBlockState(top);
        if ((!above.isAir() && !above.canBeReplaced()) || !level.getFluidState(top).isEmpty()) {
            return; // occupied, or submerged in water
        }
        double chance = Config.FOGGY_GRASS_SEED_CHANCE.get() * density(level, mossPos);
        if (random.nextDouble() < chance) {
            level.setBlock(top, ModBlocks.FOGGY_GRASS.get().defaultBlockState(), UPDATE_CLIENTS);
        }
    }

    // Count live tufts in the proximity window around `centre`.
    private static int countTufts(ServerLevel level, BlockPos centre) {
        int r = Config.FOGGY_GRASS_PROXIMITY.getAsInt();
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    p.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (level.getBlockState(p).is(ModBlocks.FOGGY_GRASS.get())) {
                        count++;
                        break; // at most one tuft per column
                    }
                }
            }
        }
        return count;
    }

    // The maximum number of tufts allowed in the proximity window: the moss columns nearby times the
    // temperature density fraction. Below this, spread is allowed; at or above it, the patch is full.
    private static int densityCap(ServerLevel level, BlockPos centre) {
        int r = Config.FOGGY_GRASS_PROXIMITY.getAsInt();
        int moss = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -1; dy <= 1; dy++) {
                    p.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (FogMoss.isFogMoss(level.getBlockState(p))) {
                        moss++;
                        break;
                    }
                }
            }
        }
        return (int) Math.ceil(moss * density(level, centre));
    }

    // Density fraction (0..1) of moss this biome's temperature supports, interpolated between the cold
    // and warm anchors that also pick the moss variant (fogMossTempBand = [coldMax, warmMin]).
    private static double density(ServerLevel level, BlockPos pos) {
        double temp = level.getBiome(pos).value().getBaseTemperature();
        double cold = Config.fogMossColdMax();
        double warm = Config.fogMossWarmMin();
        double lo = Config.foggyGrassColdDensity();
        double hi = Config.foggyGrassWarmDensity();
        if (warm <= cold) {
            return hi; // misconfigured thresholds: fall back to the dense end
        }
        double t = Mth.clamp((temp - cold) / (warm - cold), 0.0, 1.0);
        return Mth.lerp(t, lo, hi);
    }
}
