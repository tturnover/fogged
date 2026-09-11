package com.fogged;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a karst tower wears, decided by the climate it stands in. The rock underneath is the same
 * everywhere; only the dressing changes, so a tower reads as belonging to the landscape it is part of
 * rather than as the same prop stamped into every biome.
 *
 * <p>Three climates, taken from the biome's own base temperature at the tower's foot:
 * <ul>
 *   <li><b>Cold</b> -- snow over the level ground, and icicles hung from whatever the rock overhangs.
 *   <li><b>Temperate</b> -- grass and meadow flowers, which is what the towers had before.
 *   <li><b>Warm</b> -- leaves clinging to the flanks with vines trailing off them, and jungle bushes on
 *       the flats.
 * </ul>
 *
 * <p>Warm asks for rainfall as well as heat, so a desert is not treated as a jungle: at 2.0 a desert is
 * the hottest biome there is, and hanging it with vines would look far stranger than leaving it bare.
 */
public final class KarstDressing {

    public enum Climate { COLD, TEMPERATE, WARM }

    // Vanilla's own reference points: snowy biomes sit at 0.0 and taiga at 0.25, while jungle is 0.95
    // and plains 0.8 -- so these thresholds split roughly where the game itself stops placing snow and
    // starts placing jungle.
    private static final float COLD_BELOW = 0.25F;
    private static final float WARM_FROM = 0.95F;

    // Icicles hang from the underside of the rock -- in practice the lip of the undercut, since the
    // support rule leaves the towers with almost no other overhang.
    private static final int ICICLE_MAX = 4;
    private static final float ICICLE_CHANCE = 0.45F;

    // Where growth and ice may go is settled before these are called -- by the Voronoi seams and the
    // patch field in KarstPillarsFeature -- so what is left here is only raggedness along the edges of
    // what was chosen. Rolling a low chance here as well, as an earlier version did, simply dissolved
    // those seams and patches back into the speckle they were meant to replace.
    private static final float LEAF_CHANCE = 0.85F;
    private static final int VINE_MAX = 7;

    private static final float BUSH_CHANCE = 0.06F;

    // Ice clinging to the flanks. The rock is built so that nothing overhangs anything -- see the
    // support rule in KarstPillarsFeature -- so the only true ceiling on a tower is the lip of the
    // undercut, and that sits up at the waterline. Icicles down the lower flanks therefore cannot be
    // hung from a ceiling; they grip the face instead, exactly as the vines do on a warm tower.
    // 0.7 within a patch that is itself about a quarter of the face, so ice ends up on roughly a sixth
    // of the visible rock -- comfortably inside the quarter it is allowed.
    private static final float FROST_CHANCE = 0.7F;
    private static final int FROST_MAX = 5;

    private KarstDressing() {}

    /**
     * The climate at a tower's foot. Read once per tower, not per block.
     *
     * <p>Sampled from the biome SOURCE rather than from the level. A tower's foot is regularly in a
     * different chunk from the one being generated -- they are wider than a chunk and stand where they
     * like -- and asking the level for a biome there is asking it for a chunk it is not allowed to
     * touch, which ends world generation on the spot with "Requested chunk unavailable". The source
     * answers from the noise alone, so it needs no chunk and gives every chunk of a tower the same
     * answer, which is also what stops one tower wearing two climates.
     */
    public static Climate at(BiomeSource source, net.minecraft.world.level.biome.Climate.Sampler sampler,
            int x, int y, int z) {
        // Biomes are stored per four blocks, so the sampler works in quart coordinates.
        Biome biome = source.getNoiseBiome(x >> 2, y >> 2, z >> 2, sampler).value();
        float temperature = biome.getBaseTemperature();
        if (temperature < COLD_BELOW) {
            return Climate.COLD;
        }
        // Heat alone is not a jungle. Without rain this is a desert or a badlands, and bare rock is
        // right there.
        if (temperature >= WARM_FROM && biome.hasPrecipitation()) {
            return Climate.WARM;
        }
        return Climate.TEMPERATE;
    }

    /** The block that caps level ground on a tower. */
    public static BlockState ground(Climate climate) {
        return switch (climate) {
            case COLD -> Blocks.SNOW_BLOCK.defaultBlockState();
            default -> Blocks.GRASS_BLOCK.defaultBlockState();
        };
    }

    /** What grows on that ground, or {@code null} for bare. */
    public static BlockState plant(Climate climate, RandomSource rnd) {
        float roll = rnd.nextFloat();
        return switch (climate) {
            // Snow is left clean: anything poking through it reads as the snow being a thin dusting,
            // which is the opposite of the solid white cap this is after.
            case COLD -> null;
            case WARM -> roll < 0.34F ? Blocks.FERN.defaultBlockState()
                    : roll < 0.52F ? Blocks.SHORT_GRASS.defaultBlockState()
                    : roll < 0.58F ? Blocks.JUNGLE_SAPLING.defaultBlockState()
                    : null;
            default -> roll < 0.42F ? Blocks.SHORT_GRASS.defaultBlockState()
                    : roll < 0.57F ? Blocks.FERN.defaultBlockState()
                    : roll < 0.63F ? switch (rnd.nextInt(4)) {
                        case 0 -> Blocks.DANDELION.defaultBlockState();
                        case 1 -> Blocks.POPPY.defaultBlockState();
                        case 2 -> Blocks.AZURE_BLUET.defaultBlockState();
                        default -> Blocks.OXEYE_DAISY.defaultBlockState();
                    }
                    : null;
        };
    }

    /**
     * Hang ice from a piece of rock that has nothing under it. Tapers to a point, so it reads as an
     * icicle rather than as a column of ice.
     */
    public static void icicle(WorldGenLevel level, RandomSource rnd, int x, int y, int z) {
        if (rnd.nextFloat() >= ICICLE_CHANCE) {
            return;
        }
        int length = 1 + rnd.nextInt(ICICLE_MAX);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 1; i <= length; i++) {
            pos.set(x, y - i, z);
            if (!level.getBlockState(pos).isAir()) {
                return;
            }
            // Packed ice at the shoulder, clear ice for the thin end: the pair reads as an icicle
            // catching the light along its length.
            level.setBlock(pos, i < length && length > 2
                    ? Blocks.PACKED_ICE.defaultBlockState()
                    : Blocks.ICE.defaultBlockState(), 2);
        }
    }

    /**
     * Clothe an exposed vertical face: a patch of leaves held against the rock, with vines trailing
     * down off it. The leaves are what make it read as growth wrapping the tower rather than as the
     * bare vine strands vanilla hangs off a ceiling.
     */
    public static void drape(WorldGenLevel level, RandomSource rnd, int x, int y, int z, Direction face) {
        if (rnd.nextFloat() >= LEAF_CHANCE) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x + face.getStepX(), y, z + face.getStepZ());
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        // Persistent, or the leaves decay the moment the world starts ticking: there is no log within
        // range of them, which is exactly the case decay is meant to catch.
        level.setBlock(pos, Blocks.JUNGLE_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true)
                .setValue(LeavesBlock.DISTANCE, 7), 2);

        // Vines trail from underneath the patch, gripping the rock face they hang against.
        BlockState vine = Blocks.VINE.defaultBlockState()
                .setValue(VineBlock.PROPERTY_BY_DIRECTION.get(face.getOpposite()), true);
        int length = 1 + rnd.nextInt(VINE_MAX);
        for (int i = 1; i <= length; i++) {
            pos.set(x + face.getStepX(), y - i, z + face.getStepZ());
            if (!level.getBlockState(pos).isAir()) {
                return;
            }
            level.setBlock(pos, vine, 2);
        }
    }

    /**
     * Hang a strand of ice down an exposed vertical face. Packed ice through the body and clear ice at
     * the tip, so it reads as a frozen runnel rather than a blue stripe.
     */
    public static void frost(WorldGenLevel level, RandomSource rnd, int x, int y, int z, Direction face) {
        if (rnd.nextFloat() >= FROST_CHANCE) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int length = 1 + rnd.nextInt(FROST_MAX);
        for (int i = 0; i < length; i++) {
            pos.set(x + face.getStepX(), y - i, z + face.getStepZ());
            if (!level.getBlockState(pos).isAir()) {
                return;
            }
            level.setBlock(pos, i < length - 1 && length > 2
                    ? Blocks.PACKED_ICE.defaultBlockState()
                    : Blocks.ICE.defaultBlockState(), 2);
        }
    }

    /**
     * A sparse-jungle bush on level ground: a stub of log under a ball of leaves, the same shape the
     * vanilla jungle scatters between its big trees.
     */
    public static void bush(WorldGenLevel level, RandomSource rnd, int x, int y, int z) {
        if (rnd.nextFloat() >= BUSH_CHANCE) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        pos.set(x, y + 1, z);
        if (!level.getBlockState(pos).isAir()) {
            return;
        }
        level.setBlock(pos, Blocks.OAK_LOG.defaultBlockState(), 2);

        BlockState leaves = Blocks.JUNGLE_LEAVES.defaultBlockState()
                .setValue(LeavesBlock.PERSISTENT, true)
                .setValue(LeavesBlock.DISTANCE, 1);
        for (int dy = 1; dy <= 2; dy++) {
            int reach = dy == 1 ? 1 : 0;
            for (int dx = -reach; dx <= reach; dx++) {
                for (int dz = -reach; dz <= reach; dz++) {
                    if (dx == 0 && dz == 0 && dy == 1) {
                        continue; // the log's own column
                    }
                    if (Math.abs(dx) == 1 && Math.abs(dz) == 1 && rnd.nextBoolean()) {
                        continue; // ragged corners, so it is a bush and not a cube
                    }
                    pos.set(x + dx, y + 1 + dy, z + dz);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlock(pos, leaves, 2);
                    }
                }
            }
        }
    }
}
