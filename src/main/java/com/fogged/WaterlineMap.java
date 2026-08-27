package com.fogged;

import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
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
    // The block-state reseed scan (recompute's O(size^2) part) is spread one x-stripe per tick instead
    // of bursting the whole grid at once -- same idiom as FogScour's per-tick sweep. Equal to
    // RECOMPUTE_INTERVAL so a full stripe cycle finishes exactly as the next chamfer below is due: the
    // data chamfer consumes is always as fresh as if the whole grid had been rescanned in one go, just
    // spread across the same window instead of bursted into a single tick.
    private static final int RESEED_STRIPES = RECOMPUTE_INTERVAL;
    private static final float EASE_CELLS_PER_TICK = 0.25F; // how fast shown[] chases target[] (foam ramp)
    private static final int MIN_SIZE = 48;           // clamp the simulation-distance-driven block edge
    private static final int MAX_SIZE = 192;
    private static final float INF = 1.0e9F;

    // Whether the Sable physics mod is present, so its sub-levels can also generate foam.
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    // Foam particles sprinkled on the ring each tick, within this radius of the camera.
    private static final int PARTICLES_PER_TICK = 24;
    private static final int PARTICLE_RADIUS_BLOCKS = 14;

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
            originX = cx;
            originZ = cz;
        }

        boolean newTick = tick != lastTick;
        boolean recomputed = false;

        if (boundaryY != lastBoundaryY) {
            // Rare event (the day/schedule moved the breathing boundary): reseed the whole grid right
            // now instead of waiting out a multi-tick stripe cycle, so foam doesn't lag a stale height.
            lastBoundaryY = boundaryY;
            lastRecomputeTick = tick;
            reseedAll(level, boundaryY);
            finishRecompute(level, boundaryY);
            recomputed = true;
        } else if (newTick) {
            reseedStripe(level, boundaryY, (int) (tick % RESEED_STRIPES));
            if (tick - lastRecomputeTick >= RECOMPUTE_INTERVAL) {
                lastRecomputeTick = tick;
                finishRecompute(level, boundaryY);
                recomputed = true;
            }
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

    // Reseed one x-stripe of the block-state fields this tick (the O(size^2) part of recompute, spread
    // across RESEED_STRIPES ticks -- see the field comment on RESEED_STRIPES). Solids and flowing water
    // (currents, falls) seed the full field; plants the plant field. Still water (source) and air are
    // left as open surface so foam has somewhere to fade into.
    private static void reseedStripe(Level level, int boundaryY, int phase) {
        int stripe = (size + RESEED_STRIPES - 1) / RESEED_STRIPES;
        int bx0 = phase * stripe;
        if (bx0 >= size) {
            return;
        }
        int bx1 = Math.min(size, bx0 + stripe);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int bz = 0; bz < size; bz++) {
            for (int bx = bx0; bx < bx1; bx++) {
                reseedColumn(level, pos, boundaryY, bx, bz);
            }
        }
    }

    // Reseed the whole block-state field in one pass (used only on a boundary-height change, a rare
    // one-off event -- see update()). Equivalent to running every reseedStripe() phase back to back.
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

    // Reseed one block column's cells from its current block state. Clears the column's own cells first
    // (rather than relying on a prior full-array clear), so a striped reseed only ever touches the
    // columns it is actually re-scanning this call.
    private static void reseedColumn(Level level, BlockPos.MutableBlockPos pos, int boundaryY, int bx, int bz) {
        final int C = cellsPerBlock;
        int x0 = bx * C;
        int z0 = bz * C;
        int x1 = x0 + C - 1;
        int z1 = z0 + C - 1;
        fillCells(target, x0, z0, x1, z1, INF);
        fillCells(targetP, x0, z0, x1, z1, INF);

        pos.set(originX + bx, boundaryY, originZ + bz);
        BlockState state = level.getBlockState(pos);
        var fluid = state.getFluidState();
        boolean solid = state.blocksMotion();
        boolean flowing = !fluid.isEmpty() && !fluid.isSource();
        boolean plant = !solid && fluid.isEmpty() && !state.isAir();
        if (solid || flowing) {
            fillCells(target, x0, z0, x1, z1, 0.0F);
        } else if (plant) {
            fillCells(targetP, x0, z0, x1, z1, 0.0F);
        }
    }

    // Entity + Sable sub-level stamping (bounded queries, not the O(size^2) cost reseedStripe/reseedAll
    // spread out) followed by the chamfer distance transform. Always runs immediately after a full
    // reseed cycle has completed (see update()), so it always operates on fully fresh block-state data.
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
        // Dust particle tinted with the foam colour.
        float[] pc = Config.foamColor();
        DustParticleOptions dust = new DustParticleOptions(new Vector3f(pc[0], pc[1], pc[2]), 1.0F);
        RandomSource rnd = level.getRandom();
        double camCellX = (camPos.x - originX) * C;
        double camCellZ = (camPos.z - originZ) * C;
        int r = PARTICLE_RADIUS_BLOCKS * C;
        for (int i = 0; i < PARTICLES_PER_TICK; i++) {
            int x = (int) (camCellX + rnd.nextInt(2 * r + 1) - r);
            int z = (int) (camCellZ + rnd.nextInt(2 * r + 1) - r);
            if (x < 0 || x >= cells || z < 0 || z >= cells) {
                continue;
            }
            float s = shown[z * cells + x];
            if (s > 0.2F && s <= foamBandCells) { // on the ring, not inside land or open water
                double wx = originX + (x + rnd.nextDouble()) / C;
                double wz = originZ + (z + rnd.nextDouble()) / C;
                level.addParticle(dust, wx, surfaceY + 0.05, wz, 0.0, 0.0, 0.0);
            }
        }
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
