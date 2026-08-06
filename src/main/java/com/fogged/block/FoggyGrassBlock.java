package com.fogged.block;

import java.util.function.Predicate;

import com.fogged.Config;
import com.fogged.FogMoss;
import com.fogged.FoggyGrassWave;
import com.fogged.registry.ModBlocks;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SculkChargeParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * "Foggy grass": a wispy tuft that grows on top of {@link FogMoss fog moss} beneath the plane.
 *
 * <p>Tufts are planted by a {@link FoggyGrassWave charge wave} that ripples out across the moss like a
 * sculk catalyst's bloom: a big one-shot bloom when a moss puddle forms, and small sparks as mature
 * tufts creep onto neighbouring moss. Each planted tuft then grows upward through its {@link #AGE}
 * stages by random ticks, stopping at its own {@link #CAP random maximum age} so a patch comes up ragged
 * rather than uniform. How thickly a patch can carpet is regulated by the biome temperature: warm biomes
 * support a dense sward, cold biomes only a sparse fuzz, and the wave stops planting once the live tufts
 * in the surrounding area reach that temperature-derived cap.
 */
public class FoggyGrassBlock extends BushBlock {
    /** Growth stages 0..MAX_AGE; higher = a taller, bushier tuft (see the per-age stump models). */
    public static final int MAX_AGE = 3;
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, MAX_AGE);

    /** This tuft's own maximum age, rolled once when planted, so a patch settles ragged. Not drawn — only
     *  {@link #AGE} drives the model — so it adds no model variants. */
    public static final IntegerProperty CAP = IntegerProperty.create("cap", 1, MAX_AGE);

    public static final MapCodec<FoggyGrassBlock> CODEC = simpleCodec(FoggyGrassBlock::new);

    /** The vertical steps a column scan tries, in order: level, then one up, then one down. */
    private static final int[] DY = {0, 1, -1};

    public FoggyGrassBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(AGE, 0).setValue(CAP, MAX_AGE));
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<net.minecraft.world.level.block.Block, BlockState> builder) {
        builder.add(AGE, CAP);
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return FogMoss.isFloraSoil(state);
    }

    /** As BushBlock, but also drops if the tuft itself ends up submerged in fluid. */
    @Override
    public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return level.getFluidState(pos).isEmpty() && super.canSurvive(state, level, pos);
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        // Tick at every stage: even a maxed-out tuft keeps a chance to spark a spread onto bare moss.
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
        if (random.nextDouble() < Config.FOGGY_GRASS_SPREAD_CHANCE.get()) {
            FoggyGrassWave.spark(level, pos);
        }
    }

    // ==== planting, driven by FoggyGrassWave ====

    /**
     * The position just above fog moss at, or one step up/down from, {@code refY} in this column — the
     * surface the wave walks along, whether or not it plants there. Null if no fog moss is near {@code refY}.
     */
    public static BlockPos mossTopNear(ServerLevel level, BlockPos col, int refY) {
        return columnNear(col, refY, at -> FogMoss.isSeedGround(level.getBlockState(at.below())));
    }

    /**
     * The first position in this column, at {@code refY} or one step either side of it, that {@code accept}
     * takes — the one-block step being how every wave here follows sloping terrain. Null if none does.
     */
    public static BlockPos columnNear(BlockPos col, int refY, Predicate<BlockPos> accept) {
        for (int dy : DY) {
            BlockPos at = new BlockPos(col.getX(), refY + dy, col.getZ());
            if (accept.test(at)) {
                return at;
            }
        }
        return null;
    }

    /** Free space a tuft could occupy: air or replaceable, and not flooded. */
    public static boolean isFree(ServerLevel level, BlockPos at) {
        BlockState here = level.getBlockState(at);
        return (here.isAir() || here.canBeReplaced()) && here.getFluidState().isEmpty();
    }

    /**
     * Bloom planting: sparsely sprout a tuft, its chance scaled by temperature density. A substituted puff
     * bush is grown out at once on an {@code instant} chunk rather than left as a sapling. A fog eye is
     * rolled for ahead of either -- it is not a substitute tuft the way the bush is, it has its own
     * independent chance at every spot the wave visits, seeded and spread alike. Returns whether
     * something planted.
     */
    public static boolean tryPlantSeeded(ServerLevel level, BlockPos top, RandomSource random, boolean instant) {
        if (!isPlantable(level, top)) {
            return false;
        }
        if (tryPlantFogEye(level, top, random, instant)) {
            return true;
        }
        double chance = Config.FOGGY_GRASS_SEED_CHANCE.get() * density(level, top.below());
        if (random.nextDouble() >= chance) {
            return false;
        }
        if (rollPuffBush(random)) {
            plantPuffBush(level, top, random, instant);
        } else {
            sprout(level, top, random, false);
        }
        return true;
    }

    /** Spread planting: sprout a tuft if the surrounding patch is still below its density cap. */
    public static boolean tryPlantSpread(ServerLevel level, BlockPos top, RandomSource random, boolean instant) {
        if (!isPlantable(level, top)) {
            return false;
        }
        if (tryPlantFogEye(level, top, random, instant)) {
            return true;
        }
        if (countTufts(level, top) >= densityCap(level, top)) {
            return false; // patch already as thick as this temperature allows
        }
        if (rollPuffBush(random)) {
            plantPuffBush(level, top, random, instant);
        } else {
            sprout(level, top, random, true);
        }
        return true;
    }

    // The fog eye's only path into the world: it has no seeding of its own any more, it is just
    // another thing the grass wave can plant at a spot it was already going to visit (see FogMoss and
    // FogMossEvents, which no longer touch FogEyeBlock at all). `top.below()` is the ground the wave
    // found -- moss or coarse dirt, see FogMoss#isSeedGround -- which is exactly what trySeed roots on.
    private static boolean tryPlantFogEye(ServerLevel level, BlockPos top, RandomSource random, boolean instant) {
        BlockPos eye = FogEyeBlock.trySeed(level, top.below(), random);
        if (eye == null) {
            return false;
        }
        if (instant) {
            FogEyeBlock.simulateGrowth(level, eye);
        }
        return true;
    }

    private static boolean rollPuffBush(RandomSource random) {
        return random.nextDouble() < Config.PUFF_BUSH_SEED_CHANCE.get();
    }

    // Live: a sapling that grows over time. Instant: grown out on the spot, bar the odd one kept a sapling.
    private static void plantPuffBush(ServerLevel level, BlockPos top, RandomSource random, boolean instant) {
        if (instant && random.nextDouble() >= Config.PUFF_BUSH_KEEP_SAPLING_CHANCE.get()
                && PuffBushSaplingBlock.grow(level, top, random)) {
            return;
        }
        level.setBlock(top, ModBlocks.PUFF_BUSH_SAPLING.get().defaultBlockState(), UPDATE_CLIENTS);
    }

    private static boolean isPlantable(ServerLevel level, BlockPos top) {
        return isFree(level, top) && FogMoss.isSeedGround(level.getBlockState(top.below()));
    }

    // A spread tuft starts part-grown (random age up to its cap) so it doesn't creep in as bare sprouts.
    private static void sprout(ServerLevel level, BlockPos top, RandomSource random, boolean randomBase) {
        level.setBlock(top, rollAgeAndCap(ModBlocks.FOGGY_GRASS.get().defaultBlockState(), random, randomBase),
                UPDATE_CLIENTS);
        plantedEffects(level, top, random, 0.15, 0.2);
    }

    /**
     * Roll this tuft's own maximum height and its starting height onto {@code base}. {@code randomBase}
     * starts it part-grown, which is what keeps spread growth from creeping in as a rank of bare sprouts.
     * Shared with {@link FoggyGrassSideBlock} so both variants come up with the same raggedness.
     */
    public static BlockState rollAgeAndCap(BlockState base, RandomSource random, boolean randomBase) {
        int cap = 1 + random.nextInt(MAX_AGE);
        int age = randomBase ? random.nextInt(cap + 1) : 0;
        return base.setValue(CAP, cap).setValue(AGE, age);
    }

    /** The sculk-charge puff and occasional spread sound a freshly planted tuft makes. */
    public static void plantedEffects(ServerLevel level, BlockPos at, RandomSource random,
            double chargeY, double popY) {
        level.sendParticles(new SculkChargeParticleOptions(0.0F),
                at.getX() + 0.5, at.getY() + chargeY, at.getZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.0);
        level.sendParticles(ParticleTypes.SCULK_CHARGE_POP,
                at.getX() + 0.5, at.getY() + popY, at.getZ() + 0.5, 3, 0.2, 0.1, 0.2, 0.0);
        if (random.nextInt(6) == 0) {
            level.playSound(null, at, SoundEvents.SCULK_BLOCK_SPREAD, SoundSource.BLOCKS,
                    0.4F, 0.8F + random.nextFloat() * 0.4F);
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
                    if (FogMoss.isSeedGround(level.getBlockState(p))) {
                        moss++;
                        break;
                    }
                }
            }
        }
        return (int) Math.ceil(moss * density(level, centre));
    }

    /**
     * Density fraction (0..1) of moss this biome's temperature supports, interpolated between the cold
     * and warm anchors that also pick the moss variant (fogMossTempBand = [coldMax, warmMin]). Shared
     * with {@link FoggyGrassSideBlock} so the wall tufts thin out in the cold along with the upright ones.
     */
    public static double density(ServerLevel level, BlockPos pos) {
        return FogMoss.tempLerp(level, pos, Config.foggyGrassColdDensity(), Config.foggyGrassWarmDensity());
    }
}
