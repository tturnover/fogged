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
// The map's block edge length tracks the game's *simulation distance*.
public final class WaterlineMap {

    public static final float MAX_DIST = 8.0F;       // distances are clamped/stored up to this many blocks
    private static final int RECOMPUTE_INTERVAL = 5;  // ticks between full target rebuilds (also on move)
    private static final float EASE_CELLS_PER_TICK = 0.25F; // how fast shown[] chases target[] (foam ramp)
    private static final int MIN_SIZE = 48;           // clamp the simulation-distance-driven block edge
    private static final int MAX_SIZE = 192;
    private static final float INF = 1.0e9F;

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
            // Newly exposed cells default to "no liquid": the murk draws there until the next rescan,
            // which is the right way round -- a missing cut-out is far less visible than a phantom one.
            shiftField(fluidMask, dx, dz, 0.0F);
            shiftOpen(cx - originX, cz - originZ);
            originX = cx;
            originZ = cz;
            revision++;
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
            finishRecompute(level, boundaryY);
            recomputed = true;
            revision++;
        }

        // Ease + re-upload at most once per tick (bounded cost), or immediately after a re-centre.
        if (newTick || originMoved) {
            long elapsed = lastTick == Long.MIN_VALUE ? 1 : Math.max(1, tick - lastTick);
            float step = EASE_CELLS_PER_TICK * elapsed;
            boolean changed = ease(target, shown, step);
            changed |= ease(targetP, shownP, step);
            lastTick = tick;
            if (changed || originMoved || recomputed) {
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
        java.util.Arrays.fill(fluidMask, 0.0F);
        java.util.Arrays.fill(openBlocks, true);
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

    // Entity + Sable sub-level stamping followed by the chamfer distance transform. Always runs
    // immediately after reseedAll, in the same call (see update()), on fully fresh block-state data.
    private static void finishRecompute(Level level, int boundaryY) {
        final int C = cellsPerBlock;

        // Entities whose bounding box straddles the boundary seed the full field (foam rings them).
        AABB area = new AABB(originX, boundaryY - 2.0, originZ, originX + size, boundaryY + 2.0, originZ + size);
        for (Entity e : level.getEntities((Entity) null, area,
                e -> e.getBoundingBox().minY <= boundaryY + 0.5 && e.getBoundingBox().maxY >= boundaryY - 0.5)) {
            AABB b = e.getBoundingBox();
            int x0 = Mth.floor((b.minX - originX) * C);
            int x1 = Mth.floor((b.maxX - originX) * C);
            int z0 = Mth.floor((b.minZ - originZ) * C);
            int z1 = Mth.floor((b.maxZ - originZ) * C);
            fillCells(target, x0, z0, x1, z1, 0.0F);
        }

        // Sable sub-levels (ships / contraptions) that cross the boundary also seed the full field.
        if (SABLE && Config.SABLE_FOAM.getAsBoolean()) {
            SableCompatibility.stampSubLevels(level, boundaryY, originX, originZ, size, C,
                    (cellX, cellZ) -> target[cellZ * cells + cellX] = 0.0F);
        }

        chamferDistance(target);
        chamferDistance(targetP);
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
        final float d1 = 1.0F;
        final float d2 = 1.41421356F;
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int i = z * cells + x;
                float d = field[i];
                if (x > 0) d = Math.min(d, field[i - 1] + d1);
                if (z > 0) d = Math.min(d, field[i - cells] + d1);
                if (x > 0 && z > 0) d = Math.min(d, field[i - cells - 1] + d2);
                if (x < cells - 1 && z > 0) d = Math.min(d, field[i - cells + 1] + d2);
                field[i] = d;
            }
        }
        for (int z = cells - 1; z >= 0; z--) {
            for (int x = cells - 1; x >= 0; x--) {
                int i = z * cells + x;
                float d = field[i];
                if (x < cells - 1) d = Math.min(d, field[i + 1] + d1);
                if (z < cells - 1) d = Math.min(d, field[i + cells] + d1);
                if (x < cells - 1 && z < cells - 1) d = Math.min(d, field[i + cells + 1] + d2);
                if (x > 0 && z < cells - 1) d = Math.min(d, field[i + cells - 1] + d2);
                field[i] = d;
            }
        }
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

        // Growth wavefront: a cell can only become as foamed as its nearest neighbour plus one step.
        System.arraycopy(show, 0, scratch, 0, show.length);
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int i = z * cells + x;
                float t = Math.min(field[i], maxCells);
                if (show[i] <= t) {
                    continue; // already grown to target
                }
                float m = scratch[i];
                if (x > 0) m = Math.min(m, scratch[i - 1]);
                if (x < cells - 1) m = Math.min(m, scratch[i + 1]);
                if (z > 0) m = Math.min(m, scratch[i - cells]);
                if (z < cells - 1) m = Math.min(m, scratch[i + cells]);
                float allowed = m + stepCells;               // front creeps stepCells per tick
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
                int f = fluidMask[i] > 0.5F ? 0xFF : 0;
                // ABGR: R = solid/entity dist, G = plant dist, B = liquid cut-out mask
                img.setPixelRGBA(x, z, 0xFF000000 | (f << 16) | (g << 8) | v);
            }
        }
        texture.upload();
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
                spawnWake(level, puff, cx + ox * rr, surfaceY, cz + oz * rr, ox, oz, push, motion);
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
                spawnWake(level, puff, wx, surfaceY, wz,
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
        size = newSize;
        cellsPerBlock = newCellsPerBlock;
        final float maxCells = MAX_DIST * cellsPerBlock;
        cells = size * cellsPerBlock;
        target = new float[cells * cells];
        shown = new float[cells * cells];
        targetP = new float[cells * cells];
        shownP = new float[cells * cells];
        fluidMask = new float[cells * cells];
        openBlocks = new boolean[size * size];
        java.util.Arrays.fill(openBlocks, true);
        revision++;
        scratch = new float[cells * cells];
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
