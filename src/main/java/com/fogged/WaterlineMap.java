package com.fogged;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.fogged.registry.ModParticles;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A world-aligned "distance to the nearest waterline" map, centred on the camera. The grid is stored
// at cellsPerBlock cells per block (sub-block resolution) so the foam contour is crisp on the
// pixel grid. Each cell's red channel stores the distance (normalised by MAX_DIST blocks) to the
// nearest solid block / boundary-crossing entity. The plane shader samples this to draw foam as a
// stable ring on the water -- world-space, so it never cuts off with view angle.
//
// Two grids are kept: target[] is the freshly-computed distance field; shown[] eases toward it each
// tick so foam accumulates and fades gradually instead of popping in. shown[] is world-anchored, so
// when the map re-centres as the camera walks, its contents are shifted to keep foam in place.
//
// The map's block edge length tracks the game's *simulation distance*.
public final class WaterlineMap {

    public static final float MAX_DIST = 8.0F;       // distances are clamped/stored up to this many blocks
    private static final int RECOMPUTE_INTERVAL = 5;  // ticks between full target rebuilds (also on move)
    private static final float EASE_CELLS_PER_TICK = 0.25F; // how fast shown[] chases target[] (foam ramp)
    private static final int MIN_SIZE = 48;           // clamp the simulation-distance-driven block edge
    private static final int MAX_SIZE = 192;
    private static final float INF = 1.0e9F;
    // How far above and below the surface something can sit and still raise foam, in blocks. At the
    // surface the ring is full strength; at the edge of the band there is none left. Read once per
    // rescan rather than per column -- it is a config lookup.
    static double foamBand() {
        return Config.FOAM_REACH.get();
    }

    // Cost of a diagonal step, against 1.0 for an orthogonal one. Used by BOTH the distance transform
    // and the foam's growth wavefront, and they have to agree: the transform's contours are what the
    // foam settles into, so a front that spreads in a different metric visibly changes shape as it
    // catches up. The same goes for KNIGHT_STEP below -- add a move to one and it belongs in the other.
    private static final float DIAGONAL_STEP = 1.41421356F;   // sqrt(2)
    // ...and the knight move (1,2), sqrt(5). A mask of only orthogonal and diagonal steps is a 3x3
    // chamfer, whose distances are off by up to ~7.6% and are worst halfway between the two -- which
    // is a real shape, not noise: the iso-distance contours come out as octagons, and once the foam
    // quantises them into bands the ring reads as a star -- in the mist as much as in the foam, since
    // the vapour thins on the same field. Adding the knight moves makes it a 5x5 chamfer and drops the
    // error to ~2%, which those bands no longer resolve.
    private static final float KNIGHT_STEP = 2.23606798F;

    // Whether the Sable physics mod is present, so its sub-levels can also generate foam.
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    // How far out spray from crossing entities is thrown, bounded by the map that feeds the query.
    private static final int PARTICLE_RADIUS_MAX = 48;

    // How far the spray's tint is blended toward white, 0 = the foam colour as painted, 1 = white.
    // The sprites carry their own soft shading, so multiplying them by the full foam colour lands much
    // darker than the flat ring drawn on the surface -- and spray is water thrown into the air, which
    // reads lighter than the surface it came off, not the same. One number to tune.
    private static final float SPRAY_WHITENING = 0.3F;

    // A crossing entity throws spray in proportion to how fast it is going, piled up ahead of it.
    private static final double FULL_SPEED = 0.4;          // blocks/tick counting as "fast"
    private static final double WAKE_MAX = 2.25;           // particles per entity per tick at full speed
    private static final float STATIC_CHANCE = 0.045F;     // ...and how often a still one manages one
    // The same for a moored sub-level, per probe. Kept where the entity rate used to be: a hull the
    // size of a ship is what this fires against, and halving the entity rate was about the crowd of
    // spray a moving mob throws, not about ships.
    private static final float SUBLEVEL_STATIC_CHANCE = 0.09F;
    private static final double FRONT_ARC = 0.9;           // radians of spread the bow wave piles into
    private static final double WAKE_DRAG = 0.6;           // how much of the entity's motion the spray keeps
    private static final double WAKE_SPREAD = 0.06;        // blocks/tick the wave travels outward at
    // Waterline samples per Sable sub-level per tick. A probe only spawns spray when it lands on a
    // solid block of the hull, and it is thrown at the sub-level's whole bounding box -- most of which
    // is the water around the hull, not the hull -- so the hit rate is well under half and a ship threw
    // visibly less spray than a boat with a player in it. Roughly twice the probes to close that gap,
    // and half again on top for the rate below -- then halved with the entity rate.
    private static final int SUBLEVEL_PROBES = 36;

    private static DynamicTexture texture;
    // Ready-to-draw RGB of the foam ring, built only while a shader pack draws the plane (see
    // FogPlaneRenderer): that path goes through the pack's own program, which can only multiply a
    // texture by a vertex colour, so the foam ramp fog_plane.fsh normally evaluates per fragment is
    // baked here instead.
    private static DynamicTexture foamColorTexture;
    // Colours/width the baked foam texture was last built with, so a live config edit rebuilds it even
    // when the distance field itself is unchanged.
    private static int lastFoamSignature = 0;
    private static int size = 0;          // current block edge length of the map
    private static int cellsPerBlock = 4; // sub-block grid resolution; see Config.WATERLINE_CELLS_PER_BLOCK
    private static int cells = 0;         // texel/cell edge length = size * cellsPerBlock
    // Two independent foam fields so full-strength (solid/entity) foam and half-strength plant foam
    // form separately and never override one another -- they are combined by max in the shader.
    private static float[] target = new float[0];   // distance to nearest solid block / entity
    private static float[] shown = new float[0];     // eased version of target (R channel)
    private static float[] targetP = new float[0];  // distance to nearest plant
    private static float[] shownP = new float[0];    // eased version of targetP (G channel)
    // Distance to the nearest thing that sits NEAR the surface without cutting it -- the foam band's
    // seeds (see bandSeedCells) -- kept apart from the crossings in target/shown on purpose. The foam
    // rings whichever of the two is closer; the dissolve reads only the crossings. When the band went
    // into the same field as the crossings, a campfire half a block under the surface opened the
    // plane above it like something standing through it, and the world showed through the hole.
    private static float[] targetN = new float[0];
    private static float[] shownN = new float[0];    // eased version of targetN (B channel)
    private static float[] scratch = new float[0];
    // Distance to the nearest solid BLOCK only, chamfered, kept between rescans. Entities and Sable
    // sub-levels are stamped onto a copy of it every tick (see restampDynamic), so what moves is
    // re-seeded at tick rate while the expensive block scan keeps its own slower cadence.
    private static float[] blockDist = new float[0];
    private static float[] blockDistN = new float[0]; // the same, for the band field

    // Cell boxes stamped by whatever was crossing last time, so only those get relaxed back in. On
    // overflow the whole field is chamfered instead, which is what the map did every rescan anyway.
    private static final int MAX_STAMP_BOXES = 32;
    private static final int[] stampBoxes = new int[MAX_STAMP_BOXES * 4];
    private static int stampBoxCount;
    private static boolean stampOverflow;
    private static boolean lastStamped;
    // World block X/Z of every column whose boundary-row block is liquid that CROSSES the surface, as
    // of the last rescan: a fall, a current, or standing water with a side open to the air (a wall of
    // it, a fountain), as opposed to a pool the surface merely passes through. FogPlaneRenderer draws
    // these as depth-only boxes into the soft edge's depth snapshot, since water itself is drawn after
    // the plane and so never reaches it (see FogPlaneRenderer#waterDepthProxies).
    private static int[] flowingColumns = new int[64];
    private static int flowingColumnCount;
    private static int originX;           // world block X of the map's corner
    private static int originZ;           // world block Z of the map's corner
    private static int lastBoundaryY = Integer.MIN_VALUE;
    private static long lastRecomputeTick = Long.MIN_VALUE;
    private static long lastTick = Long.MIN_VALUE;

    // Where the work is, as a cell range per row (x0 > x1 for a row with none). A seed's reach is
    // MAX_DIST, so beyond that band around the seeds the fields are flat "far water" and stay so; the
    // distance transform, the ease and the texture refill are confined to the band instead of sweeping
    // every one of the map's cells -- at 768x768 that sweep was most of a tick, and the transform most
    // of a frame. Per row rather than one box, because seeds sit wherever the shore is and one box
    // around all of them is most of the map again.
    private static int[] seedMin = new int[0];   // solid/entity seeds of the last rescan, per row
    private static int[] seedMax = new int[0];
    private static int[] seedMinP = new int[0];  // plant seeds
    private static int[] seedMaxP = new int[0];
    private static int[] seedMinN = new int[0];  // band seeds
    private static int[] seedMaxN = new int[0];
    private static int[] shownMin = new int[0];  // where any foam still shows after the last ease
    private static int[] shownMax = new int[0];
    private static int[] workMin = new int[0];   // this tick's ease and refill range
    private static int[] workMax = new int[0];
    private static int[] bandMin = new int[0];   // scratch: a seed range grown by the reach
    private static int[] bandMax = new int[0];
    private static boolean workFull = true;      // refill the whole texture this tick

    private static void rowsClear(int[] min, int[] max) {
        java.util.Arrays.fill(min, Integer.MAX_VALUE);
        java.util.Arrays.fill(max, Integer.MIN_VALUE);
    }

    private static void rowsAdd(int[] min, int[] max, int x0, int z0, int x1, int z1) {
        for (int z = Math.max(0, z0); z <= Math.min(cells - 1, z1); z++) {
            min[z] = Math.min(min[z], x0);
            max[z] = Math.max(max[z], x1);
        }
    }

    // Widen dst by src grown r cells on every side: each row takes the widest range of the src rows
    // within r of it. A sliding window, so the cost is rows times reach, not cells.
    private static void rowsAddGrown(int[] dstMin, int[] dstMax, int[] srcMin, int[] srcMax, int r) {
        for (int z = 0; z < cells; z++) {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int zz = Math.max(0, z - r); zz <= Math.min(cells - 1, z + r); zz++) {
                lo = Math.min(lo, srcMin[zz]);
                hi = Math.max(hi, srcMax[zz]);
            }
            if (lo <= hi) {
                dstMin[z] = Math.min(dstMin[z], Math.max(0, lo - r));
                dstMax[z] = Math.max(dstMax[z], Math.min(cells - 1, hi + r));
            }
        }
    }

    // Move the ranges with the field contents they describe (see shiftField: new[x] = old[x + dx]).
    private static void rowsShift(int[] min, int[] max, int dx, int dz) {
        System.arraycopy(min, 0, bandMin, 0, cells);
        System.arraycopy(max, 0, bandMax, 0, cells);
        rowsClear(min, max);
        for (int z = 0; z < cells; z++) {
            int sz = z + dz;
            if (sz < 0 || sz >= cells || bandMin[sz] > bandMax[sz]) {
                continue;
            }
            int lo = Math.max(0, bandMin[sz] - dx);
            int hi = Math.min(cells - 1, bandMax[sz] - dx);
            if (lo <= hi) {
                min[z] = lo;
                max[z] = hi;
            }
        }
    }

    private static int reachCells() {
        return Mth.ceil(MAX_DIST) * cellsPerBlock;
    }

    // Wall times of the last rescan's parts and the last tick's parts, for the debug HUD.
    static double lastReseedMs;
    static double lastChamferMs;
    static double lastStampMs;
    static double lastEaseMs;
    static double lastUploadMs;

    private WaterlineMap() {
    }

    public static int textureId() {
        return texture == null ? 0 : texture.getId();
    }

    /** Baked foam-ring colour map; 0 until a frame has asked for it (see {@link #update}). */
    public static int foamColorTextureId() {
        return foamColorTexture == null ? 0 : foamColorTexture.getId();
    }

    /** Radii in blocks over which the map's answer fades to nothing, for the debug HUD: {from, to}. */
    public static float[] fadeRadii() {
        float halfSize = size * 0.5F;
        float to = Math.max(1.0F, halfSize - FOAM_FADE_MARGIN);
        return new float[] { Math.min(FOAM_FADE_START * halfSize, to - 1.0F), to };
    }

    public static int size() {
        return size;
    }

    /** Columns of flowing liquid crossing the boundary as of the last rescan; see {@link #flowingColumnX}. */
    public static int flowingColumnCount() {
        return flowingColumnCount;
    }

    public static int flowingColumnX(int i) {
        return flowingColumns[i * 2];
    }

    public static int flowingColumnZ(int i) {
        return flowingColumns[i * 2 + 1];
    }

    // Current sub-block grid resolution (cells per block); read by the plane/vapour shaders' pixel-snap
    // uniforms so their pixelation always matches the map's actual resolution (see Config.WATERLINE_CELLS_PER_BLOCK).
    public static int cellsPerBlock() {
        return cellsPerBlock;
    }

    public static float originX() {
        return originX;
    }

    public static float originZ() {
        return originZ;
    }

    // Driven from the renderer every frame. Recomputes the target field on move / every few ticks,
    // eases the shown field toward it once per tick, and sprinkles foam particles. wantFoamColor asks
    // for the baked colour map alongside (see foamColorTexture).
    public static void update(Level level, Vec3 camPos, int boundaryY, int mapBlocks, boolean wantFoamColor) {
        // The surface's own fractional height: the block row alone is up to a block away from it, and
        // the foam band is measured in half blocks (see bandSeedCells).
        double surfaceY = Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
        int want = Mth.clamp(mapBlocks, MIN_SIZE, MAX_SIZE);
        int wantC = Mth.clamp(Config.WATERLINE_CELLS_PER_BLOCK.getAsInt(), 1, 4);
        if (texture == null || want != size || wantC != cellsPerBlock) {
            rebuild(want, wantC);
        }
        boolean foamColorDirty = syncFoamColorTexture(wantFoamColor);

        int cx = Mth.floor(camPos.x) - size / 2;
        int cz = Mth.floor(camPos.z) - size / 2;
        long tick = level.getGameTime();
        boolean originMoved = cx != originX || cz != originZ;

        // Keep the fields world-anchored: shift their contents by the offset the map just moved. The
        // distance fields (target/targetP) are shifted too, so walking does NOT trigger a full rescan
        // every time the camera crosses a block boundary -- only the periodic recompute below rebuilds
        // them. The newly exposed edge strip reads as "far water" until that next recompute (<= a few
        // ticks), which the foam ease already hides at the map's outer rim.
        if (originMoved) {
            int dx = (cx - originX) * cellsPerBlock;
            int dz = (cz - originZ) * cellsPerBlock;
            shiftField(shown, dx, dz);
            shiftField(shownP, dx, dz);
            shiftField(shownN, dx, dz);
            shiftField(target, dx, dz);
            shiftField(targetP, dx, dz);
            shiftField(targetN, dx, dz);
            shiftField(blockDist, dx, dz);
            shiftField(blockDistN, dx, dz);
            rowsShift(seedMin, seedMax, dx, dz);
            rowsShift(seedMinP, seedMaxP, dx, dz);
            rowsShift(seedMinN, seedMaxN, dx, dz);
            rowsShift(shownMin, shownMax, dx, dz);
            workFull = true; // the strip that came in reads "far water" and has to be drawn so
            originX = cx;
            originZ = cz;
        }

        boolean newTick = tick != lastTick;
        boolean recomputed = false;

        // Reseed + chamfer synchronously on the same call, on the boundary changing or every
        // RECOMPUTE_INTERVAL ticks: the two must happen back-to-back on fully fresh block-state data.
        // (A tick-staggered reseed was tried here and reverted -- easing every tick against a target[]
        // whose stripes hold raw, not-yet-chamfered values for several ticks between chamfer passes
        // made foam near real edges visibly recede then snap back as each stripe cycled through.)
        if (boundaryY != lastBoundaryY || tick - lastRecomputeTick >= RECOMPUTE_INTERVAL) {
            lastBoundaryY = boundaryY;
            lastRecomputeTick = tick;
            long t0 = System.nanoTime();
            reseedAll(level, boundaryY, surfaceY, foamBand());
            long t1 = System.nanoTime();
            finishRecompute();
            lastReseedMs = (t1 - t0) / 1.0e6;
            lastChamferMs = (System.nanoTime() - t1) / 1.0e6;
            recomputed = true;
        }

        // Ease + re-upload at most once per tick (bounded cost), or immediately after a re-centre.
        if (newTick || originMoved) {
            // Whatever moves is re-stamped every tick, so a boat's ring keeps up with it rather than
            // waiting on the block rescan's cadence above.
            long t0 = System.nanoTime();
            boolean restamped = restampDynamic(level, boundaryY, surfaceY, foamBand());
            long t1 = System.nanoTime();
            long elapsed = lastTick == Long.MIN_VALUE ? 1 : Math.max(1, tick - lastTick);
            float step = EASE_CELLS_PER_TICK * elapsed;
            // This tick's region: everything a seed can reach, plus wherever foam still shows (it may
            // be retreating from a seed that is gone). Rebuilt from the boxes, then narrowed by the
            // ease to where foam actually is for the next tick.
            rowsClear(workMin, workMax);
            rowsAddGrown(workMin, workMax, shownMin, shownMax, 0);
            rowsAddGrown(workMin, workMax, seedMin, seedMax, reachCells());
            rowsAddGrown(workMin, workMax, seedMinP, seedMaxP, reachCells());
            rowsAddGrown(workMin, workMax, seedMinN, seedMaxN, reachCells());
            if (stampOverflow) {
                rowsAdd(workMin, workMax, 0, 0, cells - 1, cells - 1);
            } else {
                int r = reachCells();
                for (int i = 0; i < stampBoxCount; i++) {
                    rowsAdd(workMin, workMax, Math.max(0, stampBoxes[i * 4] - r), stampBoxes[i * 4 + 1] - r,
                            Math.min(cells - 1, stampBoxes[i * 4 + 2] + r), stampBoxes[i * 4 + 3] + r);
                }
            }
            rowsClear(shownMin, shownMax);
            boolean changed = ease(target, shown, step);
            changed |= ease(targetP, shownP, step);
            changed |= ease(targetN, shownN, step);
            lastStampMs = (t1 - t0) / 1.0e6;
            lastEaseMs = (System.nanoTime() - t1) / 1.0e6;
            lastTick = tick;
            if (changed || originMoved || recomputed || restamped || foamColorDirty) {
                long t2 = System.nanoTime();
                upload(!(workFull || foamColorDirty));
                workFull = false;
                lastUploadMs = (System.nanoTime() - t2) / 1.0e6;
                foamColorDirty = false;
            }
            if (newTick) {
                emitFoamParticles(level, camPos, boundaryY);
            }
        }
        if (foamColorDirty) {
            upload(false); // config edit, or the map just asked for, on a frame that wasn't going to re-upload
        }
    }

    // Create / drop / invalidate the baked foam colour map. Returns true when it needs (re)filling this
    // frame: it was just created, or the colours and width it was baked with have since changed.
    private static boolean syncFoamColorTexture(boolean wanted) {
        if (!wanted) {
            if (foamColorTexture != null) {
                foamColorTexture.close();
                foamColorTexture = null;
            }
            return false;
        }
        int signature = foamSignature();
        if (foamColorTexture == null) {
            foamColorTexture = newFoamColorTexture();
            lastFoamSignature = signature;
            return true;
        }
        if (signature != lastFoamSignature) {
            lastFoamSignature = signature;
            return true;
        }
        return false;
    }

    private static int foamSignature() {
        float[] plane = Config.planeColor();
        float[] foam = Config.foamColor();
        int h = Float.floatToIntBits((float) (double) Config.FOAM_WIDTH.get());
        for (int i = 0; i < 3; i++) {
            h = h * 31 + Float.floatToIntBits(plane[i]);
        }
        for (int i = 0; i < 4; i++) {
            h = h * 31 + Float.floatToIntBits(foam[i]);
        }
        return h;
    }

    private static DynamicTexture newFoamColorTexture() {
        DynamicTexture tex = new DynamicTexture(new NativeImage(NativeImage.Format.RGBA, cells, cells, false));
        tex.setFilter(true, false); // LINEAR between cells, matching how the shader samples the map
        GlStateManager._bindTexture(tex.getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return tex;
    }

    // Reseed the whole block-state field. Solids and flowing water (currents, falls) seed the full
    // field; plants the plant field. Still water (source) and air are left as open surface so foam has
    // somewhere to fade into.
    /**
     * Seed distance, in cells, for something whose nearest face sits {@code gap} blocks from the
     * surface, or a negative value when it is too far to count.
     *
     * <p>A seed stamped at a distance rather than at zero is how "less of an effect" is said in a
     * field that only stores distance: the ring it raises starts that far along its own ramp, so it
     * comes out narrower and fainter, and at the band's edge it is gone. Scaled by the foam's width so
     * the fade spans the whole ring whatever that width is set to -- with a fixed slice instead, a wide
     * ring would barely notice half a block and a narrow one would lose everything in the first inch.
     *
     * <p>The dissolve reads the same field (fogged_nearCrossing), so something under the surface now
     * softens it a little as well as ringing it -- weakly, and only within the band.
     */
    private static float bandSeedCells(double gap, double band, float foamWidth) {
        float near = bandFalloff(gap, band);
        if (near <= 0.0F || foamWidth <= 0.0F) {
            return -1.0F;
        }
        return (1.0F - near) * foamWidth * cellsPerBlock;
    }

    /**
     * How much of its full effect something {@code gap} blocks clear of the surface still has: 1 at the
     * surface, 0 at the edge of the band. The weight form of {@link #bandSeedCells}, which is written
     * in terms of this so the painted ring and the thrown spray cannot fall out of step.
     */
    static float bandFalloff(double gap, double band) {
        return band <= 0.0 || gap >= band ? 0.0F : (float) (1.0 - gap / band);
    }

    /** Blocks between the surface and the nearest face of {@code box}; zero while it straddles. */
    static double surfaceGap(AABB box, double surfaceY) {
        return Math.max(0.0, Math.max(surfaceY - box.maxY, box.minY - surfaceY));
    }

    // Vertical gap in blocks between the surface and the block row y, which spans [y, y + 1]. Zero when
    // the surface runs through the row.
    static double rowGap(double surfaceY, int y) {
        if (surfaceY >= y && surfaceY <= y + 1) {
            return 0.0;
        }
        return surfaceY > y + 1 ? surfaceY - (y + 1) : y - surfaceY;
    }

    private static void reseedAll(Level level, int boundaryY, double surfaceY, double band) {
        java.util.Arrays.fill(target, INF);
        java.util.Arrays.fill(targetP, INF);
        java.util.Arrays.fill(targetN, INF);
        rowsClear(seedMin, seedMax);
        rowsClear(seedMinP, seedMaxP);
        rowsClear(seedMinN, seedMaxN);
        flowingColumnCount = 0;
        float foamWidth = (float) (double) Config.FOAM_WIDTH.get();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bz = 0; bz < size; bz++) {
            for (int bx = 0; bx < size; bx++) {
                reseedColumn(level, pos, boundaryY, surfaceY, band, foamWidth, bx, bz);
            }
        }
    }

    // Reseed one block column's cells from its current block state. Relies on reseedAll having already
    // cleared the whole field to INF, so this only needs to stamp the cells that turn out to be solid.
    private static void reseedColumn(Level level, BlockPos.MutableBlockPos pos, int boundaryY,
            double surfaceY, double band, float foamWidth, int bx, int bz) {
        final int C = cellsPerBlock;
        int x0 = bx * C;
        int z0 = bz * C;
        int x1 = x0 + C - 1;
        int z1 = z0 + C - 1;

        pos.set(originX + bx, boundaryY, originZ + bz);
        BlockState state = level.getBlockState(pos);
        var fluid = state.getFluidState();
        boolean solid = state.blocksMotion();
        boolean plant = !solid && fluid.isEmpty() && !state.isAir();
        if (columnClosed(state) || standingWaterCrossing(level, pos, state)) {
            fillCells(target, x0, z0, x1, z1, 0.0F);
            rowsAdd(seedMin, seedMax, x0, z0, x1, z1);
            if (!solid) {
                if (flowingColumnCount * 2 + 2 > flowingColumns.length) {
                    flowingColumns = java.util.Arrays.copyOf(flowingColumns, flowingColumns.length * 2);
                }
                flowingColumns[flowingColumnCount * 2] = originX + bx;
                flowingColumns[flowingColumnCount * 2 + 1] = originZ + bz;
                flowingColumnCount++;
            }
        } else if (plant) {
            fillCells(targetP, x0, z0, x1, z1, 0.0F);
            rowsAdd(seedMinP, seedMaxP, x0, z0, x1, z1);
        }

        // Whatever sits just clear of the surface rings it too, the further out the fainter. Every row
        // the band reaches is looked at, not just the nearest: the surface sits at a fractional height,
        // so the two rows touching its own are at different distances from it and a reach wide enough
        // for both has to see both.
        for (int y = bandLow(surfaceY, band); y <= bandHigh(surfaceY, band); y++) {
            if (y == boundaryY) {
                continue; // the row the surface runs through, already seeded above at full strength
            }
            float seed = bandSeedCells(rowGap(surfaceY, y), band, foamWidth);
            if (seed < 0.0F) {
                continue;
            }
            pos.set(originX + bx, y, originZ + bz);
            BlockState near = level.getBlockState(pos);
            boolean nearPlant = !near.blocksMotion() && near.getFluidState().isEmpty() && !near.isAir();
            if (columnClosed(near) || standingWaterCrossing(level, pos, near)) {
                fillCellsMin(targetN, x0, z0, x1, z1, seed); // the band field, never the crossings'
                rowsAdd(seedMinN, seedMaxN, x0, z0, x1, z1);
            } else if (nearPlant) {
                fillCellsMin(targetP, x0, z0, x1, z1, seed);
                rowsAdd(seedMinP, seedMaxP, x0, z0, x1, z1);
            }
        }
    }

    /** Lowest / highest block row any part of which lies within {@code band} of the surface. */
    static int bandLow(double surfaceY, double band) {
        return Mth.floor(surfaceY - band);
    }

    static int bandHigh(double surfaceY, double band) {
        return Mth.floor(surfaceY + band);
    }

    // Standing (source) water with a side open to the air: a wall or column of it standing in the
    // open crosses the surface like a block does, where a pool's water only meets more water or the
    // shore that already rings it. Checked against the four horizontal neighbours at the boundary row.
    private static boolean standingWaterCrossing(Level level, BlockPos.MutableBlockPos pos, BlockState state) {
        var fluid = state.getFluidState();
        if (fluid.isEmpty() || !fluid.isSource()) {
            return false;
        }
        int x = pos.getX();
        int y = pos.getY();
        int z = pos.getZ();
        boolean open = sideOpen(level, pos.set(x + 1, y, z)) || sideOpen(level, pos.set(x - 1, y, z))
                || sideOpen(level, pos.set(x, y, z + 1)) || sideOpen(level, pos.set(x, y, z - 1));
        pos.set(x, y, z);
        return open;
    }

    private static boolean sideOpen(Level level, BlockPos pos) {
        BlockState side = level.getBlockState(pos);
        return !side.blocksMotion() && side.getFluidState().isEmpty();
    }

    /**
     * Whether the surface is blocked at this column: a solid block, or a fluid that is still flowing
     * (a source pool is the surface's own water and rings nothing).
     *
     * <p>The single definition of "the waterline touches something here", shared by the map's own
     * seeding above and the foam-site scan ({@link FoamSites}) -- they must agree, or spray appears
     * where the painted ring does not.
     */
    static boolean columnClosed(BlockState state) {
        var fluid = state.getFluidState();
        return state.blocksMotion() || (!fluid.isEmpty() && !fluid.isSource());
    }

    // The block half of the field: chamfer what reseedAll seeded and keep a copy. Always runs
    // immediately after reseedAll, in the same call (see update()), on fully fresh block-state data.
    // Everything that moves is stamped on top of that copy every tick instead (see restampDynamic).
    private static void finishRecompute() {
        chamferAround(target, seedMin, seedMax);
        System.arraycopy(target, 0, blockDist, 0, target.length);
        chamferAround(targetP, seedMinP, seedMaxP);
        chamferAround(targetN, seedMinN, seedMaxN);
        System.arraycopy(targetN, 0, blockDistN, 0, targetN.length);
    }

    // The transform over the band a field's seeds can reach; beyond it every cell is still INF. Exact
    // for every distance under the reach: a cell within it of some seed lies in the seed's grown row
    // range, and so does every cell on the transform's path to it, all nearer the seed still.
    private static void chamferAround(float[] field, int[] seedMinX, int[] seedMaxX) {
        rowsClear(bandMin, bandMax);
        rowsAddGrown(bandMin, bandMax, seedMinX, seedMaxX, reachCells());
        chamferRows(field, bandMin, bandMax);
    }

    /**
     * Stamp everything that MOVES -- crossing entities and Sable sub-levels -- onto a fresh copy of
     * the block field. Runs every tick, and returns whether the field actually changed.
     *
     * <p>This used to happen inside the rescan, so a boat's ring only caught up every
     * RECOMPUTE_INTERVAL ticks and visibly stepped along behind anything moving at speed. Splitting it
     * out costs an arraycopy a tick, plus a chamfer around each stamp on the ticks where something is
     * actually near the boundary -- with nothing crossing, there is nothing to do at all.
     *
     * <p>Restoring the block-only field is what un-stamps what moved off: it is exact, so nothing has
     * to be un-chamfered, and only the new seeds need relaxing back in.
     */
    private static boolean restampDynamic(Level level, int boundaryY, double surfaceY, double band) {
        final int C = cellsPerBlock;
        AABB area = new AABB(originX, boundaryY - 2.0, originZ, originX + size, boundaryY + 2.0, originZ + size);
        // Measured off the surface itself rather than off its block row, so the band is the same half
        // block above and below that the block seeds use -- the row is up to a block away from where
        // the surface actually sits.
        var crossing = level.getEntities((Entity) null, area,
                e -> surfaceGap(e.getBoundingBox(), surfaceY) < band);
        boolean sable = SABLE && Config.SABLE_FOAM.getAsBoolean();
        if (crossing.isEmpty() && !sable && !lastStamped) {
            return false; // nothing crossed last tick and nothing crosses now: the field is untouched
        }

        System.arraycopy(blockDist, 0, target, 0, target.length);
        System.arraycopy(blockDistN, 0, targetN, 0, targetN.length);
        stampBoxCount = 0;
        stampOverflow = false;

        float foamWidth = (float) (double) Config.FOAM_WIDTH.get();
        for (Entity e : crossing) {
            AABB b = e.getBoundingBox();
            // Zero while the box straddles the surface, growing as it clears it either way; the same
            // falloff the block seeds get, so a boat riding the surface still rings it hard and one
            // sinking away from it lets go gradually instead of at a block boundary.
            double gap = surfaceGap(b, surfaceY);
            float seed = gap <= 0.0 ? 0.0F : bandSeedCells(gap, band, foamWidth);
            if (seed < 0.0F) {
                continue;
            }
            int x0 = Mth.floor((b.minX - originX) * C);
            int x1 = Mth.floor((b.maxX - originX) * C);
            int z0 = Mth.floor((b.minZ - originZ) * C);
            int z1 = Mth.floor((b.maxZ - originZ) * C);
            // Straddling the surface it is a crossing, and rings and dissolves like one; merely near it,
            // it goes to the band field, which rings but never dissolves.
            fillCellsMin(gap <= 0.0 ? target : targetN, x0, z0, x1, z1, seed);
            addStampBox(x0, z0, x1, z1);
        }

        // A sub-level's stamped cells are scattered over its hull's footprint, so one box covers the lot.
        if (sable) {
            int[] u = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
            float ringWidth = foamWidth * cellsPerBlock;
            SableCompatibility.stampSubLevels(level, surfaceY, band, originX, originZ, size, C,
                    (cellX, cellZ, weight) -> {
                        // The same seed-at-a-distance the blocks and entities get: a hull meeting the
                        // surface stamps zero into the crossings, one that only comes near stamps
                        // further along the ramp, into the band field -- which rings but never dissolves.
                        float seed = (1.0F - weight) * ringWidth;
                        float[] field = weight >= 1.0F ? target : targetN;
                        int i = cellZ * cells + cellX;
                        if (seed < field[i]) {
                            field[i] = seed;
                        }
                        u[0] = Math.min(u[0], cellX);
                        u[1] = Math.min(u[1], cellZ);
                        u[2] = Math.max(u[2], cellX);
                        u[3] = Math.max(u[3], cellZ);
                    });
            if (u[0] <= u[2]) {
                addStampBox(u[0], u[1], u[2], u[3]);
            }
        }

        boolean stamped = stampBoxCount > 0 || stampOverflow;
        // A seed only reaches MAX_DIST blocks, so relaxing that far around each stamp is the whole of
        // its effect -- where re-running the transform over the map would cost cells^2 a tick.
        if (stampOverflow) {
            chamferDistance(target);
            chamferDistance(targetN);
        } else {
            int reach = Mth.ceil(MAX_DIST) * C;
            for (int i = 0; i < stampBoxCount; i++) {
                int b = i * 4;
                chamferRegion(target, stampBoxes[b] - reach, stampBoxes[b + 1] - reach,
                        stampBoxes[b + 2] + reach, stampBoxes[b + 3] + reach);
                chamferRegion(targetN, stampBoxes[b] - reach, stampBoxes[b + 1] - reach,
                        stampBoxes[b + 2] + reach, stampBoxes[b + 3] + reach);
            }
        }
        boolean changed = stamped || lastStamped; // the tick the last one leaves still has to be redrawn
        lastStamped = stamped;
        return changed;
    }

    // Remember one stamped box, or give up on tracking them and chamfer the whole field instead.
    private static void addStampBox(int x0, int z0, int x1, int z1) {
        if (stampOverflow) {
            return;
        }
        if (stampBoxCount == MAX_STAMP_BOXES) {
            stampOverflow = true;
            return;
        }
        int b = stampBoxCount++ * 4;
        stampBoxes[b] = x0;
        stampBoxes[b + 1] = z0;
        stampBoxes[b + 2] = x1;
        stampBoxes[b + 3] = z1;
    }

    // As fillCells, but only where it lowers the field: the band seeds (see bandSeedCells) are weaker
    // than a crossing's, and a block sitting under the surface must not rub out the ring of the one
    // standing through it in the same column.
    private static void fillCellsMin(float[] field, int x0, int z0, int x1, int z1, float value) {
        x0 = Math.max(0, x0);
        z0 = Math.max(0, z0);
        x1 = Math.min(cells - 1, x1);
        z1 = Math.min(cells - 1, z1);
        for (int z = z0; z <= z1; z++) {
            int row = z * cells;
            for (int x = x0; x <= x1; x++) {
                if (value < field[row + x]) {
                    field[row + x] = value;
                }
            }
        }
    }

    private static void fillCells(float[] field, int x0, int z0, int x1, int z1, float value) {
        x0 = Math.max(0, x0);
        z0 = Math.max(0, z0);
        x1 = Math.min(cells - 1, x1);
        z1 = Math.min(cells - 1, z1);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                field[z * cells + x] = value;
            }
        }
    }

    /**
     * The same transform over one rectangle only, for relaxing a fresh seed back into a field that is
     * already a valid distance map everywhere else (see restampDynamic).
     *
     * <p>Neighbours outside the rectangle are read but never written: they hold the block field's own
     * distances, which is exactly what a seed at the edge of the box has to relax against. The box is
     * the stamp widened by MAX_DIST, past which a new seed cannot lower anything the shader can tell
     * apart from "nothing near".
     */
    private static void chamferRegion(float[] field, int bx0, int bz0, int bx1, int bz1) {
        final int x0 = Math.max(0, bx0);
        final int z0 = Math.max(0, bz0);
        final int x1 = Math.min(cells - 1, bx1);
        final int z1 = Math.min(cells - 1, bz1);
        if (x0 > x1 || z0 > z1) {
            return;
        }
        final float d1 = 1.0F;
        final float d2 = DIAGONAL_STEP;
        final float d3 = KNIGHT_STEP;
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int i = z * cells + x;
                float d = field[i];
                if (x > 0) d = Math.min(d, field[i - 1] + d1);
                if (z > 0) d = Math.min(d, field[i - cells] + d1);
                if (x > 0 && z > 0) d = Math.min(d, field[i - cells - 1] + d2);
                if (x < cells - 1 && z > 0) d = Math.min(d, field[i - cells + 1] + d2);
                if (z > 0 && x > 1) d = Math.min(d, field[i - cells - 2] + d3);
                if (z > 0 && x < cells - 2) d = Math.min(d, field[i - cells + 2] + d3);
                if (z > 1 && x > 0) d = Math.min(d, field[i - 2 * cells - 1] + d3);
                if (z > 1 && x < cells - 1) d = Math.min(d, field[i - 2 * cells + 1] + d3);
                field[i] = d;
            }
        }
        for (int z = z1; z >= z0; z--) {
            for (int x = x1; x >= x0; x--) {
                int i = z * cells + x;
                float d = field[i];
                if (x < cells - 1) d = Math.min(d, field[i + 1] + d1);
                if (z < cells - 1) d = Math.min(d, field[i + cells] + d1);
                if (x < cells - 1 && z < cells - 1) d = Math.min(d, field[i + cells + 1] + d2);
                if (x > 0 && z < cells - 1) d = Math.min(d, field[i + cells - 1] + d2);
                if (z < cells - 1 && x < cells - 2) d = Math.min(d, field[i + cells + 2] + d3);
                if (z < cells - 1 && x > 1) d = Math.min(d, field[i + cells - 2] + d3);
                if (z < cells - 2 && x < cells - 1) d = Math.min(d, field[i + 2 * cells + 1] + d3);
                if (z < cells - 2 && x > 0) d = Math.min(d, field[i + 2 * cells - 1] + d3);
                field[i] = d;
            }
        }
    }

    // Two-pass chamfer distance transform: cheap O(n) approximate Euclidean distance (in cells), over
    // the whole field. One mask, in chamferRegion above, so the two can never disagree.
    private static void chamferDistance(float[] field) {
        chamferRegion(field, 0, 0, cells - 1, cells - 1);
    }

    // chamferRegion over a range per row (see chamferAround). The same two passes and the same mask.
    private static void chamferRows(float[] field, int[] minX, int[] maxX) {
        final float d1 = 1.0F;
        final float d2 = DIAGONAL_STEP;
        final float d3 = KNIGHT_STEP;
        for (int z = 0; z < cells; z++) {
            for (int x = minX[z]; x <= maxX[z]; x++) {
                int i = z * cells + x;
                float d = field[i];
                if (x > 0) d = Math.min(d, field[i - 1] + d1);
                if (z > 0) d = Math.min(d, field[i - cells] + d1);
                if (x > 0 && z > 0) d = Math.min(d, field[i - cells - 1] + d2);
                if (x < cells - 1 && z > 0) d = Math.min(d, field[i - cells + 1] + d2);
                if (z > 0 && x > 1) d = Math.min(d, field[i - cells - 2] + d3);
                if (z > 0 && x < cells - 2) d = Math.min(d, field[i - cells + 2] + d3);
                if (z > 1 && x > 0) d = Math.min(d, field[i - 2 * cells - 1] + d3);
                if (z > 1 && x < cells - 1) d = Math.min(d, field[i - 2 * cells + 1] + d3);
                field[i] = d;
            }
        }
        for (int z = cells - 1; z >= 0; z--) {
            for (int x = maxX[z]; x >= minX[z]; x--) {
                int i = z * cells + x;
                float d = field[i];
                if (x < cells - 1) d = Math.min(d, field[i + 1] + d1);
                if (z < cells - 1) d = Math.min(d, field[i + cells] + d1);
                if (x < cells - 1 && z < cells - 1) d = Math.min(d, field[i + cells + 1] + d2);
                if (x > 0 && z < cells - 1) d = Math.min(d, field[i + cells - 1] + d2);
                if (z < cells - 1 && x < cells - 2) d = Math.min(d, field[i + cells + 2] + d3);
                if (z < cells - 1 && x > 1) d = Math.min(d, field[i + cells - 2] + d3);
                if (z < cells - 2 && x < cells - 1) d = Math.min(d, field[i + 2 * cells + 1] + d3);
                if (z < cells - 2 && x > 0) d = Math.min(d, field[i + 2 * cells - 1] + d3);
                field[i] = d;
            }
        }
    }

    // Ease shown[] toward target[] so foam grows / fades gradually. Growth is a wavefront: land cells
    // seed foam at once, then foam may only advance stepCells past an already-foamed neighbour each
    // tick -- so it always sweeps outward from the contact edge instead of appearing everywhere.
    // Over the work rows only (workMin/workMax); every cell outside them is flat far water in both
    // fields and stays so. Widens the shown rows to wherever foam remains afterwards.
    private static boolean ease(float[] field, float[] show, float stepCells) {
        final float maxCells = MAX_DIST * cellsPerBlock;
        boolean changed = false;

        // Seed the contact and fade retreating foam back toward "far water".
        for (int z = 0; z < cells; z++) {
            for (int x = workMin[z]; x <= workMax[z]; x++) {
                int i = z * cells + x;
                float t = Math.min(field[i], maxCells);
                float s = show[i];
                if (t <= 0.001F) {
                    if (s != 0.0F) { // land cell: foam source, snap in immediately
                        show[i] = 0.0F;
                        changed = true;
                    }
                } else if (s < t) { // foam should retreat: rise toward target at the base rate
                    show[i] = Math.min(t, s + stepCells);
                    changed = true;
                }
            }
        }

        // Growth wavefront: a cell can only become as foamed as its nearest neighbour, plus what the
        // front is allowed to creep in a tick.
        //
        // Every move of the distance transform's own mask is here, at the same weight -- diagonals at
        // DIAGONAL_STEP, knight moves at KNIGHT_STEP. Spreading through the four orthogonal neighbours
        // alone is an L1 metric, so the front grew as a DIAMOND and then visibly reshaped into the
        // near-circular contours of the finished field once it caught up. Matching the metric makes the
        // advance per unit of distance the same in every direction, so the growing edge is already the
        // shape it is going to settle into.
        final float diagonalStep = stepCells * DIAGONAL_STEP;
        final float knightStep = stepCells * KNIGHT_STEP;
        // The wavefront reads up to two cells past a row's range, in rows up to two away, so copy the
        // rows' ranges with that margin.
        for (int z = 0; z < cells; z++) {
            int lo = Integer.MAX_VALUE;
            int hi = Integer.MIN_VALUE;
            for (int zz = Math.max(0, z - 2); zz <= Math.min(cells - 1, z + 2); zz++) {
                lo = Math.min(lo, workMin[zz]);
                hi = Math.max(hi, workMax[zz]);
            }
            if (lo <= hi) {
                lo = Math.max(0, lo - 2);
                hi = Math.min(cells - 1, hi + 2);
                System.arraycopy(show, z * cells + lo, scratch, z * cells + lo, hi - lo + 1);
            }
        }
        for (int z = 0; z < cells; z++) {
            for (int x = workMin[z]; x <= workMax[z]; x++) {
                int i = z * cells + x;
                float t = Math.min(field[i], maxCells);
                if (show[i] <= t) {
                    if (show[i] < maxCells) {
                        shownMin[z] = Math.min(shownMin[z], x);
                        shownMax[z] = Math.max(shownMax[z], x);
                    }
                    continue; // already grown to target
                }
                boolean west = x > 0;
                boolean east = x < cells - 1;
                boolean north = z > 0;
                boolean south = z < cells - 1;
                boolean west2 = x > 1;
                boolean east2 = x < cells - 2;
                boolean north2 = z > 1;
                boolean south2 = z < cells - 2;
                float allowed = scratch[i] + stepCells;      // front creeps stepCells per tick
                if (west) allowed = Math.min(allowed, scratch[i - 1] + stepCells);
                if (east) allowed = Math.min(allowed, scratch[i + 1] + stepCells);
                if (north) allowed = Math.min(allowed, scratch[i - cells] + stepCells);
                if (south) allowed = Math.min(allowed, scratch[i + cells] + stepCells);
                if (west && north) allowed = Math.min(allowed, scratch[i - cells - 1] + diagonalStep);
                if (east && north) allowed = Math.min(allowed, scratch[i - cells + 1] + diagonalStep);
                if (west && south) allowed = Math.min(allowed, scratch[i + cells - 1] + diagonalStep);
                if (east && south) allowed = Math.min(allowed, scratch[i + cells + 1] + diagonalStep);
                if (west2 && north) allowed = Math.min(allowed, scratch[i - cells - 2] + knightStep);
                if (east2 && north) allowed = Math.min(allowed, scratch[i - cells + 2] + knightStep);
                if (west2 && south) allowed = Math.min(allowed, scratch[i + cells - 2] + knightStep);
                if (east2 && south) allowed = Math.min(allowed, scratch[i + cells + 2] + knightStep);
                if (west && north2) allowed = Math.min(allowed, scratch[i - 2 * cells - 1] + knightStep);
                if (east && north2) allowed = Math.min(allowed, scratch[i - 2 * cells + 1] + knightStep);
                if (west && south2) allowed = Math.min(allowed, scratch[i + 2 * cells - 1] + knightStep);
                if (east && south2) allowed = Math.min(allowed, scratch[i + 2 * cells + 1] + knightStep);
                float s = Math.max(t, Math.min(show[i], allowed));
                if (s != show[i]) {
                    show[i] = s;
                    changed = true;
                }
                if (s < maxCells) {
                    shownMin[z] = Math.min(shownMin[z], x);
                    shownMax[z] = Math.max(shownMax[z], x);
                }
            }
        }
        return changed;
    }

    // Translate a shown field by (dx, dz) cells when the map re-centres; exposed cells become far water.
    private static void shiftField(float[] show, int dx, int dz) {
        final float maxCells = MAX_DIST * cellsPerBlock;
        for (int z = 0; z < cells; z++) {
            int sz = z + dz;
            for (int x = 0; x < cells; x++) {
                int sx = x + dx;
                scratch[z * cells + x] = (sx >= 0 && sx < cells && sz >= 0 && sz < cells)
                        ? show[sz * cells + sx]
                        : maxCells;
            }
        }
        System.arraycopy(scratch, 0, show, 0, show.length);
    }

    // Refill the texture's pixels from the fields -- the work rows only when `partial`, the rest being
    // unchanged since the last refill, else all of them -- and upload.
    private static void upload(boolean partial) {
        final float maxCells = MAX_DIST * cellsPerBlock;
        NativeImage img = texture.getPixels();
        NativeImage foamImg = foamColorTexture == null ? null : foamColorTexture.getPixels();
        // Hoisted out of the per-cell loop below: each of these is a config lookup, and the loop runs
        // once per texel of a grid that reaches 768x768.
        float[] planeCol = foamImg == null ? null : Config.planeColor();
        float[] foamCol = foamImg == null ? null : Config.foamColor();
        float foamWidth = foamImg == null ? 0.0F : (float) (double) Config.FOAM_WIDTH.get();
        // The pack-drawn plane samples this map with CLAMP_TO_EDGE and no in-bounds test (fog_plane.fsh
        // has one; a pack's program cannot), so whatever sits in the outermost ring is smeared across
        // the entire plane beyond the map. Pin that ring to bare plane colour: foam that happens to
        // touch the map's rim would otherwise streak to the horizon.
        int rimPixel = foamImg == null ? 0
                : packAbgr(planeCol[0] * PLANE_BASE, planeCol[1] * PLANE_BASE, planeCol[2] * PLANE_BASE);
        // Fade the foam out radially, from the camera at the map's centre, and reach zero before any
        // point the map's rim can be sampled at.
        //
        // The map is a square and the plane is not. Under a shader pack the plane is drawn as a 3x3
        // patch whose centre quad is this footprint and whose eight outer quads are the rim ring
        // stretched to the render distance (FogPlaneRenderer.drawThroughPack, and the rim pin just
        // below), so anything present inside the footprint and absent outside draws the footprint onto
        // the surface: a square standing around the player, measured at 95.8 blocks against a
        // half-width of 96.0. Easing towards the rim only decides how sharp that square is.
        //
        // Ending on a circle removes it instead of softening it. Past the fade every texel is the same
        // bare plane colour the rim ring is pinned to -- foamPixel with no foam is exactly rimPixel --
        // so the two sides of the patch seam hold identical colour and the seam cannot be seen. The
        // radius is the largest one that is certainly still on the map in every direction: the square's
        // inscribed circle, less a block because the map re-anchors on whole blocks and the camera is
        // therefore within a block of its centre. Nothing here is tuned by eye.
        float halfCells = cells * 0.5F;
        float fadeTo = Math.max(1.0F, halfCells - FOAM_FADE_MARGIN * cellsPerBlock);
        float fadeFrom = Math.min(FOAM_FADE_START * halfCells, fadeTo - cellsPerBlock);
        boolean stepDither = Config.STEP_DITHER.getAsBoolean();
        int cellX0 = originX * cellsPerBlock;
        int cellZ0 = originZ * cellsPerBlock;
        for (int z = 0; z < cells; z++) {
            int x0 = partial ? workMin[z] : 0;
            int x1 = partial ? workMax[z] : cells - 1;
            for (int x = x0; x <= x1; x++) {
                int i = z * cells + x;
                float d = Math.min(shown[i], maxCells);
                float dp = Math.min(shownP[i], maxCells);
                float dn = Math.min(shownN[i], maxCells);
                int v = (int) (d / maxCells * 255.0F + 0.5F) & 0xFF;
                int g = (int) (dp / maxCells * 255.0F + 0.5F) & 0xFF;
                int bl = (int) (dn / maxCells * 255.0F + 0.5F) & 0xFF;
                // ABGR: R = crossing solid/entity dist, G = plant dist, B = near-surface (band) dist
                img.setPixelRGBA(x, z, 0xFF000000 | (bl << 16) | (g << 8) | v);
                if (foamImg != null) {
                    float rx = x - halfCells + 0.5F;
                    float rz = z - halfCells + 0.5F;
                    float keep = 1.0F - Mth.clamp(((float) Math.sqrt(rx * rx + rz * rz) - fadeFrom)
                            / (fadeTo - fadeFrom), 0.0F, 1.0F);
                    keep = keep * keep * (3.0F - 2.0F * keep); // smoothstep, as the shaders'
                    foamImg.setPixelRGBA(x, z, keep <= 0.0F ? rimPixel
                            : foamPixel(Math.min(d, dn) / cellsPerBlock, dp / cellsPerBlock, planeCol,
                                    foamCol, foamWidth, keep,
                                    stepDither ? BAYER[(cellX0 + x) & 3][(cellZ0 + z) & 3] : -1.0F));
                }
            }
        }
        texture.upload();
        if (foamColorTexture != null) {
            foamColorTexture.upload();
        }
    }

    // The foam ramp fog_plane.fsh computes per fragment (via fogged_foam.glsl), evaluated on the CPU
    // into a finished colour for the pack-drawn plane. MUST stay in step with those two -- the same
    // look reached by another route; only the shader path also adds the surface spots.
    private static final float FOAM_STEPS = 4.0F;     // == fog_plane.fsh FOAM_STEPS
    // == fogged_dither.glsl FOGGED_BAYER, indexed [cellX & 3][cellZ & 3] as fogged_bayerCell does
    private static final float[][] BAYER = {
            {1.0F / 17.0F, 9.0F / 17.0F, 3.0F / 17.0F, 11.0F / 17.0F},
            {13.0F / 17.0F, 5.0F / 17.0F, 15.0F / 17.0F, 7.0F / 17.0F},
            {4.0F / 17.0F, 12.0F / 17.0F, 2.0F / 17.0F, 10.0F / 17.0F},
            {16.0F / 17.0F, 8.0F / 17.0F, 14.0F / 17.0F, 6.0F / 17.0F},
    };
    private static final float SOLID_STRENGTH = 1.0F; // == fogged_foam.glsl FOGGED_SOLID_STRENGTH
    private static final float PLANT_STRENGTH = 0.5F; // == fogged_foam.glsl FOGGED_PLANT_STRENGTH
    private static final float PLANE_BASE = 0.92F;    // == fog_plane.fsh planarFog's plane-colour scale
    // Where the foam starts fading out, as a share of the map's half-width, measured from the camera
    // outwards. It ends at the inscribed radius less a block -- see the note in upload() for why that
    // is the number and not a taste. Must match FOGGED_FOAM_FADE_START in fogged_iris.glsl and
    // fogged_foam.glsl, which fade the dissolve off over the same circle.
    static final float FOAM_FADE_START = 0.60F;
    /** Blocks of margin inside the inscribed circle: the camera sits within one block of the centre. */
    static final float FOAM_FADE_MARGIN = 1.0F;

    // threshold: the cell's Bayer value with stepDither on, or below zero for the plain stair-step
    // (== fog_plane.fsh fogged_foamStep).
    private static int foamPixel(float solidBlocks, float plantBlocks,
                                 float[] plane, float[] foamCol, float width, float keep, float threshold) {
        float foam = 0.0F;
        if (width > 0.0F) {
            float solidEdge = SOLID_STRENGTH
                    * (1.0F - Mth.clamp(solidBlocks / (width * SOLID_STRENGTH), 0.0F, 1.0F));
            float plantEdge = PLANT_STRENGTH
                    * (1.0F - Mth.clamp(plantBlocks / (width * PLANT_STRENGTH), 0.0F, 1.0F));
            float edge = Math.max(solidEdge, plantEdge);
            foam = edge * edge * (3.0F - 2.0F * edge);
            float q = foam * FOAM_STEPS;
            float lo = (float) Math.floor(q);
            foam = (threshold < 0.0F ? (float) Math.ceil(q) : q - lo > threshold ? lo + 1.0F : lo) / FOAM_STEPS;
            foam *= foamCol[3] * keep;
        }
        return packAbgr(Mth.lerp(foam, plane[0] * PLANE_BASE, foamCol[0]),
                Mth.lerp(foam, plane[1] * PLANE_BASE, foamCol[1]),
                Mth.lerp(foam, plane[2] * PLANE_BASE, foamCol[2]));
    }

    private static int packAbgr(float r, float g, float b) {
        return 0xFF000000 | (channel(b) << 16) | (channel(g) << 8) | channel(r);
    }

    private static int channel(float v) {
        return (int) (Mth.clamp(v, 0.0F, 1.0F) * 255.0F + 0.5F) & 0xFF;
    }

    // Throw this tick's foam: the standing kind along the waterline, and the spray of anything
    // crossing it.
    private static void emitFoamParticles(Level level, Vec3 camPos, int boundaryY) {
        // Match the rendered plane height exactly (boundaryY is its floored block row, not the surface).
        double surfaceY = Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
        if (Math.abs(camPos.y - surfaceY) > 32.0) {
            return; // only when the camera is near the surface
        }
        if ((double) Config.FOAM_WIDTH.get() <= 0.0) {
            return; // foam turned off
        }
        // Tinted campfire smoke (see FoamParticle): big, soft and slow-rising, which reads as foam
        // mist lying on the surface. The nozzle filter's own puffs are a separate, smaller particle.
        float[] pc = Config.foamColor();
        ParticleOptions foam = ColorParticleOption.create(ModParticles.FOAM.get(),
                whitened(pc[0]), whitened(pc[1]), whitened(pc[2]));
        RandomSource rnd = level.getRandom();

        // Standing foam along the waterline comes from FoamSites, which remembers where the boundary
        // touches blocks per chunk and so reaches the whole loaded world. Scattering samples over this
        // map instead capped foam at the map's own edge and made it travel with the player.
        double band = foamBand();
        FoamSites.tick(level, camPos, boundaryY, surfaceY, band, foam, rnd);
        // Spray thrown by things crossing it is per-entity and stays close, since that is where the
        // entities are.
        emitWakeFoam(level, camPos, surfaceY, band, foam, rnd);
    }

    // One channel of the foam colour, lifted toward white by SPRAY_WHITENING.
    private static float whitened(float c) {
        return c + (1.0F - c) * SPRAY_WHITENING;
    }

    // How far out spray is emitted: the map's own half-extent, capped. The entities that throw it are
    // the ones the map already tracks, so there is no point reaching past it.
    private static int particleRadius() {
        return Math.min(size / 2, PARTICLE_RADIUS_MAX);
    }

    // Spray thrown by things crossing the surface, as opposed to the standing foam a shoreline makes.
    // Emission is once a tick, so everything here back-dates its spawn point along the travel of that
    // tick: a particle goes where the thing WAS at some fraction of the way through, not where it ended
    // up. Without that the trail is a row of clumps one tick's travel apart.
    // Rate scales with speed, so a drifting boat barely fizzes and a fast one throws a real wake, and
    // the particles are biased into the arc AHEAD of the direction of travel -- foam piles up against
    // the bow rather than ringing the hull evenly. Each one then carries a fraction of the entity's
    // own motion, so it slides backwards past it and is left behind instead of riding along.
    private static void emitWakeFoam(Level level, Vec3 camPos, double surfaceY, double band,
                                     ParticleOptions foam, RandomSource rnd) {
        double reach = particleRadius();
        double slab = Math.max(2.0, band);
        AABB area = new AABB(camPos.x - reach, surfaceY - slab, camPos.z - reach,
                camPos.x + reach, surfaceY + slab, camPos.z + reach);
        // The band, not a strict straddle: a mob wading just under the surface or clearing it on a jump
        // is half in it, and the painted ring already says so (see bandSeedCells). Its spray fades over
        // the same half block, so the two agree instead of the particles cutting out a block early.
        for (Entity e : level.getEntities((Entity) null, area,
                e -> surfaceGap(e.getBoundingBox(), surfaceY) < band)) {
            float near = bandFalloff(surfaceGap(e.getBoundingBox(), surfaceY), band);
            // How far it actually moved last tick, NOT getDeltaMovement(): on the client only the
            // local player keeps a meaningful delta, while every other entity is interpolated from
            // position packets and reports nothing. Reading the positions is what makes other players,
            // mobs and ridden boats throw a wake at all instead of only the player under your own feet.
            Vec3 motion = new Vec3(e.getX() - e.xOld, e.getY() - e.yOld, e.getZ() - e.zOld);
            double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            double strength = Math.min(1.0, speed / FULL_SPEED);

            // The falloff scales how MUCH is thrown, not how fast the thing is going: strength still
            // shapes the wake into a bow wave, so a mob half under the surface throws a thinner version
            // of the same wake rather than a slow-looking one.
            int count = (int) Math.round(strength * WAKE_MAX * near);
            if (count == 0) {
                // Still, or nearly: the odd puff so a moored boat is not bone dry, and no more.
                if (rnd.nextFloat() >= STATIC_CHANCE * near) {
                    continue;
                }
                count = 1;
            }

            AABB box = e.getBoundingBox();
            double cx = (box.minX + box.maxX) * 0.5;
            double cz = (box.minZ + box.maxZ) * 0.5;
            double radius = 0.5 * Math.max(box.maxX - box.minX, box.maxZ - box.minZ);
            // Which way is "ahead". A still entity has no heading, so its lone puff goes anywhere.
            double heading = speed > 1.0e-4 ? Math.atan2(motion.z, motion.x) : rnd.nextDouble() * Math.PI * 2.0;

            for (int i = 0; i < count; i++) {
                // Spread narrows as it speeds up: slow means an even ring, fast means a tight bow wave.
                double spread = Math.PI * (1.0 - strength) + FRONT_ARC * strength;
                double angle = heading + (rnd.nextDouble() * 2.0 - 1.0) * spread;
                double ox = Math.cos(angle);
                double oz = Math.sin(angle);
                double rr = radius * (0.9 + rnd.nextDouble() * 0.35);
                double push = WAKE_SPREAD * (0.4 + strength);
                // Spread the tick's spray along the ground the entity actually covered, instead of
                // dropping all of it where the entity happens to be now. Stratified (i + random, not
                // just random) so the samples spread evenly over the interval rather than bunching.
                double t = (i + rnd.nextDouble()) / count;
                double lagX = motion.x * (1.0 - t);
                double lagZ = motion.z * (1.0 - t);
                spawnWake(level, foam, cx - lagX + ox * rr, surfaceY, cz - lagZ + oz * rr,
                        ox, oz, push, motion);
            }
        }

        // Sable ships and contraptions are not entities and never appear in the query above, but they
        // break the surface exactly as a boat does. Their waterline is sampled directly (see
        // SableCompatibility.sampleWakes), and each hit throws spray outward from the sub-level's
        // centre with the sub-level's own travel behind it.
        if (SABLE && Config.SABLE_FOAM.getAsBoolean()) {
            SableCompatibility.sampleWakes(level, surfaceY, band, SUBLEVEL_PROBES, rnd,
                    (wx, wz, motion, weight) -> {
                double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
                double strength = Math.min(1.0, speed / FULL_SPEED);
                // A probe that only came near the surface throws that much less often -- the same
                // falloff the entity wake gets, applied to how much is thrown rather than to the speed.
                if (weight < 1.0F && rnd.nextFloat() >= weight) {
                    return;
                }
                if (strength <= 0.0 && rnd.nextFloat() >= SUBLEVEL_STATIC_CHANCE) {
                    return; // moored: the odd puff along the hull, no more
                }
                // No centre to push away from here -- the probe landed somewhere along the hull, so
                // the spray leaves along the direction of travel, which is where a bow wave goes.
                double heading = speed > 1.0e-4 ? Math.atan2(motion.z, motion.x)
                        : rnd.nextDouble() * Math.PI * 2.0;
                double angle = heading + (rnd.nextDouble() * 2.0 - 1.0) * FRONT_ARC;
                // Same back-dating along the tick's travel as above. Probes arrive one at a time here
                // with no count to stratify against, so the sample is a plain random point in the
                // interval -- over a hull's worth of probes that still fills the gap evenly.
                double t = rnd.nextDouble();
                spawnWake(level, foam, wx - motion.x * (1.0 - t), surfaceY, wz - motion.z * (1.0 - t),
                        Math.cos(angle), Math.sin(angle), WAKE_SPREAD * (0.4 + strength), motion);
            });
        }
    }

    // One piece of spray: it travels OUTWARD from where it was thrown, faster the faster the thing
    // that threw it, while giving up that thing's own motion. Outward carries the wave off across the
    // surface; losing the motion means whatever threw it leaves it behind rather than dragging it
    // along -- the two together open into a V behind anything moving quickly.
    private static void spawnWake(Level level, ParticleOptions foam, double x, double surfaceY, double z,
                                  double outX, double outZ, double push, Vec3 motion) {
        level.addParticle(foam, x, surfaceY + 0.05, z,
                outX * push - motion.x * WAKE_DRAG, 0.0, outZ * push - motion.z * WAKE_DRAG);
    }

    // (Re)allocate the backing arrays and GPU texture for a new block edge length and/or cell resolution.
    private static void rebuild(int newSize, int newCellsPerBlock) {
        size = newSize;
        cellsPerBlock = newCellsPerBlock;
        final float maxCells = MAX_DIST * cellsPerBlock;
        cells = size * cellsPerBlock;
        target = new float[cells * cells];
        shown = new float[cells * cells];
        targetP = new float[cells * cells];
        shownP = new float[cells * cells];
        targetN = new float[cells * cells];
        shownN = new float[cells * cells];
        scratch = new float[cells * cells];
        blockDist = new float[cells * cells];
        blockDistN = new float[cells * cells];
        java.util.Arrays.fill(blockDist, maxCells);
        java.util.Arrays.fill(blockDistN, maxCells);
        java.util.Arrays.fill(target, maxCells);
        java.util.Arrays.fill(targetP, maxCells);
        java.util.Arrays.fill(targetN, maxCells);
        java.util.Arrays.fill(shown, maxCells); // start with no foam so it eases in
        java.util.Arrays.fill(shownP, maxCells);
        java.util.Arrays.fill(shownN, maxCells);
        seedMin = new int[cells];
        seedMax = new int[cells];
        seedMinP = new int[cells];
        seedMaxP = new int[cells];
        seedMinN = new int[cells];
        seedMaxN = new int[cells];
        shownMin = new int[cells];
        shownMax = new int[cells];
        workMin = new int[cells];
        workMax = new int[cells];
        bandMin = new int[cells];
        bandMax = new int[cells];
        rowsClear(seedMin, seedMax);
        rowsClear(seedMinP, seedMaxP);
        rowsClear(seedMinN, seedMaxN);
        rowsClear(shownMin, shownMax);
        workFull = true;
        if (texture != null) {
            texture.close();
        }
        if (foamColorTexture != null) {
            foamColorTexture.close();
            foamColorTexture = null; // re-created at the new size by the next update()
        }
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, cells, cells, false);
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                img.setPixelRGBA(x, z, 0xFFFFFFFF); // start "all far water" (R = G = B = 255)
            }
        }
        texture = new DynamicTexture(img);
        texture.setFilter(true, false); // LINEAR for a smooth ring between cells; no mipmaps

        // Clamp to edge so sampling near the map border doesn't wrap to the far side.
        GlStateManager._bindTexture(texture.getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        lastBoundaryY = Integer.MIN_VALUE;
        lastRecomputeTick = Long.MIN_VALUE;
        lastTick = Long.MIN_VALUE;
    }
}
