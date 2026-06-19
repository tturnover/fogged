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

// A world-aligned "distance to the nearest waterline" map, centred on the camera. Each texel covers
// one block; its red channel stores the distance (in blocks, normalised by MAX_DIST) from that cell
// to the nearest solid block or entity that crosses the breathing boundary. The plane shader samples
// this to draw foam as a stable ring on the water -- no screen-space, so it never cuts off with view
// angle. This is the Wakes-style approach: build the surface data on the CPU, sample it in the shader.
public final class WaterlineMap {

    public static final int SIZE = 96;          // texels == blocks (48-block radius around the camera)
    public static final float MAX_DIST = 8.0F;  // distances are clamped/stored up to this many blocks
    private static final int RECOMPUTE_INTERVAL = 5; // ticks; also recomputes whenever the camera moves

    private static DynamicTexture texture;
    private static int originX;       // world block X of texel column 0
    private static int originZ;       // world block Z of texel row 0
    private static int lastBoundaryY = Integer.MIN_VALUE;
    private static long lastTick = Long.MIN_VALUE;

    private static final float[] dist = new float[SIZE * SIZE];
    private static final float INF = 1.0e9F;

    private WaterlineMap() {
    }

    public static int textureId() {
        if (texture == null) {
            build();
        }
        return texture.getId();
    }

    public static float originX() {
        return originX;
    }

    public static float originZ() {
        return originZ;
    }

    // Rebuild the map if the camera moved to a new centre or enough ticks passed (for moving entities).
    public static void update(Level level, Vec3 camPos, int boundaryY) {
        if (texture == null) {
            build();
        }
        int cx = Mth.floor(camPos.x) - SIZE / 2;
        int cz = Mth.floor(camPos.z) - SIZE / 2;
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
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        // Seed: solid blocks that occupy the boundary cell are "land" (distance 0), everything else INF.
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                pos.set(originX + x, boundaryY, originZ + z);
                BlockState state = level.getBlockState(pos);
                dist[z * SIZE + x] = state.blocksMotion() ? 0.0F : INF;
            }
        }

        // Entities whose bounding box straddles the boundary also count as land (foam rings them).
        AABB area = new AABB(originX, boundaryY - 2.0, originZ, originX + SIZE, boundaryY + 2.0, originZ + SIZE);
        for (Entity e : level.getEntities((Entity) null, area,
                e -> e.getBoundingBox().minY <= boundaryY + 0.5 && e.getBoundingBox().maxY >= boundaryY - 0.5)) {
            AABB b = e.getBoundingBox();
            int x0 = Math.max(0, Mth.floor(b.minX) - originX);
            int x1 = Math.min(SIZE - 1, Mth.floor(b.maxX) - originX);
            int z0 = Math.max(0, Mth.floor(b.minZ) - originZ);
            int z1 = Math.min(SIZE - 1, Mth.floor(b.maxZ) - originZ);
            for (int z = z0; z <= z1; z++) {
                for (int x = x0; x <= x1; x++) {
                    dist[z * SIZE + x] = 0.0F;
                }
            }
        }

        chamferDistance();
        upload();
    }

    // Two-pass chamfer distance transform: cheap O(n) approximate Euclidean distance to nearest land.
    private static void chamferDistance() {
        final float d1 = 1.0F;
        final float d2 = 1.41421356F;
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                int i = z * SIZE + x;
                float d = dist[i];
                if (x > 0) d = Math.min(d, dist[i - 1] + d1);
                if (z > 0) d = Math.min(d, dist[i - SIZE] + d1);
                if (x > 0 && z > 0) d = Math.min(d, dist[i - SIZE - 1] + d2);
                if (x < SIZE - 1 && z > 0) d = Math.min(d, dist[i - SIZE + 1] + d2);
                dist[i] = d;
            }
        }
        for (int z = SIZE - 1; z >= 0; z--) {
            for (int x = SIZE - 1; x >= 0; x--) {
                int i = z * SIZE + x;
                float d = dist[i];
                if (x < SIZE - 1) d = Math.min(d, dist[i + 1] + d1);
                if (z < SIZE - 1) d = Math.min(d, dist[i + SIZE] + d1);
                if (x < SIZE - 1 && z < SIZE - 1) d = Math.min(d, dist[i + SIZE + 1] + d2);
                if (x > 0 && z < SIZE - 1) d = Math.min(d, dist[i + SIZE - 1] + d2);
                dist[i] = d;
            }
        }
    }

    private static void upload() {
        NativeImage img = texture.getPixels();
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                float d = Math.min(dist[z * SIZE + x], MAX_DIST);
                int v = (int) (d / MAX_DIST * 255.0F + 0.5F) & 0xFF;
                img.setPixelRGBA(x, z, 0xFF000000 | v); // ABGR: store distance in the R byte
            }
        }
        texture.upload();
    }

    private static void build() {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
        for (int z = 0; z < SIZE; z++) {
            for (int x = 0; x < SIZE; x++) {
                img.setPixelRGBA(x, z, 0xFF0000FF); // start "all far water" (R = 255)
            }
        }
        texture = new DynamicTexture(img);
        texture.setFilter(true, false); // LINEAR for a smooth ring; no mipmaps

        // Clamp to edge so sampling near the map border doesn't wrap to the far side.
        GlStateManager._bindTexture(texture.getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
    }
}
