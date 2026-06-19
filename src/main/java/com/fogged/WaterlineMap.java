package com.fogged;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// A world-aligned "distance to the nearest waterline" map, centred on the camera. The grid is stored
// at CELLS_PER_BLOCK cells per block (sub-block resolution) so the foam contour is crisp on the
// pixel grid instead of being one flat colour per block. Each cell's red channel stores the distance
// (normalised by MAX_DIST blocks) to the nearest solid block / boundary-crossing entity. The plane
// shader samples this to draw foam as a stable ring on the water -- no screen-space, so it never cuts
// off with view angle. Wakes-style: build the surface data on the CPU, sample it in the shader.
//
// The map's block edge length tracks the game's *simulation distance*: foam only makes sense where
// blocks and entities are actually loaded and ticking, so the foam radius follows that setting.
public final class WaterlineMap {

    public static final float MAX_DIST = 8.0F;       // distances are clamped/stored up to this many blocks
    public static final int CELLS_PER_BLOCK = 4;     // sub-block grid resolution (crisp foam contour)
    private static final int RECOMPUTE_INTERVAL = 5;  // ticks; also recomputes whenever the camera moves
    private static final int MIN_SIZE = 48;           // clamp the simulation-distance-driven block edge
    private static final int MAX_SIZE = 192;
    private static final float INF = 1.0e9F;

    private static DynamicTexture texture;
    private static int size = 0;          // current block edge length of the map
    private static int cells = 0;         // texel/cell edge length = size * CELLS_PER_BLOCK
    private static float[] dist = new float[0];
    private static int originX;           // world block X of the map's corner
    private static int originZ;           // world block Z of the map's corner
    private static int lastBoundaryY = Integer.MIN_VALUE;
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

    // Rebuild the map if the camera moved to a new centre, the simulation distance changed, or enough
    // ticks passed (for moving entities). mapBlocks is the desired block edge (driven by sim distance).
    public static void update(Level level, Vec3 camPos, int boundaryY, int mapBlocks) {
        int want = Mth.clamp(mapBlocks, MIN_SIZE, MAX_SIZE);
        if (texture == null || want != size) {
            rebuild(want);
        }
        int cx = Mth.floor(camPos.x) - size / 2;
        int cz = Mth.floor(camPos.z) - size / 2;
        long tick = level.getGameTime();
        boolean moved = cx != originX || cz != originZ || boundaryY != lastBoundaryY;
        if (!moved && tick - lastTick < RECOMPUTE_INTERVAL) {
            return;
        }
        originX = cx;
        originZ = cz;
        lastBoundaryY = boundaryY;
        lastTick = tick;
        recompute(level, boundaryY);
    }

    private static void recompute(Level level, int boundaryY) {
        final int C = CELLS_PER_BLOCK;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        java.util.Arrays.fill(dist, INF);

        // Seed: each solid block that occupies the boundary cell stamps its whole C x C cell block as
        // "land" (distance 0). Block lookups stay per-block (cheap); only the grid is finer.
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

        chamferDistance();
        upload();
    }

    private static void stampCells(int x0, int z0, int x1, int z1) {
        x0 = Math.max(0, x0);
        z0 = Math.max(0, z0);
        x1 = Math.min(cells - 1, x1);
        z1 = Math.min(cells - 1, z1);
        for (int z = z0; z <= z1; z++) {
            for (int x = x0; x <= x1; x++) {
                dist[z * cells + x] = 0.0F;
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
                float d = dist[i];
                if (x > 0) d = Math.min(d, dist[i - 1] + d1);
                if (z > 0) d = Math.min(d, dist[i - cells] + d1);
                if (x > 0 && z > 0) d = Math.min(d, dist[i - cells - 1] + d2);
                if (x < cells - 1 && z > 0) d = Math.min(d, dist[i - cells + 1] + d2);
                dist[i] = d;
            }
        }
        for (int z = cells - 1; z >= 0; z--) {
            for (int x = cells - 1; x >= 0; x--) {
                int i = z * cells + x;
                float d = dist[i];
                if (x < cells - 1) d = Math.min(d, dist[i + 1] + d1);
                if (z < cells - 1) d = Math.min(d, dist[i + cells] + d1);
                if (x < cells - 1 && z < cells - 1) d = Math.min(d, dist[i + cells + 1] + d2);
                if (x > 0 && z < cells - 1) d = Math.min(d, dist[i + cells - 1] + d2);
                dist[i] = d;
            }
        }
    }

    private static void upload() {
        // Stored distance is normalised so that stored * MAX_DIST == distance in blocks.
        final float maxCells = MAX_DIST * CELLS_PER_BLOCK;
        NativeImage img = texture.getPixels();
        for (int z = 0; z < cells; z++) {
            for (int x = 0; x < cells; x++) {
                float d = Math.min(dist[z * cells + x], maxCells);
                int v = (int) (d / maxCells * 255.0F + 0.5F) & 0xFF;
                img.setPixelRGBA(x, z, 0xFF000000 | v); // ABGR: store distance in the R byte
            }
        }
        texture.upload();
    }

    // (Re)allocate the backing array and GPU texture for a new block edge length.
    private static void rebuild(int newSize) {
        size = newSize;
        cells = size * CELLS_PER_BLOCK;
        dist = new float[cells * cells];
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

        // Force a recompute on the next update (centre/tick markers are now stale for this size).
        lastBoundaryY = Integer.MIN_VALUE;
        lastTick = Long.MIN_VALUE;
    }
}
