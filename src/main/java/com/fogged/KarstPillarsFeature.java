package com.fogged;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;

/**
 * Groups of stone towers raised from bedrock to around the murk's high-water mark, so a group breaks
 * the surface as a cluster of islands.
 *
 * <p>Two references, mixed per tower rather than averaged, so a group holds both kinds. Ha Long Bay
 * gives the karst tower: round, lumpy, undercut at the waterline, domed on top. Cape Pillar gives the
 * dolerite column: sheer, flat-faced, polygonal in section, flat on top. A tower rolls its own place
 * on that scale -- {@code facetStrength} both flattens its faces into a polygon and smooths away the
 * karst lumpiness -- so neighbours read as the same rock weathered differently.
 *
 * <p>The shape is the point. A cylinder reads as a pipe, so a tower here is a cylinder only in the
 * sense that it has an axis: its radius varies with height (bulges, a taper, a cap) AND with the angle
 * round the axis (a cross-section that is fluted or faceted, and turns slowly as it rises), and the
 * whole axis may lean a few degrees off vertical. The signature undercut -- the notch real karst wears
 * where the water works at it -- is cut at the murk's own high-water mark, so the towers look eaten by
 * the thing they stand in. Flat benches jut from the flanks, and those, with the cap, are the only
 * ground level enough to hold grass.
 *
 * <p><b>Towers are not built by the chunk that owns them.</b> A worldgen feature may only write within
 * a block or so of its own chunk, and one of these leans far further than that: at the maximum tilt a
 * 190-block tower's top lands some 70 blocks from its foot, and everything past the limit would be
 * quietly discarded, leaving towers sheared off mid-air. So a tower is instead DEFINED deterministically
 * -- every one of its parameters is derived from the world seed and its cell on a fixed grid, and from
 * nothing else -- and each chunk independently builds only the slice of any nearby tower that falls
 * inside its own 16x16 column. Neighbouring chunks derive identical towers and their slices meet
 * exactly, so a tower crosses as many chunks as it likes without a seam and without an illegal write.
 *
 * <p>Takes no feature config: everything is read from {@link Config}, so towers are tuned in the same
 * config screen as the rest of the mod rather than by writing datapack JSON.
 */
public class KarstPillarsFeature extends Feature<NoneFeatureConfiguration> {

    // Angular buckets the cross-section is sampled into. The harmonics are evaluated once per bucket
    // per layer instead of once per block, which is what keeps a 180-block tower cheap to raise.
    private static final int ANGULAR_STEPS = 64;

    // Cross-section harmonics: a tower's outline is 1 + sum of these, so it is lumpy and lopsided
    // rather than round. Low orders (2, 3) give the broad lopsidedness, 5 and 7 the vertical fluting.
    private static final int[] LUMP_ORDERS = { 2, 3, 5, 7 };
    private static final double[] LUMP_AMPLITUDES = { 0.20, 0.13, 0.07, 0.04 };

    // The most the harmonics can add to a radius (their amplitudes all in phase), used to bound how
    // far a tower can possibly reach so the candidate search never misses one.
    private static final double LUMP_MAX = 1.0 + 0.20 + 0.13 + 0.07 + 0.04;

    // How fast the cross-section rotates as it rises, in radians per block, at zero facet strength.
    // Kept small and switched off entirely for a columnar tower: a groove that winds as it climbs
    // smears the whole flank into a spiral, and what makes rock look CARVED is that its flutes run
    // straight up. The old value turned a tower through 2.7 radians over its height, which is most of
    // why the sides read as blobs rather than as a cut face.
    private static final double TWIST_PER_BLOCK_MAX = 0.004;

    // Strata: the tower widens DOWNWARD in steps and never inward, so every joint is a shoulder the
    // rock sits back on rather than a lip it hangs off. Stone that juts out over open air is the one
    // thing that reads as a blob stuck to the side instead of a face cut into it -- and a band given a
    // free +/- offset overhangs exactly half the time, which is what the first attempt at strata did.
    //
    // Two scales of banding are summed, at deliberately incommensurate heights, so the steps fall at
    // irregular intervals. One band height per tower stacked into a column of identical slabs.
    private static final double STRATA_TOTAL_MIN = 0.45;  // how much wider the foot is than the crown
    private static final double STRATA_TOTAL_MAX = 1.10;
    private static final double STRATA_FINE_MIN = 6.0;    // band heights, in blocks
    private static final double STRATA_FINE_MAX = 12.0;
    private static final double STRATA_COARSE_MIN = 18.0;
    private static final double STRATA_COARSE_MAX = 34.0;
    private static final double STRATA_EASE_MIN = 0.12;   // share of a band spent easing out to it
    private static final double STRATA_EASE_MAX = 0.32;

    // Bias the widening towards the crown, so most of the stepping happens over the stretch anyone
    // actually sees rather than being spread evenly over a hundred blocks of buried rock.
    private static final double STRATA_CROWN_BIAS = 0.55;

    // Ribs. Each band pushes out over a sector or two of its own, and because the support clamp carries
    // whatever juts out all the way down, a bump started at one height becomes a buttress running from
    // there to the foot. That is where the flank gets its relief: the clamp that removes overhangs also
    // erases anything that merely narrows, so detail has to be built by ADDING material, never by
    // cutting it away.
    private static final int RIBS_PER_BAND = 2;
    private static final double RIB_EXTRA_MAX = 0.30;      // as a share of the tower's radius
    private static final double RIB_HALF_WIDTH_MIN = 0.35; // radians
    private static final double RIB_HALF_WIDTH_MAX = 1.10;


    // The undercut at the murk's high-water mark: how deep the notch bites and how tall it is.
    private static final double NOTCH_DEPTH = 0.35;
    private static final double NOTCH_HALF_HEIGHT = 4.0;

    // Taper (narrowing from base to cap) and cap depth are rolled per tower rather than fixed: a
    // Cape-Pillar column is near enough sheer and flat-topped, a Ha Long tower tapers and domes.
    private static final double TAPER_MIN = 0.02;
    private static final double TAPER_MAX = 0.12;
    // Cap depth as a multiple of the tower's RADIUS, not its height. A cap is a rounding of the top,
    // so it belongs to how wide the tower is: as a fraction of height, the same setting domed a broad
    // tower nicely and drew a slender one out into a 25-block needle with no top to stand on.
    private static final double DOME_RADII_MIN = 0.3;
    private static final double DOME_RADII_MAX = 1.4;
    private static final double DOME_FRACTION_MAX = 0.3;

    // Sides a faceted tower may break into. Real columnar jointing is mostly five- and six-sided.
    private static final int FACET_SIDES_MIN = 4;
    private static final int FACET_SIDES_MAX = 7;

    // At full facet strength the lumps are reduced to this share of themselves -- a flat-faced column
    // should not also be knobbly.
    private static final double LUMP_SUPPRESSION = 0.7;

    // A bench juts this far out, as a share of the tower's radius, and stands this many blocks thick.
    private static final double LEDGE_EXTRA_MIN = 0.35;
    private static final double LEDGE_EXTRA_MAX = 0.6;
    private static final int LEDGE_THICKNESS_MIN = 2;
    private static final int LEDGE_THICKNESS_MAX = 4;
    private static final double LEDGE_HALF_WIDTH_MIN = 0.5;  // radians of the tower it wraps around
    private static final double LEDGE_HALF_WIDTH_MAX = 1.5;

    // Soil under the grass on a bench, in blocks.
    private static final int SOIL_DEPTH = 2;

    // One candidate tower per chunk-sized cell, jittered within it so the grid never shows.
    private static final int CELL = 16;
    private static final int CELL_MARGIN = 3; // blocks kept clear of the cell edge by the jitter

    /** Which way a group laid itself out. Ridges and isles are locatable separately. */
    public enum Layout implements StringRepresentable {
        ANY("any"), RIDGE("ridge"), ISLE("isle");

        public static final Codec<Layout> CODEC = StringRepresentable.fromEnum(Layout::values);

        private final String name;

        Layout(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return name;
        }

        boolean matches(boolean ridge) {
            return this == ANY || (this == RIDGE) == ridge;
        }
    }

    /** A level bench on a tower's flank: flat on top, which is what lets grass take hold. */
    private record Ledge(int topY, int thickness, double angle, double halfWidth, double extra) {}

    /** Everything about one tower, all of it derived from the seed and the cell -- see the class note. */
    private record Tower(double baseX, double baseZ, int bottomY, int topY, double radius,
            double driftX, double driftZ, double[] lumpPhase, long strataSeed, double strataTotal,
            double strataFine, double strataCoarse, double strataEase, double strataBlend,
            double twist, double taper, double dome, int facetSides, double facetStrength,
            double facetPhase, Ledge[] ledges, boolean ridge) {

        double height() {
            return topY - bottomY;
        }

        /** The furthest this tower's surface can be from its base column, horizontally. */
        double maxReach() {
            return Math.hypot(driftX, driftZ) * height()
                    + radius * LUMP_MAX * (1.0 + STRATA_TOTAL_MAX)
                            * (1.0 + LEDGE_EXTRA_MAX + RIB_EXTRA_MAX * RIBS_PER_BAND);
        }
    }

    public KarstPillarsFeature(Codec<NoneFeatureConfiguration> codec) {
        super(codec);
    }

    @Override
    public boolean place(FeaturePlaceContext<NoneFeatureConfiguration> context) {
        if (!Config.GENERATE_PILLARS.get()) {
            return false;
        }
        WorldGenLevel level = context.level();
        BlockPos origin = context.origin();
        int chunkMinX = origin.getX() & ~15;
        int chunkMinZ = origin.getZ() & ~15;

        double waterMark = Config.maxBreathHeight();
        BlockState stone = pillarBlock();
        long seed = level.getSeed();
        int minY = level.getMinBuildHeight();
        int maxY = level.getMaxBuildHeight();

        // The natural surface of this chunk, read BEFORE a single tower block is placed. Once one is
        // up it is itself the highest thing in the column, so asking later would put every skirt on top
        // of the tower rather than round its foot.
        int erosion = Config.PILLAR_BASE_EROSION.getAsInt();
        // Wanted by the skirt (where to pile it, and what to blend it into) and by the dressing (which
        // only belongs above the surface), so it is gathered whenever any of the three is switched on.
        boolean needGround = erosion > 0 || Config.PILLAR_GREENERY.get() || Config.PILLAR_CLIMATE.get();
        int[] groundY = new int[256];
        BlockState[] groundBlock = new BlockState[256];
        if (needGround) {
            BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int column = x + z * 16;
                    int wx = chunkMinX + x;
                    int wz = chunkMinZ + z;
                    groundY[column] = solidGround(level, probe, wx, wz);
                    // The block the ground actually presents here -- grass, sand, gravel, whatever --
                    // so the skirt can dither into it instead of ending as a rim of bare stone.
                    probe.set(wx, groundY[column] - 1, wz);
                    groundBlock[column] = level.getBlockState(probe);
                }
            }
        }

        // Everything needed to ask what biome a point is in without touching its chunk.
        BiomeSource biomeSource = context.chunkGenerator().getBiomeSource();
        net.minecraft.world.level.biome.Climate.Sampler sampler =
                level.getLevel().getChunkSource().randomState().sampler();

        int spacing = Config.PILLAR_GROUP_SPACING.getAsInt();
        double towerReach = worstCaseTowerReach(waterMark);
        int search = Mth.ceil((maxGroupExtent() + towerReach) / spacing) + 1;
        int centreGx = Math.floorDiv(chunkMinX, spacing);
        int centreGz = Math.floorDiv(chunkMinZ, spacing);

        boolean placedAny = false;
        for (int gz = centreGz - search; gz <= centreGz + search; gz++) {
            for (int gx = centreGx - search; gx <= centreGx + search; gx++) {
                Group group = groupAt(seed, gx, gz, spacing);
                if (group == null || !group.reaches(chunkMinX, chunkMinZ, towerReach)) {
                    continue;
                }
                int count = towerCount(group);
                for (int i = 0; i < count; i++) {
                    Tower tower = towerOf(seed, group, i, waterMark, minY, maxY);
                    if (tower == null) {
                        continue;
                    }
                    double reach = tower.maxReach();
                    if (tower.baseX() + reach < chunkMinX || tower.baseX() - reach > chunkMinX + 15
                            || tower.baseZ() + reach < chunkMinZ || tower.baseZ() - reach > chunkMinZ + 15) {
                        continue;
                    }
                    KarstDressing.Climate climate = Config.PILLAR_CLIMATE.get()
                            ? KarstDressing.at(biomeSource, sampler, Mth.floor(tower.baseX()),
                                    tower.topY(), Mth.floor(tower.baseZ()))
                            : KarstDressing.Climate.TEMPERATE;
                    placedAny |= carve(level, tower, stone, waterMark, chunkMinX, chunkMinZ,
                            groundY, groundBlock, erosion, climate);
                }
            }
        }
        return placedAny;
    }

    /**
     * One group of towers. Laid out from its own midpoint rather than inside a patch of a grid, which
     * is what lets a ridge be longer than the distance between one group and the next -- a long crest
     * runs straight past its neighbours, and that overlap is what makes it read as a coastline instead
     * of a clump. Everything about it comes from the world seed and its cell on the group grid.
     */
    private record Group(double anchorX, double anchorZ, boolean ridge, double dirX, double dirZ,
            double halfLength, double isleRadius, long key) {

        /** How far from the anchor this group can put a tower's centre. */
        double extent() {
            return ridge ? halfLength + RIDGE_WANDER : isleRadius;
        }

        boolean reaches(int chunkMinX, int chunkMinZ, double towerReach) {
            double r = extent() + towerReach;
            return anchorX + r >= chunkMinX && anchorX - r <= chunkMinX + 15
                    && anchorZ + r >= chunkMinZ && anchorZ - r <= chunkMinZ + 15;
        }
    }

    // How far a ridge tower may wander off the crest line, and how often the crest simply misses one --
    // enough to notch it here and there without breaking it into separate stacks.
    private static final double RIDGE_WANDER = 9.0;
    private static final double RIDGE_GAP_CHANCE = 0.08;

    // Towers along a crest are stepped at this share of their mean diameter, so consecutive ones
    // overlap and the ridge fuses into a wall rather than standing as a row of spires.
    private static final double RIDGE_STEP_OF_DIAMETER = 0.85;

    // Grid step a cluster scatters its isles on; the count follows from the cluster's area.
    private static final double ISLE_STEP = 22.0;

    // How close to the world origin the guaranteed first group is planted.
    private static final double SPAWN_RADIUS = 100.0;

    // The group whose cell this is. One per cell, its middle jittered well inside so the grid never
    // shows in where groups sit.
    //
    // Crests are free to run through one another. They are laid out from their own middles and pay no
    // attention to where the grid divides the world, so at close spacing they cross constantly -- but
    // the answer to that is to make groups RARE rather than to forbid the crossing, because a pair that
    // does meet is worth coming across. Spacing is the setting that governs it.
    private static Group groupAt(long seed, int gx, int gz, int spacing) {
        RandomSource rnd = RandomSource.create(mix(seed, gx, gz, 0x6C0A9DL));
        double margin = spacing * 0.2;
        double anchorX = gx * (double) spacing + margin + rnd.nextDouble() * (spacing - margin * 2.0);
        double anchorZ = gz * (double) spacing + margin + rnd.nextDouble() * (spacing - margin * 2.0);

        // The cell holding the origin plants its group right next to it, so a new world always starts
        // in sight of one instead of possibly thousands of blocks from the nearest.
        //
        // Drawn from a random source of its own rather than from the one above, so switching this off
        // leaves the rest of the cell's group -- which way it runs, how long it is -- exactly as it
        // would otherwise have been, and only moves it back onto the ordinary jitter.
        if (gx == 0 && gz == 0 && Config.PILLAR_SPAWN_GROUP.get()) {
            RandomSource near = RandomSource.create(mix(seed, 0, 0, 0x59A00EL));
            double angle = near.nextDouble() * Math.PI * 2.0;
            double radius = SPAWN_RADIUS * Math.sqrt(near.nextDouble());
            anchorX = Math.cos(angle) * radius;
            anchorZ = Math.sin(angle) * radius;
        }

        boolean ridge = rnd.nextDouble() < Config.PILLAR_RIDGE_CHANCE.get();

        double angle = rnd.nextDouble() * Math.PI; // a line, so half a turn covers every direction
        int lengthMin = Config.PILLAR_RIDGE_LENGTH_MIN.getAsInt();
        int lengthMax = Math.max(lengthMin, Config.PILLAR_RIDGE_LENGTH_MAX.getAsInt());
        double length = lengthMin + rnd.nextDouble() * (lengthMax - lengthMin);
        return new Group(anchorX, anchorZ, ridge, Math.cos(angle), Math.sin(angle), length * 0.5,
                Config.PILLAR_ISLE_RADIUS.getAsInt(), mix(seed, gx, gz, 0x6C0A9DL));
    }

    private static int towerCount(Group group) {
        if (group.ridge()) {
            return Math.max(1, Mth.floor(group.halfLength() * 2.0 / ridgeStep()) + 1);
        }
        double r = group.isleRadius();
        return Math.max(1, Mth.floor(Math.PI * r * r / (ISLE_STEP * ISLE_STEP)));
    }

    private static double ridgeStep() {
        double mean = Config.PILLAR_RADIUS_MIN.get() + Math.max(Config.PILLAR_RADIUS_MIN.get(),
                Config.PILLAR_RADIUS_MAX.get());
        return Math.max(4.0, mean * RIDGE_STEP_OF_DIAMETER);
    }

    // The group's i-th tower, or null where it skips one. Note that a ridge tower rolls its own build
    // exactly as a lone isle does -- same radius range, same lean, same weathering. Smoothing them
    // along the crest would make a fin of one thickness, and the point of a ridge here is a run of
    // individual towers that happen to have grown into each other.
    private static Tower towerOf(long seed, Group group, int index, double waterMark,
            int minBuildHeight, int maxBuildHeight) {
        RandomSource rnd = RandomSource.create(mix(group.key(), index, 0, 0x70B3E5L));
        double baseX;
        double baseZ;
        if (group.ridge()) {
            if (rnd.nextDouble() < RIDGE_GAP_CHANCE) {
                return null; // a notch in the crest
            }
            double step = ridgeStep();
            double along = -group.halfLength() + index * step + (rnd.nextDouble() - 0.5) * step * 0.5;
            double across = (rnd.nextDouble() - 0.5) * 2.0 * RIDGE_WANDER;
            baseX = group.anchorX() + group.dirX() * along - group.dirZ() * across;
            baseZ = group.anchorZ() + group.dirZ() * along + group.dirX() * across;
        } else {
            if (rnd.nextDouble() >= Config.PILLAR_DENSITY.get()) {
                return null;
            }
            // Square-rooted so the scatter is even over the disc instead of piling into the middle.
            double r = group.isleRadius() * Math.sqrt(rnd.nextDouble());
            double a = rnd.nextDouble() * Math.PI * 2.0;
            baseX = group.anchorX() + Math.cos(a) * r;
            baseZ = group.anchorZ() + Math.sin(a) * r;
        }
        return buildTower(rnd, baseX, baseZ, waterMark, minBuildHeight, maxBuildHeight, group.ridge());
    }

    // Everything about a tower's own build, rolled the same way wherever it stands.
    private static Tower buildTower(RandomSource rnd, double baseX, double baseZ, double waterMark,
            int minBuildHeight, int maxBuildHeight, boolean ridge) {
        int bottomY = minBuildHeight + 1; // +1 leaves the bedrock floor itself alone
        int variance = Config.PILLAR_TOP_VARIANCE.getAsInt();
        int topY = Mth.floor(waterMark) + (variance == 0 ? 0 : rnd.nextInt(variance * 2 + 1) - variance);
        topY = Math.min(topY, maxBuildHeight - 1);
        if (topY <= bottomY) {
            return null;
        }

        double radiusMin = Config.PILLAR_RADIUS_MIN.get();
        double radiusMax = Math.max(radiusMin, Config.PILLAR_RADIUS_MAX.get());
        double radius = radiusMin + rnd.nextDouble() * (radiusMax - radiusMin);

        // Lean: a random direction, a random amount up to the configured limit. Held as the horizontal
        // drift per block of height, which is all the placement loop needs.
        double tilt = Math.toRadians(Config.PILLAR_TILT.get()) * rnd.nextDouble();
        double azimuth = rnd.nextDouble() * Math.PI * 2.0;
        double driftX = Math.tan(tilt) * Math.cos(azimuth);
        double driftZ = Math.tan(tilt) * Math.sin(azimuth);

        double[] lumpPhase = new double[LUMP_ORDERS.length];
        for (int i = 0; i < lumpPhase.length; i++) {
            lumpPhase[i] = rnd.nextDouble() * Math.PI * 2.0;
        }
        long strataSeed = rnd.nextLong();
        double strataTotal = STRATA_TOTAL_MIN + rnd.nextDouble() * (STRATA_TOTAL_MAX - STRATA_TOTAL_MIN);
        double strataFine = STRATA_FINE_MIN + rnd.nextDouble() * (STRATA_FINE_MAX - STRATA_FINE_MIN);
        double strataCoarse = STRATA_COARSE_MIN + rnd.nextDouble() * (STRATA_COARSE_MAX - STRATA_COARSE_MIN);
        double strataEase = STRATA_EASE_MIN + rnd.nextDouble() * (STRATA_EASE_MAX - STRATA_EASE_MIN);
        double strataBlend = 0.3 + rnd.nextDouble() * 0.4;

        // Where this tower sits between the two references: 0 is a rounded, lumpy karst stack, 1 a
        // sheer flat-faced column. Squared, so most of a group leans karst and the hard-edged columns
        // are the exception that stands out.
        double facetStrength = rnd.nextDouble() * rnd.nextDouble();
        double taper = TAPER_MAX - (TAPER_MAX - TAPER_MIN) * facetStrength;
        double domeRadii = DOME_RADII_MAX - (DOME_RADII_MAX - DOME_RADII_MIN) * facetStrength;
        double dome = Mth.clamp(radius * domeRadii / (topY - bottomY), 0.01, DOME_FRACTION_MAX);
        int facetSides = FACET_SIDES_MIN + rnd.nextInt(FACET_SIDES_MAX - FACET_SIDES_MIN + 1);
        double facetPhase = rnd.nextDouble() * Math.PI * 2.0;

        // A columnar tower does not turn at all; a karst one turns gently.
        double twist = TWIST_PER_BLOCK_MAX * (1.0 - facetStrength);

        Ledge[] ledges = rollLedges(rnd, bottomY, topY, radius, waterMark);

        return new Tower(baseX, baseZ, bottomY, topY, radius, driftX, driftZ, lumpPhase,
                strataSeed, strataTotal, strataFine, strataCoarse, strataEase, strataBlend,
                twist, taper, dome, facetSides, facetStrength, facetPhase, ledges, ridge);
    }

    // The benches on one tower's flanks. Kept clear of the very bottom and the cap, so a bench always
    // has tower above and below it and reads as a step rather than a skirt or a brim.
    private static Ledge[] rollLedges(RandomSource rnd, int bottomY, int topY, double radius,
            double waterMark) {
        int most = Config.PILLAR_LEDGES.getAsInt();
        if (most <= 0) {
            return NO_LEDGES;
        }
        // Only along the stretch that is actually seen and can actually be green. Spread evenly from
        // bedrock up, nearly every bench landed a hundred blocks down -- buried in the terrain the
        // tower was driven through, and far below the line where anything grows.
        int low = Math.max(bottomY + 8, Mth.floor(waterMark) - Config.PILLAR_GREENERY_DEPTH.getAsInt() - 8);
        int high = topY - 6;
        if (high <= low) {
            return NO_LEDGES;
        }
        int count = rnd.nextInt(most + 1);
        Ledge[] ledges = new Ledge[count];
        for (int i = 0; i < count; i++) {
            ledges[i] = new Ledge(
                    low + rnd.nextInt(high - low),
                    LEDGE_THICKNESS_MIN + rnd.nextInt(LEDGE_THICKNESS_MAX - LEDGE_THICKNESS_MIN + 1),
                    rnd.nextDouble() * Math.PI * 2.0,
                    LEDGE_HALF_WIDTH_MIN + rnd.nextDouble() * (LEDGE_HALF_WIDTH_MAX - LEDGE_HALF_WIDTH_MIN),
                    radius * (LEDGE_EXTRA_MIN + rnd.nextDouble() * (LEDGE_EXTRA_MAX - LEDGE_EXTRA_MIN)));
        }
        return ledges;
    }

    private static final Ledge[] NO_LEDGES = new Ledge[0];

    /** One horizontal slice of a tower: where its axis is at that height, and its outline round it. */
    private record Layer(double cx, double cz, double[] angular, double widest) {

        /** Whether the tower occupies this column at this height. Pure -- works outside any chunk. */
        boolean contains(int x, int z) {
            return within(x, z, 0.0);
        }

        /** How far outside the rock face this column lies, in blocks. Negative inside. */
        double beyond(int x, int z) {
            double ox = x + 0.5 - cx;
            double oz = z + 0.5 - cz;
            double theta = Math.atan2(oz, ox);
            int bucket = Math.floorMod(
                    Mth.floor(theta / (Math.PI * 2.0) * ANGULAR_STEPS + 0.5), ANGULAR_STEPS);
            return Math.sqrt(ox * ox + oz * oz) - angular[bucket];
        }

        /** As {@link #contains}, with every spoke lengthened by {@code extra} -- used for the skirt. */
        boolean within(int x, int z, double extra) {
            double ox = x + 0.5 - cx;
            double oz = z + 0.5 - cz;
            double distSqr = ox * ox + oz * oz;
            double reach = widest + extra;
            if (distSqr > reach * reach) {
                return false; // outside even the longest spoke, so no need to find the angle
            }
            double theta = Math.atan2(oz, ox);
            int bucket = Math.floorMod(
                    Mth.floor(theta / (Math.PI * 2.0) * ANGULAR_STEPS + 0.5), ANGULAR_STEPS);
            double spoke = angular[bucket] + extra;
            return distSqr <= spoke * spoke;
        }
    }

    // The tower's slice at height y, or null where it has none. Writes into the supplied table so the
    // per-layer loop can keep two of them alive without allocating each time round.
    //
    // {@code support} carries the finished outline of the slice ABOVE this one, and no spoke here is
    // allowed to come in shorter than its counterpart there. That single clamp is what guarantees the
    // rock never hangs over open air: whatever juts out at one height is carried by at least as much
    // rock all the way down, so a bench grows its own buttress and a stratum its own shoulder. Shapes
    // alone cannot promise this -- a protruding shelf is unsupported by definition, and the earlier
    // attempt to slope its underside only tilted the overhang instead of removing it.
    //
    // The undercut is the deliberate exception, and the only one.
    private static Layer layer(Tower tower, int y, double waterMark, double[] angular, double[] support) {
        if (y < tower.bottomY() || y > tower.topY()) {
            return null;
        }
        double t = (y - tower.bottomY()) / tower.height();
        double profile = verticalProfile(tower, t, y, waterMark);
        if (profile <= 0.0) {
            return null;
        }
        double layerRadius = tower.radius() * profile;
        double twist = (y - tower.bottomY()) * tower.twist();
        // A faceted tower is a smoother one: the lumps are what make karst knobbly, and a dolerite
        // column has none of them.
        double lumpScale = 1.0 - LUMP_SUPPRESSION * tower.facetStrength();
        double sector = Math.PI * 2.0 / tower.facetSides();
        // Inside the undercut the tower is MEANT to draw back in under itself, so the support clamp
        // stands down for those few blocks.
        boolean undercutting = Math.abs(y - waterMark) < NOTCH_HALF_HEIGHT;

        double widest = 0.0;
        for (int a = 0; a < ANGULAR_STEPS; a++) {
            double theta = a * (Math.PI * 2.0 / ANGULAR_STEPS);

            double lump = 1.0;
            for (int i = 0; i < LUMP_ORDERS.length; i++) {
                lump += LUMP_AMPLITUDES[i] * lumpScale
                        * Math.cos(LUMP_ORDERS[i] * (theta + twist) + tower.lumpPhase()[i]);
            }

            // Flat faces: the radial extent of a regular polygon, blended in by facetStrength. At full
            // strength this is exactly a polygon of facetSides sides; at zero it leaves the circle be.
            double intoSector = Mth.positiveModulo(theta + twist - tower.facetPhase(), sector) - sector * 0.5;
            double polygon = Math.cos(Math.PI / tower.facetSides()) / Math.cos(intoSector);
            double facet = 1.0 + tower.facetStrength() * (polygon - 1.0);

            double r = layerRadius * lump * facet + ledgeBoost(tower, y, theta)
                    + ribBoost(tower, y, theta);
            if (!undercutting) {
                r = Math.max(r, support[a]);
            }
            angular[a] = Math.max(0.0, r);
            widest = Math.max(widest, angular[a]);
        }
        if (widest <= 0.0) {
            return null;
        }
        double cx = tower.baseX() + tower.driftX() * (y - tower.bottomY());
        double cz = tower.baseZ() + tower.driftZ() * (y - tower.bottomY());
        return new Layer(cx, cz, angular, widest);
    }

    // How far this height's ribs push the outline out at this angle. Constant through a band and
    // reshuffled at every boundary, so a rib begins abruptly at a joint -- and the support clamp then
    // carries it down from there.
    private static double ribBoost(Tower tower, int y, double theta) {
        int band = Math.floorDiv(tower.topY() - y, Math.max(1, Mth.floor(tower.strataFine())));
        double boost = 0.0;
        for (int i = 0; i < RIBS_PER_BAND; i++) {
            long hash = mix(tower.strataSeed(), band, i, 0x81BBEDL);
            double angle = ((hash >>> 40) / (double) (1L << 24)) * Math.PI * 2.0;
            double amount = ((hash >>> 16) & 0xFFFFFL) / (double) (1L << 20);
            double width = ((hash >>> 4) & 0xFFFL) / (double) (1L << 12);
            double halfWidth = Math.toDegrees(
                    RIB_HALF_WIDTH_MIN + width * (RIB_HALF_WIDTH_MAX - RIB_HALF_WIDTH_MIN));
            double apart = Math.abs(Mth.wrapDegrees(Math.toDegrees(theta - angle)));
            if (apart >= halfWidth) {
                continue;
            }
            double f = 1.0 - apart / halfWidth;
            boost += tower.radius() * RIB_EXTRA_MAX * amount * amount * f * f * (3.0 - 2.0 * f);
        }
        return boost;
    }

    // How far a bench pushes the outline out at this height and angle.
    //
    // Flat on top and abrupt at the bottom, deliberately: holding the extra constant through the
    // bench's whole thickness is what keeps its top level enough to grass, and the buttress that
    // carries it is not drawn here at all -- the support clamp in layer() grows one automatically out
    // of whatever the bench needs, which is both simpler and impossible to get wrong.
    private static double ledgeBoost(Tower tower, int y, double theta) {
        double boost = 0.0;
        for (Ledge ledge : tower.ledges()) {
            if (y > ledge.topY() || y <= ledge.topY() - ledge.thickness()) {
                continue;
            }
            double apart = Math.abs(Mth.wrapDegrees(Math.toDegrees(theta - ledge.angle())));
            double halfWidth = Math.toDegrees(ledge.halfWidth());
            if (apart >= halfWidth) {
                continue;
            }
            // Eased across the sector so a bench tapers into the flank at its ends rather than
            // stopping dead. That easing is horizontal, so it does not tilt the top.
            double f = 1.0 - apart / halfWidth;
            boost += ledge.extra() * f * f * (3.0 - 2.0 * f);
        }
        return boost;
    }

    // Build the part of one tower that falls inside this chunk.
    //
    // Walked from the crown DOWN, not up, because the support clamp in layer() only makes sense in that
    // direction: each slice has to know what it is holding up before it can be widened to hold it.
    private static boolean carve(WorldGenLevel level, Tower tower, BlockState stone, double waterMark,
            int chunkMinX, int chunkMinZ, int[] groundY, BlockState[] groundBlock, int erosion,
            KarstDressing.Climate climate) {
        // Three rotating outline tables, because deciding whether a surface is level needs the slice
        // below it as well as the one above: below says how far the ground drops away, above says
        // whether this is a surface at all.
        double[][] tables = { new double[ANGULAR_STEPS], new double[ANGULAR_STEPS], new double[ANGULAR_STEPS] };
        double[] support = new double[ANGULAR_STEPS]; // the finished outline of the slice just above
        // Blocks of soil still owed to each column of this chunk. Carving runs downward, so a column
        // that grows grass cannot lay the dirt beneath it there and then -- those blocks have not been
        // reached yet, and writing them early would only be overwritten on the way past. It leaves a
        // count behind instead, and the layers below spend it.
        int[] soilOwed = new int[256];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        boolean placedAny = false;

        boolean greenery = Config.PILLAR_GREENERY.get();
        double greenLine = waterMark - Config.PILLAR_GREENERY_DEPTH.getAsInt();
        // The flanks are dressed further down than the level ground is: greenery belongs near the top
        // where the light is, but ice and vines run right down the visible face of the rock.
        double faceLine = waterMark - FACE_DRESS_DEPTH;
        long seed = level.getSeed();
        boolean bands = Config.PILLAR_STONE_BANDS.get() && stone.is(Blocks.STONE);
        // The bed fields depend only on x and z, so they are worked out once for the chunk's columns
        // rather than per block -- a tower is some tens of thousands of blocks over 256 columns.
        Bedding bedding = bands ? rollBedding(tower, stone) : null;


        // How far the skirt reaches at each column, wobbled so it heaps unevenly round the foot.
        double[] apron = new double[256];
        if (erosion > 0) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    apron[x + z * 16] = erosion * (0.45 + 1.1
                            * bedNoise3(tower.strataSeed() + 29L, chunkMinX + x, 0, chunkMinZ + z,
                                    APRON_NOISE_CELL));
                }
            }
        }

        int slot = 0;
        Layer above = null;
        Layer current = layer(tower, tower.topY(), waterMark, tables[slot], support);
        carrySupport(support, current);

        for (int y = tower.topY(); y >= tower.bottomY(); y--) {
            int nextSlot = (slot + 1) % tables.length;
            Layer below = layer(tower, y - 1, waterMark, tables[nextSlot], support);
            carrySupport(support, below);

            if (current != null) {
                // Clip the layer's own bounding box to this chunk, so a tower crossing a chunk line
                // costs each side only the columns it actually owns.
                double scan = current.widest() + erosion * 1.6;
                int x0 = Math.max(chunkMinX, Mth.floor(current.cx() - scan));
                int x1 = Math.min(chunkMinX + 15, Mth.floor(current.cx() + scan));
                int z0 = Math.max(chunkMinZ, Mth.floor(current.cz() - scan));
                int z1 = Math.min(chunkMinZ + 15, Mth.floor(current.cz() + scan));
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) {
                        int column = (x - chunkMinX) + (z - chunkMinZ) * 16;
                        boolean inTower = current.contains(x, z);
                        // The skirt: a cone of scree that is widest where the tower meets the ground
                        // and has thinned to nothing a few blocks up it.
                        double slack = 0.0;
                        if (!inTower && erosion > 0) {
                            double aboveGround = y - groundY[column];
                            double rise = apron[column] * APRON_RISE_PER_BLOCK;
                            if (aboveGround >= 0.0 && aboveGround < rise && rise > 0.0) {
                                double t = 1.0 - aboveGround / rise;
                                slack = apron[column] * t * t;
                            }
                        }
                        if (!inTower && (slack <= 0.0 || !current.within(x, z, slack))) {
                            continue;
                        }
                        pos.set(x, y, z);
                        if (level.getBlockState(pos).is(Blocks.BEDROCK)) {
                            continue; // never punch through the floor of the world
                        }
                        boolean ground = inTower && greenery && y >= greenLine
                                && y >= groundY[column]
                                && (above == null || !above.contains(x, z))
                                && isLevel(current, below, x, z);
                        if (ground && plantable(level, x, y + 1, z)) {
                            dress(level, climate, seed, x, y, z);
                            soilOwed[column] = SOIL_DEPTH;
                        } else if (soilOwed[column] > 0) {
                            soilOwed[column]--;
                            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
                        } else {
                            BlockState rock = stone;
                            if (bands) {
                                double ox = x + 0.5 - current.cx();
                                double oz = z + 0.5 - current.cz();
                                double widest = current.widest();
                                double distFrac = (ox * ox + oz * oz) / Math.max(1.0e-6, widest * widest);
                                rock = rockAt(tower, bedding, x, y, z, distFrac, stone);
                            }
                            // Out on the skirt, dither between the tower's rock and whatever the ground
                            // here is made of, weighted so the scree is pure rock against the cliff and
                            // has given way to the local surface by its outer edge. A skirt of unbroken
                            // stone ends at a rim you can trace with your eye; interleaving the two
                            // makes the pile read as something the ground swallowed.
                            if (!inTower && slack > 0.0 && groundBlock[column] != null) {
                                double into = Mth.clamp(current.beyond(x, z) / slack, 0.0, 1.0);
                                if (dither(seed, x, y, z) < into * into) {
                                    rock = groundBlock[column];
                                }
                            }
                            level.setBlock(pos, rock, 2);
                        }
                        placedAny = true;

                        // Anything hung off the rock goes on last, once this block is known to be
                        // there for it to hang from.
                        // Dressing sits on the part of the tower that is out in the open. The height
                        // bands alone were not enough: a tower driven through a cave has bare air
                        // against its flank down there, and grew vines and ice in the dark.
                        if (inTower && y >= faceLine && y >= groundY[column]
                                && climate != KarstDressing.Climate.TEMPERATE) {
                            boolean cold = climate == KarstDressing.Climate.COLD;
                            // Decided once for the block, not once per face: which patch of rock this
                            // is, or which seam runs through it, has nothing to do with which way a
                            // given face happens to point.
                            boolean allowed = cold ? icy(tower, x, y, z) : vineLine(tower, x, y, z);
                            if (!allowed) {
                                continue;
                            }
                            // A true ceiling is rare -- the support rule leaves the undercut as very
                            // nearly the only one -- so ice mostly grips the faces instead.
                            if (cold && (below == null || !below.contains(x, z))) {
                                KarstDressing.icicle(level, positional(seed, x, y, z, 0x1C1CL), x, y, z);
                            }
                            for (Direction face : HORIZONTAL) {
                                if (current.contains(x + face.getStepX(), z + face.getStepZ())) {
                                    continue; // not an exposed face
                                }
                                RandomSource faceRnd = positional(seed, x, y, z,
                                        0x71E5L + face.get2DDataValue());
                                if (cold) {
                                    KarstDressing.frost(level, faceRnd, x, y, z, face);
                                } else {
                                    KarstDressing.drape(level, faceRnd, x, y, z, face);
                                }
                            }
                        }
                    }
                }
            }

            above = current;
            current = below;
            slot = nextSlot;
        }
        return placedAny;
    }

    // The height of the real floor in this column -- the bed a scree slope could actually rest on.
    //
    // Neither heightmap answers this on its own. WORLD_SURFACE is merely "not air", so it counts the
    // sea surface and the ice on top of it, and the skirt was left heaped over open water. OCEAN_FLOOR
    // is "blocks motion", which drops water but NOT ice, because ice blocks motion perfectly well -- so
    // over a frozen sea it still lands on the lid. Hence starting from the ocean floor and then walking
    // down through any ice, and the water beneath it, to whatever is actually holding all that up.
    private static int solidGround(WorldGenLevel level, BlockPos.MutableBlockPos probe, int x, int z) {
        int y = level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z);
        int floor = level.getMinBuildHeight() + 1;
        while (y > floor) {
            probe.set(x, y - 1, z);
            BlockState below = level.getBlockState(probe);
            if (below.isAir() || !below.getFluidState().isEmpty() || below.is(BlockTags.ICE)) {
                y--;
                continue;
            }
            break;
        }
        return y;
    }

    // Hand this slice's finished outline down to the next one as the minimum it must carry.
    private static void carrySupport(double[] support, Layer layer) {
        if (layer == null) {
            return; // a gap holds nothing up, so the requirement passes through unchanged
        }
        System.arraycopy(layer.angular(), 0, support, 0, support.length);
    }

    // Whether the tower's surface here is level enough to hold soil: every neighbouring column has to
    // carry ground within a block of this one. A cap or a bench passes; the lip of the undercut and any
    // face that falls away by two blocks or more does not, and stays bare stone.
    //
    // Note what this does NOT ask: that the neighbours be solid at the SAME height. Nothing on a
    // rounded cap ever is -- each ring of it ends one block lower than the last, so requiring that left
    // every domed tower completely bald. Asking about the drop instead is what "flat" actually means.
    //
    // Tested against the geometry rather than against blocks already placed, so it gives the same
    // answer for a column on a chunk border as for one in the middle -- otherwise the grass would stop
    // dead at every chunk line.
    private static boolean isLevel(Layer current, Layer below, int x, int z) {
        return supported(current, below, x - 1, z) && supported(current, below, x + 1, z)
                && supported(current, below, x, z - 1) && supported(current, below, x, z + 1);
    }

    // Ground under this neighbouring column at this height or the one under it -- so a step of one is
    // still level ground, and a step of two is a drop.
    private static boolean supported(Layer current, Layer below, int x, int z) {
        return current.contains(x, z) || (below != null && below.contains(x, z));
    }

    // Whether there is open sky-side air directly above -- a tower driven up through a mountain has
    // stone over most of it, and soil buried in rock is just a brown stripe.
    private static boolean plantable(WorldGenLevel level, int x, int y, int z) {
        return level.getBlockState(new BlockPos(x, y, z)).isAir();
    }

    // How far below the waterline the flanks are still dressed with ice or vines.
    private static final int FACE_DRESS_DEPTH = 72;

    private static final Direction[] HORIZONTAL = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };

    // The level ground of a tower and whatever stands on it, according to its climate. The soil beneath
    // is left to the carve loop -- see soilOwed.
    //
    // Every roll comes from the position, not from the chunk's generator, so the same seed grows the
    // same thing on the same block however the world was explored.
    private static void dress(WorldGenLevel level, KarstDressing.Climate climate, long seed,
            int x, int y, int z) {
        level.setBlock(new BlockPos(x, y, z), KarstDressing.ground(climate), 2);
        RandomSource rnd = positional(seed, x, z, 0xF011A6EL);

        if (climate == KarstDressing.Climate.WARM) {
            KarstDressing.bush(level, rnd, x, y, z);
            if (!level.getBlockState(new BlockPos(x, y + 1, z)).isAir()) {
                return; // the bush took the space
            }
        }
        BlockState plant = KarstDressing.plant(climate, rnd);
        if (plant != null) {
            level.setBlock(new BlockPos(x, y + 1, z), plant, 2);
        }
    }

    // A repeatable value in [0, 1) for one block, for scattering decisions that need no other state.
    private static double dither(long seed, int x, int y, int z) {
        return ((mix(seed ^ (y * 0x9E3779B97F4A7C15L), x, z, 0xD177E4L) >>> 11) * 0x1.0p-53);
    }

    private static RandomSource positional(long seed, int a, int b, long salt) {
        return RandomSource.create(mix(seed, a, b, salt));
    }

    private static RandomSource positional(long seed, int x, int y, int z, long salt) {
        return RandomSource.create(mix(seed ^ (y * 0x9E3779B97F4A7C15L), x, z, salt));
    }

    // Radius multiplier at height fraction t (world height y), before the cross-section is applied.
    private static double verticalProfile(Tower tower, double t, int y, double waterMark) {
        double profile = 1.0 - tower.taper() * t;

        // Widen downward through the strata: flat through each band, easing out to the next over the
        // top of it. Monotonic by construction, so no band can ever hang over the one below.
        profile *= 1.0 + strataWiden(tower, y);

        // The undercut: a notch bitten out at the murk's high-water mark, deepest exactly at the line
        // and easing out over a few blocks either side. A tower that stops short of the mark never
        // reaches it and simply goes without.
        double fromMark = Math.abs(y - waterMark);
        if (fromMark < NOTCH_HALF_HEIGHT) {
            double bite = 1.0 - fromMark / NOTCH_HALF_HEIGHT;
            profile *= 1.0 - NOTCH_DEPTH * bite * bite;
        }

        // The cap. A shallow one on a faceted tower barely rounds the rim and leaves a broad flat top
        // to stand on; a deep one on a karst tower domes it off.
        if (t > 1.0 - tower.dome()) {
            double intoDome = (t - (1.0 - tower.dome())) / tower.dome(); // 0 at the shoulder, 1 at the tip
            profile *= Math.sqrt(Math.max(0.0, 1.0 - intoDome * intoDome));
        }
        return profile;
    }

    // The furthest any tower could possibly reach from its own base at the current settings -- the
    // tallest one leaning hardest, at the widest radius, with every rib and bench at full stretch.
    private static double worstCaseTowerReach(double waterMark) {
        double tallest = waterMark + Config.PILLAR_TOP_VARIANCE.getAsInt() + 64.0;
        double radius = Math.max(Config.PILLAR_RADIUS_MIN.get(), Config.PILLAR_RADIUS_MAX.get());
        return Math.tan(Math.toRadians(Config.PILLAR_TILT.get())) * tallest
                + radius * LUMP_MAX * (1.0 + STRATA_TOTAL_MAX)
                        * (1.0 + LEDGE_EXTRA_MAX + RIB_EXTRA_MAX * RIBS_PER_BAND);
    }

    // The furthest a group can put a tower's centre from its own anchor: half the longest ridge, or a
    // cluster's radius, whichever the settings allow to be bigger.
    private static double maxGroupExtent() {
        double ridge = Math.max(Config.PILLAR_RIDGE_LENGTH_MIN.getAsInt(),
                Config.PILLAR_RIDGE_LENGTH_MAX.getAsInt()) * 0.5 + RIDGE_WANDER;
        return Math.max(ridge, Config.PILLAR_ISLE_RADIUS.getAsInt());
    }

    /**
     * The nearest tower to {@code (fromX, fromZ)} within {@code maxBlocks}, or {@code null} when there
     * is none in range. Returns its base column and top Y packed as a {@link BlockPos}. {@code want}
     * narrows the search to ridges or to isles.
     *
     * <p>Loads nothing. Every group, and every tower in it, is a pure function of the seed and the
     * group's cell (see {@link Group}), so finding one is arithmetic over the group grid rather than a
     * chunk-by-chunk search -- which is why this can answer instantly at any range, where locating a
     * real structure cannot.
     */
    public static BlockPos findNearest(long seed, int fromX, int fromZ, int maxBlocks,
            double waterMark, int minBuildHeight, int maxBuildHeight, Layout want) {
        int spacing = Config.PILLAR_GROUP_SPACING.getAsInt();
        int centreGx = Math.floorDiv(fromX, spacing);
        int centreGz = Math.floorDiv(fromZ, spacing);
        double slack = maxGroupExtent() + worstCaseTowerReach(waterMark);
        int maxRing = maxBlocks / spacing + Mth.ceil(slack / spacing) + 1;

        Tower best = null;
        double bestDistSqr = (double) maxBlocks * maxBlocks;

        for (int ring = 0; ring <= maxRing; ring++) {
            // Stop on distance, never on ring count. The grid is square and the distance is not, so a
            // group several rings further out can still hold the nearer tower; what CAN be relied on is
            // that nothing in ring r sits closer than r*spacing - (spacing-1), less the reach a group
            // has beyond its own cell. Once even that floor beats the best found, no later ring can
            // improve on it.
            double ringFloor = (double) ring * spacing - (spacing - 1) - slack;
            if (best != null && ringFloor > 0.0 && ringFloor * ringFloor > bestDistSqr) {
                break;
            }
            for (int gz = centreGz - ring; gz <= centreGz + ring; gz++) {
                for (int gx = centreGx - ring; gx <= centreGx + ring; gx++) {
                    if (Math.abs(gx - centreGx) != ring && Math.abs(gz - centreGz) != ring) {
                        continue; // interior cells were covered by an earlier ring
                    }
                    Group group = groupAt(seed, gx, gz, spacing);
                    if (group == null || !want.matches(group.ridge())) {
                        continue;
                    }
                    int count = towerCount(group);
                    for (int i = 0; i < count; i++) {
                        Tower tower = towerOf(seed, group, i, waterMark, minBuildHeight, maxBuildHeight);
                        if (tower == null) {
                            continue;
                        }
                        double dx = tower.baseX() - fromX;
                        double dz = tower.baseZ() - fromZ;
                        double distSqr = dx * dx + dz * dz;
                        if (distSqr < bestDistSqr) {
                            bestDistSqr = distSqr;
                            best = tower;
                        }
                    }
                }
            }
        }
        return best == null ? null
                : new BlockPos(Mth.floor(best.baseX()), best.topY(), Mth.floor(best.baseZ()));
    }

    // The debris skirt rises this many blocks for every block it spreads outward, so a wider setting
    // gives a longer, shallower scree slope rather than a taller heap.
    private static final double APRON_RISE_PER_BLOCK = 0.9;

    // Scale of the wobble in the skirt's reach, so it piles unevenly round the foot instead of ringing
    // it like a collar.
    private static final double APRON_NOISE_CELL = 11.0;

    // --- bedding, after Create's LayeredOreFeature (com.simibubi.create.infrastructure.worldgen) ---
    //
    // Five things there that a plain stack of horizontal bands does not do, and between them they are
    // the whole difference in how it reads:
    //
    //  1. the layers stack along a TILTED axis, so bedding dips across the rock instead of lying flat;
    //  2. every layer rolls its own thickness, so the banding has no rhythm to it;
    //  3. a layer is never the same rock as the one before it, so bands always show against each other;
    //  4. the boundaries are displaced by 3D noise, so they wander in all three axes, not just two;
    //  5. each layer reaches only so far out from the middle, and that cutoff is roughened per block,
    //     so layers pinch out at the surface at different distances.
    //
    // The one deliberate departure: Create's deposits are small blobs, so any dip reads fine on them.
    // Over a tower a hundred and ninety blocks tall a shallow axis would turn the bedding on its side
    // into vertical stripes, so the axis is held near enough upright to stay bedding.
    private static final double AXIS_MIN_VERTICALITY = 0.60;
    private static final int LAYER_THICKNESS_MIN = 1;
    private static final int LAYER_THICKNESS_MAX = 5;
    private static final double LAYER_NOISE_CELL = 8.0;      // Create samples its noise at 0.125/block
    private static final double LAYER_NOISE_BLOCKS = 1.8;    // how far the boundaries wander
    private static final double LAYER_RADIAL_MIN = 0.5;      // Create: randomBetween(0.5, 1.0)
    private static final double LAYER_ROUGH_MIN = 0.75;      // Create: map(noise, -1, 1, 0.75, 1.0)

    // The rocks a layer may be, and how often. Stone stays the commonest single answer, but every layer
    // is a real roll from this -- not "plain rock, with the occasional variant" -- because that, plus
    // never repeating the previous layer, is what makes the banding read all the way up.
    //
    // Index 0 stands for whatever pillarBlock is set to and is resolved per tower; the rest are fixed.
    // Nothing here is mutable, deliberately: chunks generate on several threads at once, and an earlier
    // version filled a shared array on first use, which one thread could read half-written.
    private static final BlockState[] PALETTE_VARIANT = {
            Blocks.TUFF.defaultBlockState(),
            Blocks.ANDESITE.defaultBlockState(),
            Blocks.GRANITE.defaultBlockState(),
            Blocks.DIORITE.defaultBlockState(),
            Blocks.CALCITE.defaultBlockState() };
    private static final int[] PALETTE_WEIGHT = { 5, 3, 3, 2, 2, 1 };
    private static final int PALETTE_TOTAL = 16; // == sum(PALETTE_WEIGHT)

    private static BlockState palette(int index, BlockState base) {
        return index == 0 ? base : PALETTE_VARIANT[index - 1];
    }

    /** One tower's bedding: the axis its layers stack along, and what lies at each step of it. */
    private record Bedding(double axisX, double axisY, double axisZ, int rampMin,
            BlockState[] rock, double[] radial, long noiseSeed) {}

    // Roll a tower's bedding. Derived from the tower's own seed, so every chunk that touches it builds
    // the same layers and they meet across the join.
    private static Bedding rollBedding(Tower tower, BlockState base) {
        RandomSource rnd = RandomSource.create(tower.strataSeed() ^ 0xB3DD142L);

        // Create's own axis roll: the cube root crowds the vertical, so most beds lie near flat and the
        // occasional one dips hard. Clamped, for the reason in the note above.
        double gy = Math.cbrt(rnd.nextDouble() * 2.0 - 1.0);
        gy = gy < 0 ? Math.min(gy, -AXIS_MIN_VERTICALITY) : Math.max(gy, AXIS_MIN_VERTICALITY);
        double xzRescale = Math.sqrt(Math.max(0.0, 1.0 - gy * gy));
        double theta = rnd.nextDouble() * Math.PI * 2.0;
        double gx = Math.cos(theta) * xzRescale;
        double gz = Math.sin(theta) * xzRescale;

        // How far the ramp runs over the whole tower, allowing for the tilt carrying it sideways.
        double height = tower.height();
        double spread = (Math.abs(gx) + Math.abs(gz)) * tower.maxReach() + LAYER_NOISE_BLOCKS + 2.0;
        int rampMin = Mth.floor(Math.min(0.0, gy * height) - spread);
        int rampMax = Mth.ceil(Math.max(0.0, gy * height) + spread);
        int steps = Math.max(1, rampMax - rampMin + 1);

        BlockState[] rock = new BlockState[steps];
        double[] radial = new double[steps];
        int at = 0;
        int previous = -1;
        while (at < steps) {
            int pick = rollLayer(rnd, previous);
            previous = pick;
            int thickness = LAYER_THICKNESS_MIN
                    + rnd.nextInt(LAYER_THICKNESS_MAX - LAYER_THICKNESS_MIN + 1);
            double reach = LAYER_RADIAL_MIN + rnd.nextDouble() * (1.0 - LAYER_RADIAL_MIN);
            int end = Math.min(steps, at + thickness);
            while (at < end) {
                rock[at] = palette(pick, base);
                radial[at] = reach;
                at++;
            }
        }
        return new Bedding(gx, gy, gz, rampMin, rock, radial, tower.strataSeed() ^ 0x1A4E12L);
    }

    // Weighted pick, excluding whatever the layer below was -- Create's rollNext. Two touching layers
    // of the same rock would simply read as one thicker layer, wasting a boundary.
    private static int rollLayer(RandomSource rnd, int previous) {
        int total = PALETTE_TOTAL - (previous >= 0 ? PALETTE_WEIGHT[previous] : 0);
        int rolled = rnd.nextInt(Math.max(1, total));
        for (int i = 0; i < PALETTE_WEIGHT.length; i++) {
            if (i == previous) {
                continue;
            }
            rolled -= PALETTE_WEIGHT[i];
            if (rolled < 0) {
                return i;
            }
        }
        return previous == 0 ? 1 : 0;
    }

    // The rock at one block: which layer the tilted, noise-displaced ramp lands in, and whether that
    // layer reaches this far out from the middle.
    private static BlockState rockAt(Tower tower, Bedding bedding, int x, int y, int z,
            double distFrac, BlockState base) {
        double ramp = bedding.axisX() * (x + 0.5 - tower.baseX())
                + bedding.axisY() * (y - tower.bottomY())
                + bedding.axisZ() * (z + 0.5 - tower.baseZ())
                + (bedNoise3(bedding.noiseSeed(), x, y, z, LAYER_NOISE_CELL) * 2.0 - 1.0) * LAYER_NOISE_BLOCKS;

        int index = Mth.clamp(Mth.floor(ramp) - bedding.rampMin(), 0, bedding.rock().length - 1);
        double rough = LAYER_ROUGH_MIN + (1.0 - LAYER_ROUGH_MIN)
                * bedNoise3(bedding.noiseSeed() + 31L, x, y, z, LAYER_NOISE_CELL);
        if (distFrac > bedding.radial()[index] * rough) {
            return country(base, y); // this layer stops short of here
        }
        return country(bedding.rock()[index], y);
    }

    // Below zero the country rock is deepslate, as it is everywhere else in the world.
    private static BlockState country(BlockState rock, int y) {
        return y < 0 && rock.is(Blocks.STONE) ? Blocks.DEEPSLATE.defaultBlockState() : rock;
    }

    // --- where the dressing goes -------------------------------------------------------------------

    // Ice gathers in patches rather than evenly over a face, and the patches are held to about a
    // quarter of the rock. The threshold had to be measured, not reasoned about: trilinear value noise
    // crowds around its own middle, whose median here is 0.612 rather than 0.5, so the value that looks
    // like it should pass a quarter passes half. Measured over a sampled tower face: 0.605 -> 52%,
    // 0.65 -> 39%, 0.71 -> 25%, 0.75 -> 17%.
    private static final double FROST_PATCH_CELL = 15.0;
    private static final double FROST_PATCH_THRESHOLD = 0.71;

    // Vines run in lines, not speckle: this is the cell size of a Voronoi field whose CELL BORDERS the
    // growth follows, and how wide a border counts as. Borders form continuous, branching seams over
    // the rock, which is what a creeper actually does -- it finds a crack and follows it.
    // Measured on the same face: width 0.6 -> 26% of it on a seam, 0.75 -> ~31%, 1.15 -> 45%.
    private static final double VINE_CELL = 9.0;
    private static final double VINE_LINE_WIDTH = 0.75;

    /** True where ice is allowed to form at all. */
    private static boolean icy(Tower tower, int x, int y, int z) {
        return bedNoise3(tower.strataSeed() + 61L, x, y, z, FROST_PATCH_CELL) > FROST_PATCH_THRESHOLD;
    }

    /** True on a Voronoi cell border -- the seams vines follow. */
    private static boolean vineLine(Tower tower, int x, int y, int z) {
        return voronoiEdge(tower.strataSeed() + 83L, x, y, z, VINE_CELL) < VINE_LINE_WIDTH;
    }

    // Distance from the border between two Voronoi cells, in blocks: the gap between the nearest
    // feature point and the second nearest. Zero exactly on a border and rising away from it, so
    // thresholding it low draws the borders as connected lines rather than as scattered dots.
    private static double voronoiEdge(long seed, int x, int y, int z, double cell) {
        double fx = x / cell;
        double fy = y / cell;
        double fz = z / cell;
        int xi = Mth.floor(fx);
        int yi = Mth.floor(fy);
        int zi = Mth.floor(fz);
        double nearest = Double.MAX_VALUE;
        double second = Double.MAX_VALUE;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    long hash = mix(seed + (yi + dy) * 0x2545F4914F6CDD1DL, xi + dx, zi + dz, 0x0F0CA1L);
                    // Three offsets inside the cell, from separate slices of the one hash.
                    double px = xi + dx + ((hash >>> 42) & 0x3FF) / 1024.0;
                    double py = yi + dy + ((hash >>> 21) & 0x1FF) / 512.0;
                    double pz = zi + dz + (hash & 0x1FF) / 512.0;
                    double ddx = px - fx;
                    double ddy = py - fy;
                    double ddz = pz - fz;
                    double dist = Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
                    if (dist < nearest) {
                        second = nearest;
                        nearest = dist;
                    } else if (dist < second) {
                        second = dist;
                    }
                }
            }
        }
        return (second - nearest) * cell;
    }

    // Smooth value noise in three dimensions, 0 to 1 -- the boundaries have to wander in y as well, or
    // they are still flat sheets however much they are pushed around in x and z.
    private static double bedNoise3(long seed, int x, int y, int z, double cell) {
        double fx = x / cell;
        double fy = y / cell;
        double fz = z / cell;
        int x0 = Mth.floor(fx);
        int y0 = Mth.floor(fy);
        int z0 = Mth.floor(fz);
        double tx = smooth(fx - x0);
        double ty = smooth(fy - y0);
        double tz = smooth(fz - z0);
        double c00 = Mth.lerp(tx, lattice3(seed, x0, y0, z0), lattice3(seed, x0 + 1, y0, z0));
        double c10 = Mth.lerp(tx, lattice3(seed, x0, y0 + 1, z0), lattice3(seed, x0 + 1, y0 + 1, z0));
        double c01 = Mth.lerp(tx, lattice3(seed, x0, y0, z0 + 1), lattice3(seed, x0 + 1, y0, z0 + 1));
        double c11 = Mth.lerp(tx, lattice3(seed, x0, y0 + 1, z0 + 1), lattice3(seed, x0 + 1, y0 + 1, z0 + 1));
        return Mth.lerp(tz, Mth.lerp(ty, c00, c10), Mth.lerp(ty, c01, c11));
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lattice3(long seed, int x, int y, int z) {
        return ((mix(seed + y * 0x2545F4914F6CDD1DL, x, z, 0x1A771CEL) >>> 11) * 0x1.0p-53);
    }

    // How much wider the tower is here than at its crown, as a fraction of its radius. 0 at the top,
    // strataTotal at the foot, never decreasing on the way down.
    private static double strataWiden(Tower tower, int y) {
        double fromTop = tower.topY() - y;
        double height = tower.height();
        double fine = bandRamp(fromTop, tower.strataFine(), tower.strataEase(), height);
        double coarse = bandRamp(fromTop, tower.strataCoarse(), tower.strataEase(), height);
        double mixed = fine * tower.strataBlend() + coarse * (1.0 - tower.strataBlend());
        return tower.strataTotal() * Math.pow(mixed, STRATA_CROWN_BIAS);
    }

    // One scale of banding, 0 at the crown and 1 at the foot. Flat through the body of each band and
    // eased across to the next over the first {@code ease} of it -- so the joint is a slope a couple of
    // blocks deep, which is what turns a lip into a shoulder.
    private static double bandRamp(double fromTop, double bandHeight, double ease, double height) {
        double u = fromTop / bandHeight;
        double band = Math.floor(u);
        double frac = Math.min(1.0, (u - band) / ease);
        double eased = band + frac * frac * (3.0 - 2.0 * frac);
        return eased / Math.max(1.0, height / bandHeight);
    }

    // Order-dependent 64-bit mix of the seed and a pair of coordinates (finalizer from splitmix64), so
    // neighbouring cells land nowhere near each other in the output.
    private static long mix(long seed, int a, int b, long salt) {
        long hash = seed ^ salt;
        hash = hash * 6364136223846793005L + (a * 0x9E3779B97F4A7C15L);
        hash = hash * 6364136223846793005L + (b * 0xC2B2AE3D27D4EB4FL);
        hash ^= hash >>> 33;
        hash *= 0xFF51AFD7ED558CCDL;
        hash ^= hash >>> 33;
        hash *= 0xC4CEB9FE1A85EC53L;
        hash ^= hash >>> 33;
        return hash;
    }

    // The configured block, falling back to stone for an id that names nothing.
    private static BlockState pillarBlock() {
        ResourceLocation id = ResourceLocation.tryParse(Config.PILLAR_BLOCK.get().trim());
        if (id == null) {
            return Blocks.STONE.defaultBlockState();
        }
        return BuiltInRegistries.BLOCK.getOptional(id)
                .map(net.minecraft.world.level.block.Block::defaultBlockState)
                .orElseGet(Blocks.STONE::defaultBlockState);
    }
}
