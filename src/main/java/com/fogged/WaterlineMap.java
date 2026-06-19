package com.fogged;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A world-aligned "distance to the nearest waterline" map, centred on the camera. The grid is stored
// at CELLS_PER_BLOCK cells per block (sub-block resolution) so the foam contour is crisp on the
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
    public static final int CELLS_PER_BLOCK = 4;     // sub-block grid resolution (crisp foam contour)
    private static final int RECOMPUTE_INTERVAL = 5;  // ticks between full target rebuilds (also on move)
    private static final float EASE_CELLS_PER_TICK = 0.6F; // how fast shown[] chases target[] (foam ramp)
    private static final int MIN_SIZE = 48;           // clamp the simulation-distance-driven block edge
    private static final int MAX_SIZE = 192;
    private static final float INF = 1.0e9F;

    // Whether the Sable physics mod is present, so its sub-levels can also generate foam.
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    // Foam particles sprinkled on the ring each tick, within this radius of the camera.
    private static final int PARTICLES_PER_TICK = 4;
    private static final int PARTICLE_RADIUS_BLOCKS = 14;

    private static DynamicTexture texture;
    private static int size = 0;          // current block edge length of the map
    private static int cells = 0;         // texel/cell edge length = size * CELLS_PER_BLOCK
    private static float[] target = new float[0];
    private static float[] shown = new float[0];
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
        if (texture == null || want != size) {
            rebuild(want);
        }

        int cx = Mth.floor(camPos.x) - size / 2;
        int cz = Mth.floor(camPos.z) - size / 2;
        long tick = level.getGameTime();
        boolean originMoved = cx != originX || cz != originZ;

        // Keep shown[] world-anchored: shift its contents by the same offset the map just moved.
        if (originMoved) {
            shiftShown((cx - originX) * CELLS_PER_BLOCK, (cz - originZ) * CELLS_PER_BLOCK);
            originX = cx;
            originZ = cz;
        }

        if (originMoved || boundaryY != lastBoundaryY || tick - lastRecomputeTick >= RECOMPUTE_INTERVAL) {
            lastBoundaryY = boundaryY;
            lastRecomputeTick = tick;
            recompute(level, boundaryY);
        }

        // Ease + re-upload at most once per tick (bounded cost), or immediately after a re-centre.
        boolean newTick = tick != lastTick;
        if (newTick || originMoved) {
            long elapsed = lastTick == Long.MIN_VALUE ? 1 : Math.max(1, tick - lastTick);
            boolean changed = ease(EASE_CELLS_PER_TICK * elapsed);
            lastTick = tick;
            if (changed || originMoved) {
                upload();
            }
            if (newTick) {
                emitFoamParticles(level, camPos, boundaryY);
            }
        }
    }

    private static void recompute(Level level, int boundaryY) {
        final int C = CELLS_PER_BLOCK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        java.util.Arrays.fill(target, INF);

        // Seed: each solid block at the boundary stamps its whole C x C cell block as land (dist 0).
        for (int bz = 0; bz < size; bz++) {
            for (int bx = 0; bx < size; bx++) {
                pos.set(originX + bx, boundaryY, originZ + bz);
                BlockState state = level.getBlockState(pos);
                if (state.blocksMotion()) {
                    stampCells(bx * C, bz * C, bx * C + C - 1, bz * C + C - 1);
                }
            }
        }

        // Entities whose bounding box straddles the boundary also count as land (foam rings them).
        AABB area = new AABB(originX, boundaryY - 2.0, originZ, originX + size, boundaryY + 2.0, originZ + size);
        for (Entity e : level.getEntities((Entity) null, area,
                e -> e.getBoundingBox().minY <= boundaryY + 0.5 && e.getBoundingBox().maxY >= boundaryY - 0.5)) {
            AABB b = e.getBoundingBox();
            int x0 = Mth.floor((b.minX - originX) * C);
            int x1 = Mth.floor((b.maxX - originX) * C);
            int z0 = Mth.floor((b.minZ - originZ) * C);
            int z1 = Mth.floor((b.maxZ - originZ) * C);
            stampCells(x0, z0, x1, z1);
        }

        // Sable sub-levels (ships / contraptions) that cross the boundary also generate foam.
        if (SABLE && Config.SABLE_FOAM.getAsBoolean()) {
            SableFoam.stampSubLevels(level, boundaryY, originX, originZ, size, C,
                    (cellX, cellZ) -> target[cellZ * cells + cellX] = 0.0F);
        }

        chamferDistance();
    }

    private static void stampCells(int x0, int z0, int x1, int z1) {
        x0 = Math.max(0, x0);
        z0 = Math.max(0, z0);
        x1 = Math.min(cells - 1, x1);
        z1 = Math.min(cells - 1, z1);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                target[z * cells + x] = 0.0F;
            }
        }
    }

    // Two-pass chamfer distance transform: cheap O(n) approximate Euclidean distance (in cells).
    private static void chamferDistance() {
        final float d1 = 1.0F;
        final float d2 = 1.41421356F;
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int i = z * cells + x;
                float d = target[i];
                if (x > 0) d = Math.min(d, target[i - 1] + d1);
                if (z > 0) d = Math.min(d, target[i - cells] + d1);
                if (x > 0 && z > 0) d = Math.min(d, target[i - cells - 1] + d2);
                if (x < cells - 1 && z > 0) d = Math.min(d, target[i - cells + 1] + d2);
                target[i] = d;
            }
        }
        for (int z = cells - 1; z >= 0; z--) {
            for (int x = cells - 1; x >= 0; x--) {
                int i = z * cells + x;
                float d = target[i];
                if (x < cells - 1) d = Math.min(d, target[i + 1] + d1);
                if (z < cells - 1) d = Math.min(d, target[i + cells] + d1);
                if (x < cells - 1 && z < cells - 1) d = Math.min(d, target[i + cells + 1] + d2);
                if (x > 0 && z < cells - 1) d = Math.min(d, target[i + cells - 1] + d2);
                target[i] = d;
            }
        }
    }

    // Ease shown[] toward target[] so foam grows / fades gradually. Growth is a wavefront: land cells
    // seed foam at once, then foam may only advance stepCells past an already-foamed neighbour each
    // tick -- so it always sweeps outward from the contact edge instead of appearing everywhere.
    private static boolean ease(float stepCells) {
        final float maxCells = MAX_DIST * CELLS_PER_BLOCK;
        boolean changed = false;

        // Seed the contact and fade retreating foam back toward "far water".
        for (int i = 0; i < shown.length; i++) {
            float t = Math.min(target[i], maxCells);
            float s = shown[i];
            if (t <= 0.001F) {
                if (s != 0.0F) { // land cell: foam source, snap in immediately
                    shown[i] = 0.0F;
                    changed = true;
                }
            } else if (s < t) { // foam should retreat: rise toward target at the base rate
                shown[i] = Math.min(t, s + stepCells);
                changed = true;
            }
        }

        // Growth wavefront: a cell can only become as foamed as its nearest neighbour plus one step.
        System.arraycopy(shown, 0, scratch, 0, shown.length);
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                int i = z * cells + x;
                float t = Math.min(target[i], maxCells);
                if (shown[i] <= t) {
                    continue; // already grown to target
                }
                float m = scratch[i];
                if (x > 0) m = Math.min(m, scratch[i - 1]);
                if (x < cells - 1) m = Math.min(m, scratch[i + 1]);
                if (z > 0) m = Math.min(m, scratch[i - cells]);
                if (z < cells - 1) m = Math.min(m, scratch[i + cells]);
                float allowed = m + stepCells;               // front creeps stepCells per tick
                float s = Math.max(t, Math.min(shown[i], allowed));
                if (s != shown[i]) {
                    shown[i] = s;
                    changed = true;
                }
            }
        }
        return changed;
    }

    // Translate shown[] by (dx, dz) cells when the map re-centres; exposed cells become "far water".
    private static void shiftShown(int dx, int dz) {
        final float maxCells = MAX_DIST * CELLS_PER_BLOCK;
        for (int z = 0; z < cells; z++) {
            int sz = z + dz;
            for (int x = 0; x < cells; x++) {
                int sx = x + dx;
                scratch[z * cells + x] = (sx >= 0 && sx < cells && sz >= 0 && sz < cells)
                        ? shown[sz * cells + sx]
                        : maxCells;
            }
        }
        float[] tmp = shown;
        shown = scratch;
        scratch = tmp;
    }

    private static void upload() {
        final float maxCells = MAX_DIST * CELLS_PER_BLOCK;
        NativeImage img = texture.getPixels();
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                float d = Math.min(shown[z * cells + x], maxCells);
                int v = (int) (d / maxCells * 255.0F + 0.5F) & 0xFF;
                img.setPixelRGBA(x, z, 0xFF000000 | v); // ABGR: store distance in the R byte
            }
        }
        texture.upload();
    }

    // Sprinkle a few foam particles onto the ring near the camera each tick, for a bit of life.
    private static void emitFoamParticles(Level level, Vec3 camPos, int boundaryY) {
        double surfaceY = boundaryY + Config.PLANE_SURFACE_OFFSET;
        if (Math.abs(camPos.y - surfaceY) > 32.0) {
            return; // only when the camera is near the surface
        }
        final int C = CELLS_PER_BLOCK;
        float foamBandCells = (float) (double) Config.FOAM_WIDTH.get() * C;
        if (foamBandCells <= 0.0F) {
            return;
        }
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
                level.addParticle(ParticleTypes.SPLASH, wx, surfaceY + 0.05, wz, 0.0, 0.0, 0.0);
            }
        }
    }

    // (Re)allocate the backing arrays and GPU texture for a new block edge length.
    private static void rebuild(int newSize) {
        final float maxCells = MAX_DIST * CELLS_PER_BLOCK;
        size = newSize;
        cells = size * CELLS_PER_BLOCK;
        target = new float[cells * cells];
        shown = new float[cells * cells];
        scratch = new float[cells * cells];
        java.util.Arrays.fill(target, maxCells);
        java.util.Arrays.fill(shown, maxCells); // start with no foam so it eases in
        if (texture != null) {
            texture.close();
        }
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, cells, cells, false);
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                img.setPixelRGBA(x, z, 0xFF0000FF); // start "all far water" (R = 255)
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
