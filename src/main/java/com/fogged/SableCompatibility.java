package com.fogged;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// Sable integration, isolated so the rest of the mod never references Sable classes directly. Only
// touched when Sable is actually loaded (see WaterlineMap), so the mod runs fine without Sable.
//
// Sable "sub-levels" are little worlds (ships, contraptions) positioned and rotated in the main world
// by a physics pose. To make foam ring them where they cross the breathing boundary, we walk the map
// cells under each sub-level's world footprint, transform that surface point into the sub-level's
// local space, and stamp foam wherever a solid block sits there.
final class SableCompatibility {

    @FunctionalInterface
    interface CellStamper {
        void stamp(int cellX, int cellZ);
    }

    /** Receives each non-air sub-level block (in its own level + local coords) that sits under the plane. */
    @FunctionalInterface
    interface BlockSink {
        void accept(Level subLevel, BlockPos localPos);
    }

    // Safety cap on how big a single sub-level's local scan box may be, so a huge contraption can't
    // stall the tick. Comfortably covers normal ships/contraptions.
    private static final long MAX_SCAN_VOLUME = 262_144L; // 64^3

    private SableCompatibility() {
    }

    /**
     * Visit every non-air block of every sub-level whose centre sits below {@code activeSurfaceY} (world
     * space), handing each to {@code sink}. Used to apply the under-fog rot to ships/contraptions; the
     * caller decides what to do with each block. No-op when there are no sub-levels.
     */
    static void rotSubLevels(Level level, double activeSurfaceY, BlockSink sink) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        Vector3d src = new Vector3d();
        Vector3d dst = new Vector3d();
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();

        for (SubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc bb = sub.boundingBox();
            if (bb.minY() >= activeSurfaceY) {
                continue; // whole sub-level rides above the active zone
            }
            Pose3dc pose = sub.logicalPose();
            Level subLevel = sub.getLevel();

            // Local-space AABB enclosing the world bounding box (inverse-transform its 8 corners).
            double lminX = Double.POSITIVE_INFINITY, lminY = Double.POSITIVE_INFINITY, lminZ = Double.POSITIVE_INFINITY;
            double lmaxX = Double.NEGATIVE_INFINITY, lmaxY = Double.NEGATIVE_INFINITY, lmaxZ = Double.NEGATIVE_INFINITY;
            for (double wx : new double[] {bb.minX(), bb.maxX()}) {
                for (double wy : new double[] {bb.minY(), bb.maxY()}) {
                    for (double wz : new double[] {bb.minZ(), bb.maxZ()}) {
                        src.set(wx, wy, wz);
                        pose.transformPositionInverse(src, dst);
                        lminX = Math.min(lminX, dst.x); lmaxX = Math.max(lmaxX, dst.x);
                        lminY = Math.min(lminY, dst.y); lmaxY = Math.max(lmaxY, dst.y);
                        lminZ = Math.min(lminZ, dst.z); lmaxZ = Math.max(lmaxZ, dst.z);
                    }
                }
            }
            int x0 = Mth.floor(lminX), x1 = Mth.floor(lmaxX);
            int y0 = Mth.floor(lminY), y1 = Mth.floor(lmaxY);
            int z0 = Mth.floor(lminZ), z1 = Mth.floor(lmaxZ);
            long volume = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
            if (volume <= 0 || volume > MAX_SCAN_VOLUME) {
                continue;
            }

            for (int ly = y0; ly <= y1; ly++) {
                for (int lx = x0; lx <= x1; lx++) {
                    for (int lz = z0; lz <= z1; lz++) {
                        local.set(lx, ly, lz);
                        if (subLevel.getBlockState(local).isAir()) {
                            continue;
                        }
                        // World height of this block's centre via the sub-level pose.
                        Vec3 world = pose.transformPosition(new Vec3(lx + 0.5, ly + 0.5, lz + 0.5));
                        if (world.y < activeSurfaceY) {
                            sink.accept(subLevel, local.immutable());
                        }
                    }
                }
            }
        }
    }

    // World-space Y of a block that lives inside a Sable sub-level (ship / contraption): sub-levels
    // are stored in a far-off plot region, so the block's raw Y is meaningless against the world fog
    // plane. Map the block centre through the sub-level's pose to get its true world height. Returns
    // NaN when the position is not inside any sub-level plot (i.e. it's an ordinary world block).
    static double worldY(Level level, BlockPos pos) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(pos)) {
            return Double.NaN;
        }
        LevelPlot plot = container.getPlot(new ChunkPos(pos));
        if (plot == null) {
            return Double.NaN;
        }
        SubLevel sub = plot.getSubLevel();
        if (sub == null) {
            return Double.NaN;
        }
        Vec3 world = sub.logicalPose()
                .transformPosition(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
        return world.y;
    }

    // World-space centre of a block that lives inside a Sable sub-level (ship / contraption). Same
    // mapping as worldY but returns the full transformed point, so a breathing sphere on a contraption
    // is registered where the contraption actually is (riders are ordinary world-space entities).
    // Returns null when the position is not inside any sub-level plot (an ordinary world block).
    static Vec3 worldCenter(Level level, BlockPos pos) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(pos)) {
            return null;
        }
        LevelPlot plot = container.getPlot(new ChunkPos(pos));
        if (plot == null) {
            return null;
        }
        SubLevel sub = plot.getSubLevel();
        if (sub == null) {
            return null;
        }
        return sub.logicalPose()
                .transformPosition(new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
    }

    static void stampSubLevels(Level level, int boundaryY, int originX, int originZ,
                               int size, int cellsPerBlock, CellStamper stamper) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        int cells = size * cellsPerBlock;
        double sampleY = boundaryY + 0.5;
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
        Vector3d src = new Vector3d();
        Vector3d dst = new Vector3d();

        for (SubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc bb = sub.boundingBox();
            // Skip sub-levels that don't straddle the boundary slab.
            if (bb.maxY() < boundaryY || bb.minY() > boundaryY + 1) {
                continue;
            }
            // World XZ footprint, clamped to the map.
            int x0 = Math.max(0, Mth.floor((bb.minX() - originX) * cellsPerBlock));
            int x1 = Math.min(cells - 1, Mth.floor((bb.maxX() - originX) * cellsPerBlock));
            int z0 = Math.max(0, Mth.floor((bb.minZ() - originZ) * cellsPerBlock));
            int z1 = Math.min(cells - 1, Mth.floor((bb.maxZ() - originZ) * cellsPerBlock));
            if (x0 > x1 || z0 > z1) {
                continue;
            }

            Pose3dc pose = sub.logicalPose();
            Level subLevel = sub.getLevel();
            for (int cz = z0; cz <= z1; cz++) {
                double wz = originZ + (cz + 0.5) / cellsPerBlock;
                for (int cx = x0; cx <= x1; cx++) {
                    double wx = originX + (cx + 0.5) / cellsPerBlock;
                    // World -> sub-level-local, then test the block there (reusing joml vectors).
                    src.set(wx, sampleY, wz);
                    pose.transformPositionInverse(src, dst);
                    local.set(Mth.floor(dst.x), Mth.floor(dst.y), Mth.floor(dst.z));
                    if (subLevel.getBlockState(local).blocksMotion()) {
                        stamper.stamp(cx, cz);
                    }
                }
            }
        }
    }
}
