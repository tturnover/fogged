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
    private static int size = 0;          // current block edge length of the map
    private static int cellsPerBlock = 4; // sub-block grid resolution; see Config.WATERLINE_CELLS_PER_BLOCK
    private static int cells = 0;         // texel/cell edge length = size * cellsPerBlock
    // Two independent foam fields so full-strength (solid/entity) foam and half-strength plant foam
    // form separately and never override one another -- they are combined by max in the shader.
    private static float[] target = new float[0];   // distance to nearest solid block / entity
    private static float[] shown = new float[0];     // eased version of target (R channel)
    private static float[] targetP = new float[0];  // distance to nearest plant
    private static float[] shownP = new float[0];    // eased version of targetP (G channel)
    private static float[] scratch = new float[0];
    // Distance to the nearest solid BLOCK only, chamfered, kept between rescans. Entities and Sable
    // sub-levels are stamped onto a copy of it every tick (see restampDynamic), so what moves is
    // re-seeded at tick rate while the expensive block scan keeps its own slower cadence.
    private static float[] blockDist = new float[0];

    // Cell boxes stamped by whatever was crossing last time, so only those get relaxed back in. On
    // overflow the whole field is chamfered instead, which is what the map did every rescan anyway.
    private static final int MAX_STAMP_BOXES = 32;
    private static final int[] stampBoxes = new int[MAX_STAMP_BOXES * 4];
    private static int stampBoxCount;
    private static boolean stampOverflow;
    private static boolean lastStamped;
    private static int originX;           // world block X of the map's corner
    private static int originZ;           // world block Z of the map's corner
    private static int lastBoundaryY = Integer.MIN_VALUE;
    private static long lastRecomputeTick = Long.MIN_VALUE;
    private static long lastTick = Long.MIN_VALUE;

    private WaterlineMap() {
    }

    public static int textureId() {
        return texture == null ? 0 : texture.getId();
    }

    public static int size() {
        return size;
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
    // eases the shown field toward it once per tick, and sprinkles foam particles.
    public static void update(Level level, Vec3 camPos, int boundaryY, int mapBlocks) {
        int want = Mth.clamp(mapBlocks, MIN_SIZE, MAX_SIZE);
        int wantC = Mth.clamp(Config.WATERLINE_CELLS_PER_BLOCK.getAsInt(), 1, 4);
        if (texture == null || want != size || wantC != cellsPerBlock) {
            rebuild(want, wantC);
        }

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
            shiftField(target, dx, dz);
            shiftField(targetP, dx, dz);
            shiftField(blockDist, dx, dz);
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
            reseedAll(level, boundaryY);
            finishRecompute();
            recomputed = true;
        }

        // Ease + re-upload at most once per tick (bounded cost), or immediately after a re-centre.
        if (newTick || originMoved) {
            // Whatever moves is re-stamped every tick, so a boat's ring keeps up with it rather than
            // waiting on the block rescan's cadence above.
            boolean restamped = restampDynamic(level, boundaryY);
            long elapsed = lastTick == Long.MIN_VALUE ? 1 : Math.max(1, tick - lastTick);
            float step = EASE_CELLS_PER_TICK * elapsed;
            boolean changed = ease(target, shown, step);
            changed |= ease(targetP, shownP, step);
            lastTick = tick;
            if (changed || originMoved || recomputed || restamped) {
                upload();
            }
            if (newTick) {
                emitFoamParticles(level, camPos, boundaryY);
            }
        }
    }

    // Reseed the whole block-state field. Solids and flowing water (currents, falls) seed the full
    // field; plants the plant field. Still water (source) and air are left as open surface so foam has
    // somewhere to fade into.
    private static void reseedAll(Level level, int boundaryY) {
        java.util.Arrays.fill(target, INF);
        java.util.Arrays.fill(targetP, INF);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bz = 0; bz < size; bz++) {
            for (int bx = 0; bx < size; bx++) {
                reseedColumn(level, pos, boundaryY, bx, bz);
            }
        }
    }

    // Reseed one block column's cells from its current block state. Relies on reseedAll having already
    // cleared the whole field to INF, so this only needs to stamp the cells that turn out to be solid.
    private static void reseedColumn(Level level, BlockPos.MutableBlockPos pos, int boundaryY, int bx, int bz) {
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
        if (columnClosed(state)) {
            fillCells(target, x0, z0, x1, z1, 0.0F);
        } else if (plant) {
            fillCells(targetP, x0, z0, x1, z1, 0.0F);
        }
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
        chamferDistance(target);
        System.arraycopy(target, 0, blockDist, 0, target.length);
        chamferDistance(targetP);
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
    private static boolean restampDynamic(Level level, int boundaryY) {
        final int C = cellsPerBlock;
        AABB area = new AABB(originX, boundaryY - 2.0, originZ, originX + size, boundaryY + 2.0, originZ + size);
        var crossing = level.getEntities((Entity) null, area,
                e -> e.getBoundingBox().minY <= boundaryY + 0.5 && e.getBoundingBox().maxY >= boundaryY - 0.5);
        boolean sable = SABLE && Config.SABLE_FOAM.getAsBoolean();
        if (crossing.isEmpty() && !sable && !lastStamped) {
            return false; // nothing crossed last tick and nothing crosses now: the field is untouched
        }

        System.arraycopy(blockDist, 0, target, 0, target.length);
        stampBoxCount = 0;
        stampOverflow = false;

        for (Entity e : crossing) {
            AABB b = e.getBoundingBox();
            int x0 = Mth.floor((b.minX - originX) * C);
            int x1 = Mth.floor((b.maxX - originX) * C);
            int z0 = Mth.floor((b.minZ - originZ) * C);
            int z1 = Mth.floor((b.maxZ - originZ) * C);
            fillCells(target, x0, z0, x1, z1, 0.0F);
            addStampBox(x0, z0, x1, z1);
        }

        // A sub-level's stamped cells are scattered over its hull's footprint, so one box covers the lot.
        if (sable) {
            int[] u = { Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE };
            SableCompatibility.stampSubLevels(level, boundaryY, originX, originZ, size, C,
                    (cellX, cellZ) -> {
                        target[cellZ * cells + cellX] = 0.0F;
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
        } else {
            int reach = Mth.ceil(MAX_DIST) * C;
            for (int i = 0; i < stampBoxCount; i++) {
                int b = i * 4;
                chamferRegion(target, stampBoxes[b] - reach, stampBoxes[b + 1] - reach,
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

    // Ease shown[] toward target[] so foam grows / fades gradually. Growth is a wavefront: land cells
    // seed foam at once, then foam may only advance stepCells past an already-foamed neighbour each
    // tick -- so it always sweeps outward from the contact edge instead of appearing everywhere.
    private static boolean ease(float[] field, float[] show, float stepCells) {
        final float maxCells = MAX_DIST * cellsPerBlock;
        boolean changed = false;

        // Seed the contact and fade retreating foam back toward "far water".
        for (int i = 0; i < show.length; i++) {
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
        System.arraycopy(show, 0, scratch, 0, show.length);
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int i = z * cells + x;
                float t = Math.min(field[i], maxCells);
                if (show[i] <= t) {
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

    private static void upload() {
        final float maxCells = MAX_DIST * cellsPerBlock;
        NativeImage img = texture.getPixels();
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int i = z * cells + x;
                float d = Math.min(shown[i], maxCells);
                float dp = Math.min(shownP[i], maxCells);
                int v = (int) (d / maxCells * 255.0F + 0.5F) & 0xFF;
                int g = (int) (dp / maxCells * 255.0F + 0.5F) & 0xFF;
                img.setPixelRGBA(x, z, 0xFF000000 | (g << 8) | v); // ABGR: R = solid/entity dist, G = plant dist
            }
        }
        texture.upload();
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
        FoamSites.tick(level, camPos, boundaryY, surfaceY, foam, rnd);
        // Spray thrown by things crossing it is per-entity and stays close, since that is where the
        // entities are.
        emitWakeFoam(level, camPos, surfaceY, foam, rnd);
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
    private static void emitWakeFoam(Level level, Vec3 camPos, double surfaceY,
                                     ParticleOptions foam, RandomSource rnd) {
        double reach = particleRadius();
        AABB area = new AABB(camPos.x - reach, surfaceY - 2.0, camPos.z - reach,
                camPos.x + reach, surfaceY + 2.0, camPos.z + reach);
        for (Entity e : level.getEntities((Entity) null, area,
                e -> e.getBoundingBox().minY <= surfaceY && e.getBoundingBox().maxY >= surfaceY)) {
            // How far it actually moved last tick, NOT getDeltaMovement(): on the client only the
            // local player keeps a meaningful delta, while every other entity is interpolated from
            // position packets and reports nothing. Reading the positions is what makes other players,
            // mobs and ridden boats throw a wake at all instead of only the player under your own feet.
            Vec3 motion = new Vec3(e.getX() - e.xOld, e.getY() - e.yOld, e.getZ() - e.zOld);
            double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            double strength = Math.min(1.0, speed / FULL_SPEED);

            int count = (int) Math.round(strength * WAKE_MAX);
            if (count == 0) {
                // Still, or nearly: the odd puff so a moored boat is not bone dry, and no more.
                if (rnd.nextFloat() >= STATIC_CHANCE) {
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
            SableCompatibility.sampleWakes(level, surfaceY, SUBLEVEL_PROBES, rnd, (wx, wz, motion) -> {
                double speed = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
                double strength = Math.min(1.0, speed / FULL_SPEED);
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
        scratch = new float[cells * cells];
        blockDist = new float[cells * cells];
        java.util.Arrays.fill(blockDist, maxCells);
        java.util.Arrays.fill(target, maxCells);
        java.util.Arrays.fill(targetP, maxCells);
        java.util.Arrays.fill(shown, maxCells); // start with no foam so it eases in
        java.util.Arrays.fill(shownP, maxCells);
        if (texture != null) {
            texture.close();
        }
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, cells, cells, false);
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                img.setPixelRGBA(x, z, 0xFF00FFFF); // start "all far water" (R = G = 255)
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
