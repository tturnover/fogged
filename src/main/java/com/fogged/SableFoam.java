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
final class SableFoam {

    @FunctionalInterface
    interface CellStamper {
        void stamp(int cellX, int cellZ);
    }

    private SableFoam() {
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
