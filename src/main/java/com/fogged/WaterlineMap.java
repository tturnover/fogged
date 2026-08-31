package com.fogged;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.fogged.registry.ModParticles;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A world-aligned "distance to the nearest waterline" map, centred on the camera. The grid is stored
// at cellsPerBlock cells per block (sub-block resolution) so the foam contour is crisp on the
// pixel grid. Each cell's red channel stores the distance (normalised by MAX_DIST blocks) to the
// nearest solid block / boundary-crossing entity, and its blue channel masks off the cells where the
// surface passes through a liquid. The plane shader samples this to draw foam as a stable ring --
// world-space, so it never cuts off with view angle -- and to cut the murk out of liquids entirely.
//
// Two grids are kept: target[] is the freshly-computed distance field; shown[] eases toward it each
// tick so foam accumulates and fades gradually instead of popping in. shown[] is world-anchored, so
// when the map re-centres as the camera walks, its contents are shifted to keep foam in place.
//
// The map's block edge length follows the render distance (clamped to MIN_SIZE..MAX_SIZE), and its
// sub-block resolution is dropped as it widens so the per-tick cost stays put (see MAX_CELLS). Foam
// is eased out over the rim of the square's inscribed circle in the shader, so what the map's finite
// reach shows the player is a soft ring rather than the square itself (see fogged_foam.glsl).
public final class WaterlineMap {

    public static final float MAX_DIST = 8.0F;       // distances are clamped/stored up to this many blocks
    // The block scan is event-driven: the boundary moving a row, a block changing at that row, or the
    // map scrolling onto ground it has not read yet. This is the BACKSTOP for none of those arriving --
    // the block-change hook is a require=0 mixin and another renderer can replace the method it rides
    // on, and a map that never rescans is a far worse failure than an occasional wasted scan.
    private static final int FALLBACK_INTERVAL = 200;
    // ...and how long it waits once the block-change hook has proven it works: the hooks report the
    // exact columns that changed (see markDirtyColumns), so with them alive the whole-map re-read is
    // only there to catch what no hook covers, and it is size^2 block lookups every time it fires.
    private static final int FALLBACK_INTERVAL_HOOKED = 600;
    // How far the camera may drift from the map's centre before the map is re-centred on it. Every
    // re-centre shifts six full grids, re-reads the strip it scrolled onto, rebuilds the surface mesh
    // and re-uploads the whole texture -- so doing it on every block walked was the single most
    // expensive thing here while moving. The map is far wider than the foam it shows (the ring fades
    // out at the inscribed circle -- see fogged_foam.glsl), so it can sit a few blocks off-centre
    // without the fade ever reaching ground the map has no data for.
    private static final int RECENTRE_STEP = 8;

    // How many effect pixels the plane draws per map cell. The distance field is data -- what it costs
    // is why its resolution is capped (see MAX_CELLS) -- while the grid the foam edge and the surface
    // spots are drawn on is just a snap in the shader, so it can be finer than the data for free: the
    // map is filtered linearly, so a half-cell step still lands on a real distance between two cells.
    private static final int EFFECT_DETAIL = 2;
    private static final float EASE_CELLS_PER_TICK = 0.25F; // how fast shown[] chases target[] (foam ramp)
    private static final int MIN_SIZE = 48;           // clamp the render-distance-driven block edge
    private static final int MAX_SIZE = 256;
    // Ceiling on the grid's CELL edge, which is what every per-tick pass over the map costs: the ease,
    // the chamfer, the upload and the texture all scale with cells^2, not with blocks. A map that grows
    // with the render distance therefore drops sub-block resolution as it widens (see update), so a far
    // view gets coarser foam rather than a slower game. 768 is what the old 192-block map cost at 4
    // cells per block, so nothing here is more expensive than it already was.
    private static final int MAX_CELLS = 768;
    private static final float INF = 1.0e9F;

    // Cost of a diagonal step, against 1.0 for an orthogonal one. Used by BOTH the distance transform
    // and the foam's growth wavefront, and they have to agree: the transform's contours are what the
    // foam settles into, so a front that spreads in a different metric visibly changes shape as it
    // catches up. The same goes for KNIGHT_STEP below -- add a move to one and it belongs in the other.
    private static final float DIAGONAL_STEP = 1.41421356F;   // sqrt(2)
    // ...and the knight move (1,2), sqrt(5). A mask of only orthogonal and diagonal steps is a 3x3
    // chamfer, whose distances are off by up to ~7.6% and are worst halfway between the two -- which
    // is a real shape, not noise: the iso-distance contours come out as octagons, and once the foam
    // quantises them into bands the ring reads as a star. Adding the knight moves makes it a 5x5
    // chamfer and drops the error to ~2%, which those bands no longer resolve.
    private static final float KNIGHT_STEP = 2.23606798F;

    // Where the surface sits inside its block row: boundaryY is floor(surfaceY), so this is the
    // leftover fraction (PLANE_SURFACE_OFFSET is -0.38, putting it 0.62 up the block).
    private static final double SURFACE_FRACTION = 1.0 + Config.PLANE_SURFACE_OFFSET;

    // Whether the Sable physics mod is present, so its sub-levels can also generate foam.
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    // Foam particles, within this radius of the camera each tick.
    //
    // They mark CONTACT, not the foam band: the ring painted on the surface fades out over
    // FOAM_WIDTH blocks, but spray only belongs where something actually meets the surface, so the
    // sampler below keeps just the innermost cells and lets the rest of the band stay painted-only.
    // The radius follows the map (so it is bounded by the data that feeds it) up to this cap, rather
    // than the short fixed reach it had -- foam that stops a dozen blocks out reads as following the
    // player around. Particular gets its long reach for free by keying off cached waterfall positions
    // per chunk; ours is bounded by how far the waterline map itself is computed.
    private static final int PARTICLE_RADIUS_MAX = 48;
    private static final int SAMPLES_AT_BASE_RADIUS = 24;  // samples taken, not particles spawned
    private static final int BASE_RADIUS = 14;             // the radius that sample count was tuned at
    private static final int SAMPLES_MAX = 384;            // ceiling on the per-tick sampling cost
    private static final float CONTACT_CELLS = 1.0F;       // how close to the crossing counts as contact

    // A crossing entity throws spray in proportion to how fast it is going, piled up ahead of it.
    private static final double FULL_SPEED = 0.4;          // blocks/tick counting as "fast"
    private static final int WAKE_MAX = 6;                 // particles per entity per tick at full speed
    private static final float STATIC_CHANCE = 0.12F;      // ...and how often a still one manages one
    private static final double FRONT_ARC = 0.9;           // radians of spread the bow wave piles into
    private static final double WAKE_DRAG = 0.6;           // how much of the entity's motion the spray keeps
    private static final double WAKE_SPREAD = 0.06;        // blocks/tick the wave travels outward at
    private static final int SUBLEVEL_PROBES = 24;         // waterline samples per Sable sub-level per tick

    private static DynamicTexture texture;
    private static int size = 0;          // current block edge length of the map
    private static int cellsPerBlock = 4; // sub-block grid resolution; see Config.WATERLINE_CELLS_PER_BLOCK
    private static int cells = 0;         // texel/cell edge length = size * cellsPerBlock
    // Two independent foam fields so full-strength (solid/entity) foam and half-strength plant foam
    // form separately and never override one another -- they are combined by max in the shader.
    // Distance to the nearest solid BLOCK only, chamfered, kept between rescans. Entities and Sable
    // sub-levels are stamped on top of a copy of this every tick (see restampDynamic), which is what
    // lets a moving boat's foam ring follow it smoothly instead of jumping on the rescan cadence.
    // Re-chamfering from an already-valid distance field is exact: the transform is a min-relaxation,
    // so adding new zero seeds to it yields min(distance to blocks, distance to the new seeds).
    private static float[] blockDist = new float[0];
    private static float[] target = new float[0];   // distance to nearest solid block / entity
    private static float[] shown = new float[0];     // eased version of target (R channel)
    private static float[] targetP = new float[0];  // distance to nearest plant
    private static float[] shownP = new float[0];    // eased version of targetP (G channel)
    // Hard mask (0 or 1, no easing or distance transform): is the block the surface passes through at
    // this cell a fluid? The murk is not drawn inside a liquid at all, so this is a cut-out, not a
    // gradient. Uploaded to the B channel.
    private static float[] fluidMask = new float[0];
    // Per-BLOCK (not per-cell) openness: is the surface free to exist in this column? False where it
    // passes through a solid block or any liquid. FogPlaneMesh builds its geometry straight from this,
    // so a closed column is a quad that is never emitted rather than a fragment that is discarded.
    private static boolean[] openBlocks = new boolean[0];
    // Bumped whenever openBlocks can have changed -- a rescan, an origin move, a resize -- and NOT by
    // the per-tick foam ease. FogPlaneMesh rebuilds off this and nothing else.
    private static int revision;
    private static float[] scratch = new float[0];
    private static int originX;           // world block X of the map's corner
    private static int originZ;           // world block Z of the map's corner
    private static boolean originValid;   // false until the map has been placed (or after a resize)
    private static int lastBoundaryY = Integer.MIN_VALUE;
    private static long lastRecomputeTick = Long.MIN_VALUE;
    // Pending block-derived rescans. The hooks that report them (see markDirtyAt / markSectionDirty)
    // run off the render thread, so the boxes are unioned under a lock and drained by update().
    //
    // They are kept as BOXES rather than one global flag because a rescan of the whole map is ~size^2
    // block lookups, and the things that dirty it -- a block edit, a chunk section loading -- touch a
    // few columns each. Overflowing the list falls back to the whole map, which is correct, just slow.
    private static final Object DIRTY_LOCK = new Object();
    private static final int MAX_DIRTY_BOXES = 16;
    private static final int[] dirtyBoxes = new int[MAX_DIRTY_BOXES * 4]; // world x0, z0, x1, z1
    private static final int[] dirtyDrained = new int[MAX_DIRTY_BOXES * 4];
    private static int dirtyCount;
    private static boolean dirtyAll = true;

    // Foam is at rest: shown[] has caught up with target[] everywhere, so the ease -- two passes over
    // every cell of the map -- has nothing to do until something moves again.
    private static boolean foamSettled;
    // Where it might NOT be at rest, in cells: the ease only walks this box. Everything that can move
    // foam (a stamp, a rescan, last tick's own changes) widens it; a shift or a full rescan gives up
    // on bounding it for that tick.
    private static boolean activeAll = true;
    private static int activeX0, activeZ0, activeX1, activeZ1;
    // Cells whose uploaded value has actually changed, so the texture upload can be a sub-rectangle
    // rather than the whole 768x768 grid every tick.
    private static boolean uploadAll = true;
    private static int uploadX0, uploadZ0, uploadX1, uploadZ1;
    // Cell boxes stamped by entities / sub-levels last tick, so only those get re-chamfered.
    private static final int MAX_STAMP_BOXES = 32;
    private static final int[] stampBoxes = new int[MAX_STAMP_BOXES * 4];
    private static int stampBoxCount;
    private static boolean stampOverflow;
    private static boolean lastStamped;
    // Cells the ease actually moved this tick; next tick's active box grows from them.
    private static int changedX0, changedZ0, changedX1, changedZ1;
    private static long lastTick = Long.MIN_VALUE;

    private WaterlineMap() {
    }

    public static int textureId() {
        return texture == null ? 0 : texture.getId();
    }

    public static int size() {
        return size;
    }

    /** Pixels per block the plane's foam and spots snap to: finer than the map's own cells. */
    public static int effectPixelsPerBlock() {
        return cellsPerBlock * EFFECT_DETAIL;
    }

    // Current sub-block grid resolution (cells per block); read by the plane/vapour shaders' pixel-snap
    // uniforms so their pixelation always matches the map's actual resolution (see Config.WATERLINE_CELLS_PER_BLOCK).
    /**
     * A block changed at {@code blockY}; rescan if that is the row the surface passes through. Called
     * from the client's own block-change path (see LevelRendererMixin), which is the only notice the
     * client gets -- there is no event for it.
     */
    public static void markDirtyAt(int blockX, int blockY, int blockZ) {
        if (Math.abs(blockY - lastBoundaryY) <= 1) {
            markDirtyColumns(blockX, blockZ, blockX, blockZ);
        }
    }

    /**
     * A whole chunk section was marked for rebuild -- a chunk loading, or a large edit. Rescan if that
     * section spans the surface's row.
     */
    public static void markSectionDirty(int sectionX, int sectionY, int sectionZ) {
        int lo = (sectionY << 4) - 1;
        int hi = lo + 17;
        if (lastBoundaryY >= lo && lastBoundaryY <= hi) {
            markDirtyColumns(sectionX << 4, sectionZ << 4, (sectionX << 4) + 15, (sectionZ << 4) + 15);
        }
    }

    // Remember a world-space column range to re-read. Merged into an existing box when it already
    // covers it, so a chunk reporting section after section does not fill the list.
    private static void markDirtyColumns(int x0, int z0, int x1, int z1) {
        synchronized (DIRTY_LOCK) {
            if (dirtyAll) {
                return;
            }
            for (int i = 0; i < dirtyCount; i++) {
                int b = i * 4;
                if (x0 >= dirtyBoxes[b] && z0 >= dirtyBoxes[b + 1]
                        && x1 <= dirtyBoxes[b + 2] && z1 <= dirtyBoxes[b + 3]) {
                    return; // already covered
                }
            }
            if (dirtyCount == MAX_DIRTY_BOXES) {
                dirtyAll = true; // more edits at once than it is worth tracking: read the lot
                dirtyCount = 0;
                return;
            }
            int b = dirtyCount * 4;
            dirtyBoxes[b] = x0;
            dirtyBoxes[b + 1] = z0;
            dirtyBoxes[b + 2] = x1;
            dirtyBoxes[b + 3] = z1;
            dirtyCount++;
        }
    }

    public static int cellsPerBlock() {
        return cellsPerBlock;
    }

    public static float originX() {
        return originX;
    }

    public static float originZ() {
        return originZ;
    }

    /** Changes only when {@link #openBlocks} may have; see FogPlaneMesh. */
    public static int revision() {
        return revision;
    }

    /** True where the surface is free to exist: not inside a solid block, not inside a liquid. */
    public static boolean openAt(int blockX, int blockZ) {
        if (blockX < 0 || blockX >= size || blockZ < 0 || blockZ >= size) {
            return true; // outside the map we have no data; assume open (see FogPlaneMesh's skirt)
        }
        return openBlocks[blockZ * size + blockX];
    }

    // Driven from the renderer every frame. Recomputes the target field on move / every few ticks,
    // eases the shown field toward it once per tick, and sprinkles foam particles.
    public static void update(Level level, Vec3 camPos, int boundaryY, int mapBlocks) {
        int want = Mth.clamp(mapBlocks, MIN_SIZE, MAX_SIZE);
        // Configured resolution, capped so a wide map cannot blow the cell budget (see MAX_CELLS).
        int wantC = Mth.clamp(MAX_CELLS / want, 1, Mth.clamp(Config.WATERLINE_CELLS_PER_BLOCK.getAsInt(), 1, 4));
        if (texture == null || want != size || wantC != cellsPerBlock) {
            rebuild(want, wantC);
        }

        // Where the map would sit if it were centred on the camera exactly, and how far it is from
        // where it actually sits. It is only moved once that drift is worth the work (RECENTRE_STEP).
        int wantX = Mth.floor(camPos.x) - size / 2;
        int wantZ = Mth.floor(camPos.z) - size / 2;
        long tick = level.getGameTime();
        boolean originMoved = !originValid
                || Math.abs(wantX - originX) >= RECENTRE_STEP
                || Math.abs(wantZ - originZ) >= RECENTRE_STEP;
        int cx = originMoved ? wantX : originX;
        int cz = originMoved ? wantZ : originZ;

        // Keep the fields world-anchored: shift their contents by the offset the map just moved. The
        // distance fields (target/targetP) are shifted too, so walking never rescans ground the map has
        // already read -- only the strip it has just scrolled onto (see reseedExposed below).
        int movedX = cx - originX;
        int movedZ = cz - originZ;
        if (originMoved) {
            int dx = movedX * cellsPerBlock;
            int dz = movedZ * cellsPerBlock;
            shiftField(shown, dx, dz);
            shiftField(shownP, dx, dz);
            shiftField(target, dx, dz);
            shiftField(blockDist, dx, dz);
            shiftField(targetP, dx, dz);
            // Newly exposed cells default to "no liquid": the murk draws there until the next rescan,
            // which is the right way round -- a missing cut-out is far less visible than a phantom one.
            shiftField(fluidMask, dx, dz, 0.0F);
            shiftOpen(movedX, movedZ);
            // The active box travels with the contents it describes, so foam still easing somewhere
            // else on the map is not left frozen by a step of the camera.
            if (!activeAll && activeX0 <= activeX1) {
                activeX0 -= dx; activeX1 -= dx;
                activeZ0 -= dz; activeZ1 -= dz;
            }
            originX = cx;
            originZ = cz;
            originValid = true;
            revision++;
        }

        boolean newTick = tick != lastTick;
        boolean recomputed = false;

        // Take whatever the block hooks have reported since the last call.
        boolean rescanAll;
        int pending;
        synchronized (DIRTY_LOCK) {
            rescanAll = dirtyAll;
            pending = dirtyCount;
            System.arraycopy(dirtyBoxes, 0, dirtyDrained, 0, pending * 4);
            dirtyAll = false;
            dirtyCount = 0;
        }

        // Reseed + chamfer synchronously on the same call, whenever the block picture can have changed:
        // the boundary moved to another row, a block changed at that row (see markDirtyAt), or the map
        // jumped clear of itself. The two must happen back-to-back on fully fresh
        // block-state data. (A tick-staggered reseed was tried here and reverted -- easing every tick
        // against a target[] whose stripes hold raw, not-yet-chamfered values for several ticks between
        // chamfer passes made foam near real edges visibly recede then snap back as each stripe cycled.)
        boolean readWholeMap = false;
        // A move of the whole map's width has nothing left to shift, so it is a rescan like any other.
        boolean scrolledClear = Math.abs(movedX) >= size || Math.abs(movedZ) >= size;
        int fallback = MixinHealthCheck.blockChangeFired ? FALLBACK_INTERVAL_HOOKED : FALLBACK_INTERVAL;
        if (boundaryY != lastBoundaryY || rescanAll || scrolledClear
                || tick - lastRecomputeTick >= fallback) {
            lastBoundaryY = boundaryY;
            lastRecomputeTick = tick;
            reseedAll(level, boundaryY);
            finishRecompute();
            markUploadAll();
            recomputed = true;
            readWholeMap = true;
            revision++;
        } else if (originMoved) {
            // Walking only ever exposes a strip at the leading edge; everything else was shifted above
            // and is still the same ground it was read from. Reading just that strip is what lets the
            // map grow with the render distance -- a full rescan on every block walked is bounded by
            // size^2 block lookups, which is the one cost here that a wider map cannot amortise.
            reseedExposed(level, boundaryY, movedX, movedZ);
            markUploadAll(); // the shift moved every cell, so the whole texture is stale anyway
            recomputed = true;
            revision++;
        }

        // Localized rescans: the columns a block edit or a loading chunk section has just changed.
        // Skipped when the whole map was just read anyway.
        if (!readWholeMap) {
            for (int i = 0; i < pending; i++) {
                int b = i * 4;
                if (reseedRegion(level, boundaryY, dirtyDrained[b], dirtyDrained[b + 1],
                        dirtyDrained[b + 2], dirtyDrained[b + 3])) {
                    recomputed = true;
                    revision++;
                }
            }
        }

        // Ease + re-upload at most once per tick (bounded cost), or immediately after a re-centre.
        if (newTick || originMoved) {
            // Everything that moves goes on fresh every tick, so the ring follows it rather than
            // catching up on the rescan cadence.
            boolean stamped = restampDynamic(level, boundaryY);
            long elapsed = lastTick == Long.MIN_VALUE ? 1 : Math.max(1, tick - lastTick);
            float step = EASE_CELLS_PER_TICK * elapsed;
            // Standing still in ground the map has already settled on, there is nothing for the ease
            // to do -- and it is two passes over every cell in the map, every tick. So it only runs
            // while something can still be moving: a stamp, a rescan, or foam still catching up.
            boolean changed = false;
            if (!foamSettled || stamped || recomputed || originMoved) {
                int ex0 = activeAll ? 0 : activeX0;
                int ez0 = activeAll ? 0 : activeZ0;
                int ex1 = activeAll ? cells - 1 : activeX1;
                int ez1 = activeAll ? cells - 1 : activeZ1;
                changedX0 = Integer.MAX_VALUE;
                changedZ0 = Integer.MAX_VALUE;
                changedX1 = Integer.MIN_VALUE;
                changedZ1 = Integer.MIN_VALUE;
                changed = ease(target, shown, step, ex0, ez0, ex1, ez1);
                changed |= ease(targetP, shownP, step, ex0, ez0, ex1, ez1);
                foamSettled = !changed;
                // Next tick only has to look where it moved, plus the cell the front can creep into.
                clearActive();
                if (changed) {
                    markActive(changedX0 - 1, changedZ0 - 1, changedX1 + 1, changedZ1 + 1);
                }
            }
            lastTick = tick;
            // Cheap when nothing is marked: it returns without touching the image or the texture.
            upload();
            if (newTick) {
                emitFoamParticles(level, camPos, boundaryY);
            }
        }
    }

    // Reseed the whole block-state field. Solids and flowing water (currents, falls) seed the full
    // field; plants the plant field. Still water (source) and air are left as open surface so foam has
    // somewhere to fade into.
    private static void reseedAll(Level level, int boundaryY) {
        foamSettled = false;
        markActiveAll();
        markUploadAll();
        java.util.Arrays.fill(target, INF);
        java.util.Arrays.fill(targetP, INF);
        java.util.Arrays.fill(fluidMask, 0.0F);
        java.util.Arrays.fill(openBlocks, true);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bz = 0; bz < size; bz++) {
            for (int bx = 0; bx < size; bx++) {
                reseedColumn(level, pos, boundaryY, bx, bz);
            }
        }
    }

    /**
     * Reseed only the columns a move of {@code (movedX, movedZ)} blocks has newly brought into the map.
     * The fields were shifted before this, so every other column already holds the right values and the
     * exposed ones hold the "nothing near / no liquid / open" defaults {@link #shiftField} fills in --
     * exactly what {@link #reseedColumn} expects to stamp onto.
     */
    private static void reseedExposed(Level level, int boundaryY, int movedX, int movedZ) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        final int reach = Mth.ceil(MAX_DIST) * cellsPerBlock; // cells a seed can still matter over
        // Content moved by -moved, so the strip that came in sits at the high edge for a positive move
        // and at the low edge for a negative one.
        if (movedX != 0) {
            int x0 = movedX > 0 ? size - movedX : 0;
            int x1 = movedX > 0 ? size - 1 : -movedX - 1;
            for (int bz = 0; bz < size; bz++) {
                for (int bx = x0; bx <= x1; bx++) {
                    reseedColumn(level, pos, boundaryY, bx, bz);
                }
            }
            recomputeStrip(x0 * cellsPerBlock - reach, -reach,
                    (x1 + 1) * cellsPerBlock - 1 + reach, cells - 1 + reach);
        }
        if (movedZ != 0) {
            int z0 = movedZ > 0 ? size - movedZ : 0;
            int z1 = movedZ > 0 ? size - 1 : -movedZ - 1;
            for (int bz = z0; bz <= z1; bz++) {
                // The corner where the two strips meet is read twice; reseeding a column is idempotent
                // and the overlap is |movedX| * |movedZ| columns, so it is not worth a branch per cell.
                for (int bx = 0; bx < size; bx++) {
                    reseedColumn(level, pos, boundaryY, bx, bz);
                }
            }
            recomputeStrip(-reach, z0 * cellsPerBlock - reach,
                    cells - 1 + reach, (z1 + 1) * cellsPerBlock - 1 + reach);
        }
    }

    // Chamfer one freshly seeded box back into shape and keep blockDist -- the block-only field the
    // per-tick dynamic stamps are rebuilt from -- in step with it over the same box.
    private static void recomputeStrip(int x0, int z0, int x1, int z1) {
        foamSettled = false; // fresh distances here: the ease has to look again, whenever it next runs
        chamferRegion(target, x0, z0, x1, z1);
        chamferRegion(targetP, x0, z0, x1, z1);
        copyRegion(target, blockDist, x0, z0, x1, z1);
        markActive(x0, z0, x1, z1);
    }

    /**
     * Re-read the columns of one world-space box -- a block edit, a chunk section arriving -- and
     * nothing else. Returns whether anything on the map was actually covered by it.
     *
     * <p>The box is widened by {@link #MAX_DIST} before it is re-read, because a seed that has just
     * been REMOVED left its distance written into cells that far away, and a chamfer pass can only
     * ever lower a value, never raise it back. Re-seeding that wider box from scratch is what lets
     * the transform recover, and it is chamfered wider still so the recovered cells can pick their
     * distance back up from whatever seeds lie outside.
     */
    private static boolean reseedRegion(Level level, int boundaryY, int wx0, int wz0, int wx1, int wz1) {
        final int reachBlocks = Mth.ceil(MAX_DIST);
        int bx0 = Math.max(0, wx0 - originX - reachBlocks);
        int bz0 = Math.max(0, wz0 - originZ - reachBlocks);
        int bx1 = Math.min(size - 1, wx1 - originX + reachBlocks);
        int bz1 = Math.min(size - 1, wz1 - originZ + reachBlocks);
        if (bx0 > bx1 || bz0 > bz1) {
            return false; // the edit was outside the map entirely
        }
        final int C = cellsPerBlock;
        int cx0 = bx0 * C;
        int cz0 = bz0 * C;
        int cx1 = (bx1 + 1) * C - 1;
        int cz1 = (bz1 + 1) * C - 1;
        fillCells(target, cx0, cz0, cx1, cz1, INF);
        fillCells(targetP, cx0, cz0, cx1, cz1, INF);
        fillCells(fluidMask, cx0, cz0, cx1, cz1, 0.0F);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bz = bz0; bz <= bz1; bz++) {
            for (int bx = bx0; bx <= bx1; bx++) {
                reseedColumn(level, pos, boundaryY, bx, bz);
            }
        }
        int reach = reachBlocks * C;
        recomputeStrip(cx0 - reach, cz0 - reach, cx1 + reach, cz1 + reach);
        markUpload(Math.max(0, cx0 - reach), Math.max(0, cz0 - reach),
                Math.min(cells - 1, cx1 + reach), Math.min(cells - 1, cz1 + reach));
        return true;
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

        openBlocks[bz * size + bx] = !columnClosed(level, pos, state);
        boolean flowing = !fluid.isEmpty() && !fluid.isSource();
        boolean plant = !solid && fluid.isEmpty() && !state.isAir();
        if (!fluid.isEmpty()) {
            // Any liquid, not just water: the surface is cut out of it entirely (see the shader).
            fillCells(fluidMask, x0, z0, x1, z1, 1.0F);
        }
        if (solid || flowing) {
            fillCells(target, x0, z0, x1, z1, 0.0F);
        } else if (plant) {
            fillCells(targetP, x0, z0, x1, z1, 0.0F);
        }
    }

    /**
     * Whether the surface is blocked at this column: any liquid closes it outright, and a solid one
     * only if its collision shape actually reaches the surface's height within the block -- the
     * surface sits {@link #SURFACE_FRACTION} up from the block's floor, so a slab or a carpet leaves
     * it in open air. An empty shape on a motion-blocking block falls back to closed.
     *
     * <p>The single definition of "there is no murk here", shared by the mesh's openness mask
     * (FogPlaneMesh) and the foam-site scan (FoamSites), which must agree or foam appears at a
     * waterline with no surface under it.
     */
    static boolean columnClosed(BlockGetter level, BlockPos pos, BlockState state) {
        var fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            return true;
        }
        if (!state.blocksMotion()) {
            return false;
        }
        // Fast path first. This runs for every column of the map on every rescan -- 36,864 of them at
        // the largest size -- and getCollisionShape builds and caches a VoxelShape, where
        // isCollisionShapeFullBlock is a flag already sitting in the block state's cache. Nearly every
        // closed column is an ordinary full block, so the expensive call is left for the few that are
        // not: slabs, carpets, stairs, and anything else the surface might still pass over.
        if (state.isCollisionShapeFullBlock(level, pos)) {
            return true;
        }
        var shape = state.getCollisionShape(level, pos);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) > SURFACE_FRACTION;
    }

    // The block half of the field: chamfer what reseedAll seeded and keep it, so the per-tick pass
    // below has something to stamp onto without rescanning every block again. Plants have no moving
    // part, so their field is finished here and left alone until the next rescan.
    private static void finishRecompute() {
        chamferDistance(target);
        System.arraycopy(target, 0, blockDist, 0, target.length);
        chamferDistance(targetP);
    }

    /**
     * Stamp everything that MOVES onto a copy of the block field, every tick.
     *
     * <p>This used to happen inside the rescan, so a boat's foam ring only caught up every
     * RECOMPUTE_INTERVAL ticks and visibly stepped along behind it. Splitting it out costs an
     * arraycopy a tick, and a chamfer only on the ticks where something is actually near the boundary
     * -- with nothing crossing, the copy alone is already the right answer.
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

        // Restoring the block-only field is what un-stamps whatever moved on: it is exact, so nothing
        // has to be un-chamfered afterwards, and only the NEW seeds need relaxing back in.
        System.arraycopy(blockDist, 0, target, 0, target.length);
        // Last tick's stamps have just been erased by that copy, so their cells changed too: they have
        // to be eased and uploaded even if nothing stands there any more.
        int reach = Mth.ceil(MAX_DIST) * C;
        if (stampOverflow) {
            markActiveAll();
            markUploadAll();
        } else {
            for (int i = 0; i < stampBoxCount; i++) {
                int b = i * 4;
                markActive(stampBoxes[b] - reach, stampBoxes[b + 1] - reach,
                        stampBoxes[b + 2] + reach, stampBoxes[b + 3] + reach);
                markUpload(Math.max(0, stampBoxes[b] - reach), Math.max(0, stampBoxes[b + 1] - reach),
                        Math.min(cells - 1, stampBoxes[b + 2] + reach),
                        Math.min(cells - 1, stampBoxes[b + 3] + reach));
            }
        }
        stampBoxCount = 0;
        stampOverflow = false;

        // Entities whose bounding box straddles the boundary seed the full field (foam rings them).
        for (Entity e : crossing) {
            AABB b = e.getBoundingBox();
            int x0 = Mth.floor((b.minX - originX) * C);
            int x1 = Mth.floor((b.maxX - originX) * C);
            int z0 = Mth.floor((b.minZ - originZ) * C);
            int z1 = Mth.floor((b.maxZ - originZ) * C);
            fillCells(target, x0, z0, x1, z1, 0.0F);
            addStampBox(x0, z0, x1, z1);
        }

        // Sable sub-levels (ships / contraptions) that cross the boundary also seed the full field.
        // Their stamped cells are scattered over the hull's footprint, so one box covers the lot.
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
        // its effect -- where the old code re-ran the transform over every cell of the map every tick.
        if (stampOverflow) {
            chamferDistance(target);
            markActiveAll();
            markUploadAll();
        } else {
            for (int i = 0; i < stampBoxCount; i++) {
                int b = i * 4;
                int x0 = stampBoxes[b] - reach;
                int z0 = stampBoxes[b + 1] - reach;
                int x1 = stampBoxes[b + 2] + reach;
                int z1 = stampBoxes[b + 3] + reach;
                chamferRegion(target, x0, z0, x1, z1);
                markActive(x0, z0, x1, z1);
                markUpload(Math.max(0, x0), Math.max(0, z0),
                        Math.min(cells - 1, x1), Math.min(cells - 1, z1));
            }
        }
        lastStamped = stamped;
        return stamped;
    }

    // Remember one stamped box, or give up on tracking them and fall back to the whole field.
    private static void addStampBox(int x0, int z0, int x1, int z1) {
        if (stampOverflow) {
            return;
        }
        if (stampBoxCount == MAX_STAMP_BOXES) {
            stampOverflow = true;
            return;
        }
        int b = stampBoxCount * 4;
        stampBoxes[b] = x0;
        stampBoxes[b + 1] = z0;
        stampBoxes[b + 2] = x1;
        stampBoxes[b + 3] = z1;
        stampBoxCount++;
    }

    private static void markActiveAll() {
        activeAll = true;
    }

    private static void markActive(int x0, int z0, int x1, int z1) {
        if (activeAll) {
            return;
        }
        if (activeX0 > activeX1) {
            activeX0 = x0; activeZ0 = z0; activeX1 = x1; activeZ1 = z1;
            return;
        }
        activeX0 = Math.min(activeX0, x0);
        activeZ0 = Math.min(activeZ0, z0);
        activeX1 = Math.max(activeX1, x1);
        activeZ1 = Math.max(activeZ1, z1);
    }

    private static void clearActive() {
        activeAll = false;
        activeX0 = Integer.MAX_VALUE;
        activeZ0 = Integer.MAX_VALUE;
        activeX1 = Integer.MIN_VALUE;
        activeZ1 = Integer.MIN_VALUE;
    }

    private static void markUploadAll() {
        uploadAll = true;
    }

    private static void markUpload(int x0, int z0, int x1, int z1) {
        if (uploadAll) {
            return;
        }
        if (uploadX0 > uploadX1) {
            uploadX0 = x0; uploadZ0 = z0; uploadX1 = x1; uploadZ1 = z1;
            return;
        }
        uploadX0 = Math.min(uploadX0, x0);
        uploadZ0 = Math.min(uploadZ0, z0);
        uploadX1 = Math.max(uploadX1, x1);
        uploadZ1 = Math.max(uploadZ1, z1);
    }

    private static void copyRegion(float[] from, float[] into, int x0, int z0, int x1, int z1) {
        x0 = Math.max(0, x0);
        z0 = Math.max(0, z0);
        x1 = Math.min(cells - 1, x1);
        z1 = Math.min(cells - 1, z1);
        for (int z = z0; z <= z1; z++) {
            System.arraycopy(from, z * cells + x0, into, z * cells + x0, x1 - x0 + 1);
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

    // Two-pass chamfer distance transform: cheap O(n) approximate Euclidean distance (in cells).
    private static void chamferDistance(float[] field) {
        chamferRegion(field, 0, 0, cells - 1, cells - 1);
    }

    /**
     * The same transform over one box of the field.
     *
     * <p>Valid because it is a min-relaxation and the cells around the box already hold correct
     * distances: they are read as sources, so any real path that leaves the box is accounted for by
     * the value it re-enters through. The caller is what has to be careful -- a box has to be wide
     * enough to cover everything the seeds it contains can reach, which is {@link #MAX_DIST} blocks,
     * since anything further is clamped away before it is ever drawn.
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

    // Ease shown[] toward target[] so foam grows / fades gradually. Growth is a wavefront: land cells
    // seed foam at once, then foam may only advance stepCells past an already-foamed neighbour each
    // tick -- so it always sweeps outward from the contact edge instead of appearing everywhere.
    private static boolean ease(float[] field, float[] show, float stepCells,
                                int bx0, int bz0, int bx1, int bz1) {
        final float maxCells = MAX_DIST * cellsPerBlock;
        final int x0 = Math.max(0, bx0);
        final int z0 = Math.max(0, bz0);
        final int x1 = Math.min(cells - 1, bx1);
        final int z1 = Math.min(cells - 1, bz1);
        if (x0 > x1 || z0 > z1) {
            return false;
        }
        boolean changed = false;

        // Seed the contact and fade retreating foam back toward "far water".
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int i = z * cells + x;
                float t = Math.min(field[i], maxCells);
                float s = show[i];
                if (t <= 0.001F) {
                    if (s != 0.0F) { // land cell: foam source, snap in immediately
                        show[i] = 0.0F;
                        changed = true;
                        markChanged(x, z);
                    }
                } else if (s < t) { // foam should retreat: rise toward target at the base rate
                    show[i] = Math.min(t, s + stepCells);
                    changed = true;
                    markChanged(x, z);
                }
            }
        }

        // Growth wavefront: a cell can only become as foamed as its nearest neighbour, plus what the
        // front is allowed to creep in a tick.
        //
        // Diagonals count, and cost DIAGONAL_STEP rather than one step -- the same weighting the
        // distance transform uses. Spreading through the four orthogonal neighbours alone is an L1
        // metric, so the front grew as a DIAMOND and then visibly reshaped into the near-circular
        // contours of the finished field once it caught up. Weighting the diagonal by its real length
        // makes the advance per unit of distance the same in every direction, so the growing edge is
        // already the shape it is going to settle into.
        final float diagonalStep = stepCells * DIAGONAL_STEP;
        final float knightStep = stepCells * KNIGHT_STEP;
        // The wavefront reads two cells past the box on every side, so the snapshot has to cover that.
        int sx0 = Math.max(0, x0 - 2);
        int sx1 = Math.min(cells - 1, x1 + 2);
        for (int z = Math.max(0, z0 - 2); z <= Math.min(cells - 1, z1 + 2); z++) {
            System.arraycopy(show, z * cells + sx0, scratch, z * cells + sx0, sx1 - sx0 + 1);
        }
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
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
                float allowed = scratch[i] + stepCells;
                if (west) allowed = Math.min(allowed, scratch[i - 1] + stepCells);
                if (east) allowed = Math.min(allowed, scratch[i + 1] + stepCells);
                if (north) allowed = Math.min(allowed, scratch[i - cells] + stepCells);
                if (south) allowed = Math.min(allowed, scratch[i + cells] + stepCells);
                if (west && north) allowed = Math.min(allowed, scratch[i - cells - 1] + diagonalStep);
                if (east && north) allowed = Math.min(allowed, scratch[i - cells + 1] + diagonalStep);
                if (west && south) allowed = Math.min(allowed, scratch[i + cells - 1] + diagonalStep);
                if (east && south) allowed = Math.min(allowed, scratch[i + cells + 1] + diagonalStep);
                // Knight moves, for the same reason the transform has them: without them the front
                // advances on an octagon and the growing ring is a different shape from the settled one.
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
                    markChanged(x, z);
                }
            }
        }
        return changed;
    }

    // A cell the ease has just moved: it has to go up in the next texture upload, and it is where the
    // foam front is, so it is also where the next tick's ease has to look.
    private static void markChanged(int x, int z) {
        markUpload(x, z, x, z);
        changedX0 = Math.min(changedX0, x);
        changedZ0 = Math.min(changedZ0, z);
        changedX1 = Math.max(changedX1, x);
        changedZ1 = Math.max(changedZ1, z);
    }

    // Translate a shown field by (dx, dz) cells when the map re-centres; exposed cells become far water.
    private static void shiftField(float[] show, int dx, int dz) {
        shiftField(show, dx, dz, MAX_DIST * cellsPerBlock);
    }

    private static void shiftField(float[] show, int dx, int dz, float exposedFill) {
        final float maxCells = exposedFill;
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

    // Same world-anchoring shift the float fields get, at block resolution. Newly exposed columns
    // default to OPEN: the murk covering ground it should not for a few ticks is far less visible
    // than a hole where the surface should be.
    private static boolean[] openScratch = new boolean[0];

    private static void shiftOpen(int dx, int dz) {
        if (openScratch.length != openBlocks.length) {
            openScratch = new boolean[openBlocks.length];
        }
        for (int z = 0; z < size; z++) {
            int sz = z + dz;
            for (int x = 0; x < size; x++) {
                int sx = x + dx;
                openScratch[z * size + x] = sx < 0 || sx >= size || sz < 0 || sz >= size
                        || openBlocks[sz * size + sx];
            }
        }
        System.arraycopy(openScratch, 0, openBlocks, 0, openBlocks.length);
    }

    // Write and send only the part of the grid that has actually changed. The whole grid is 768x768
    // at its largest, and writing every pixel of it each tick cost more than everything else here put
    // together; a walking player or a boat moves a few hundred cells.
    private static void upload() {
        final float maxCells = MAX_DIST * cellsPerBlock;
        int x0 = uploadAll ? 0 : Math.max(0, uploadX0);
        int z0 = uploadAll ? 0 : Math.max(0, uploadZ0);
        int x1 = uploadAll ? cells - 1 : Math.min(cells - 1, uploadX1);
        int z1 = uploadAll ? cells - 1 : Math.min(cells - 1, uploadZ1);
        boolean full = uploadAll;
        uploadAll = false;
        uploadX0 = Integer.MAX_VALUE;
        uploadZ0 = Integer.MAX_VALUE;
        uploadX1 = Integer.MIN_VALUE;
        uploadZ1 = Integer.MIN_VALUE;
        if (x0 > x1 || z0 > z1) {
            return;
        }
        NativeImage img = texture.getPixels();
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                int i = z * cells + x;
                float d = Math.min(shown[i], maxCells);
                float dp = Math.min(shownP[i], maxCells);
                int v = (int) (d / maxCells * 255.0F + 0.5F) & 0xFF;
                int g = (int) (dp / maxCells * 255.0F + 0.5F) & 0xFF;
                int f = fluidMask[i] > 0.5F ? 0xFF : 0;
                // ABGR: R = solid/entity dist, G = plant dist, B = liquid cut-out mask
                img.setPixelRGBA(x, z, 0xFF000000 | (f << 16) | (g << 8) | v);
            }
        }
        if (full) {
            texture.upload();
            return;
        }
        // Sub-rectangle upload. The overload without the blur/clamp flags is deliberate: the ones that
        // take them RESET the texture's filtering and wrap mode, which are set once in rebuild() and
        // must stay LINEAR + CLAMP_TO_EDGE or the foam turns blocky and wraps at the map's edge.
        GlStateManager._bindTexture(texture.getId());
        img.upload(0, x0, z0, x0, z0, x1 - x0 + 1, z1 - z0 + 1, false, false);
    }

    // Sprinkle a few foam particles onto the ring near the camera each tick, for a bit of life.
    private static void emitFoamParticles(Level level, Vec3 camPos, int boundaryY) {
        // Match the rendered plane height exactly (boundaryY is its floored block row, not the surface).
        double surfaceY = Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
        if (Math.abs(camPos.y - surfaceY) > 32.0) {
            return; // only when the camera is near the surface
        }
        final int C = cellsPerBlock;
        float foamBandCells = (float) (double) Config.FOAM_WIDTH.get() * C;
        if (foamBandCells <= 0.0F) {
            return;
        }
        // Tinted campfire smoke (see FoamParticle): big, soft and slow-rising, which reads as foam
        // mist lying on the surface. The nozzle filter's own puffs are a separate, smaller particle.
        float[] pc = Config.foamColor();
        ParticleOptions foam = ColorParticleOption.create(ModParticles.FOAM.get(), pc[0], pc[1], pc[2]);
        RandomSource rnd = level.getRandom();

        // Standing foam along the waterline comes from FoamSites, which remembers where the boundary
        // touches blocks per chunk and so reaches the whole loaded world. Scattering samples over this
        // map instead capped foam at the map's own 192 blocks and made it travel with the player.
        FoamSites.tick(level, camPos, boundaryY, surfaceY, foam, rnd);
        // Spray thrown by things crossing it is per-entity and stays close, since that is where the
        // entities are.
        emitWakeFoam(level, camPos, surfaceY, foam, rnd);
    }

    // How far out foam is emitted: the map's own half-extent, capped. Beyond the map there is no
    // distance field to read, so it can never usefully exceed that.
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
                                     ParticleOptions puff, RandomSource rnd) {
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
                // The spray travels OUTWARD from where it was thrown, faster the faster the thing that
                // threw it, while also giving up the entity's own motion. Outward carries the wave off
                // across the surface; losing the motion means the entity leaves it behind rather than
                // dragging it along -- the two together open into a V behind anything moving quickly.
                double push = WAKE_SPREAD * (0.4 + strength);
                // Spread the tick's spray along the ground the entity actually covered, instead of
                // dropping all of it where the entity happens to be now. Emission runs once a tick, so
                // at speed that left the wake in clumps a whole tick's travel apart -- the faster the
                // thing, the wider the gaps. Stratified (i + random, not just random) so the samples
                // spread evenly over the interval rather than bunching by luck.
                double t = (i + rnd.nextDouble()) / count;
                double lagX = motion.x * (1.0 - t);
                double lagZ = motion.z * (1.0 - t);
                spawnWake(level, puff, cx - lagX + ox * rr, surfaceY, cz - lagZ + oz * rr,
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
                if (strength <= 0.0 && rnd.nextFloat() >= STATIC_CHANCE) {
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
                spawnWake(level, puff, wx - motion.x * (1.0 - t), surfaceY, wz - motion.z * (1.0 - t),
                        Math.cos(angle), Math.sin(angle), WAKE_SPREAD * (0.4 + strength), motion);
            });
        }
    }

    // One piece of spray: it travels OUTWARD from where it was thrown, faster the faster the thing
    // that threw it, while giving up that thing's own motion. Outward carries the wave off across the
    // surface; losing the motion means whatever threw it leaves it behind rather than dragging it
    // along -- the two together open into a V behind anything moving quickly.
    private static void spawnWake(Level level, ParticleOptions puff, double x, double surfaceY, double z,
                                  double outX, double outZ, double push, Vec3 motion) {
        level.addParticle(puff, x, surfaceY + 0.05, z,
                outX * push - motion.x * WAKE_DRAG, 0.0, outZ * push - motion.z * WAKE_DRAG);
    }

    // (Re)allocate the backing arrays and GPU texture for a new block edge length and/or cell resolution.
    private static void rebuild(int newSize, int newCellsPerBlock) {
        originValid = false; // a resized map has to be placed again before anything is read into it
        size = newSize;
        cellsPerBlock = newCellsPerBlock;
        final float maxCells = MAX_DIST * cellsPerBlock;
        cells = size * cellsPerBlock;
        target = new float[cells * cells];
        blockDist = new float[cells * cells];
        shown = new float[cells * cells];
        targetP = new float[cells * cells];
        shownP = new float[cells * cells];
        fluidMask = new float[cells * cells];
        openBlocks = new boolean[size * size];
        java.util.Arrays.fill(openBlocks, true);
        revision++;
        scratch = new float[cells * cells];
        java.util.Arrays.fill(target, maxCells);
        java.util.Arrays.fill(blockDist, maxCells);
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
