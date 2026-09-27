package com.fogged;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
        /** {@code weight} is 1 where the hull meets the surface, easing to 0 at the band's edge. */
        void stamp(int cellX, int cellZ, float weight);
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

    /** Receives one boundary-crossing sub-level: a contact point on its waterline, and how far the
     *  whole sub-level moved this tick. */
    @FunctionalInterface
    interface WakeSink {
        /** {@code weight} is 1 where the hull meets the surface, easing to 0 at the band's edge. */
        void accept(double worldX, double worldZ, Vec3 motion, float weight);
    }

    // Heights probed either side of the surface, as shares of the band, nearest first. The surface
    // itself is probed before any of them, so a hull that actually crosses costs exactly the one
    // lookup it always did and only one that merely comes close pays for the rest.
    private static final double[] BAND_PROBES = { 1.0 / 3.0, 2.0 / 3.0 };

    /**
     * Weight of the sub-level block nearest the surface on the world column {@code (wx, wz)}: 1 when it
     * meets the surface, easing to 0 over {@code band} either side, and 0 when there is none in reach.
     */
    private static float probeColumn(Level subLevel, Pose3dc pose, Vector3d src, Vector3d dst,
            BlockPos.MutableBlockPos local, double wx, double wz, double surfaceY, double band) {
        if (solidAt(subLevel, pose, src, dst, local, wx, surfaceY, wz)) {
            return 1.0F;
        }
        for (double share : BAND_PROBES) {
            double dy = share * band;
            if (solidAt(subLevel, pose, src, dst, local, wx, surfaceY - dy, wz)
                    || solidAt(subLevel, pose, src, dst, local, wx, surfaceY + dy, wz)) {
                return (float) (1.0 - share);
            }
        }
        return 0.0F;
    }

    private static boolean solidAt(Level subLevel, Pose3dc pose, Vector3d src, Vector3d dst,
            BlockPos.MutableBlockPos local, double wx, double wy, double wz) {
        src.set(wx, wy, wz);
        pose.transformPositionInverse(src, dst);
        local.set(Mth.floor(dst.x), Mth.floor(dst.y), Mth.floor(dst.z));
        return subLevel.getBlockState(local).blocksMotion();
    }

    // How far to the side the hull is asked about when looking for its waterline edge. Half a block:
    // far enough to leave the block the probe landed in, near enough that the edge it finds is the
    // hull's own outline and not the next thing over.
    private static final double EDGE_STEP = 0.5;

    // A weight is only worth anything if it beats its neighbour's by this much -- the probe ramp is
    // coarse (1, 2/3, 1/3), so anything smaller is the same reading twice.
    private static final float EDGE_MARGIN = 1.0e-4F;

    /**
     * Whether the hull's waterline at {@code (wx, wz)} is an EDGE of the contact rather than inside it:
     * one of the four neighbouring columns meets the surface less than this one does (or not at all).
     *
     * <p>What the spray is picked by: without it a deck lying along the surface puffs foam over its
     * whole footprint, where spray belongs where the murk is actually parted -- along the hull's
     * outline. The painted ring is not filtered this way; it covers the whole contact.
     */
    private static boolean atEdge(Level subLevel, Pose3dc pose, Vector3d src, Vector3d dst,
            BlockPos.MutableBlockPos local, double wx, double wz, double surfaceY, double band, float weight) {
        return probeColumn(subLevel, pose, src, dst, local, wx + EDGE_STEP, wz, surfaceY, band) < weight - EDGE_MARGIN
                || probeColumn(subLevel, pose, src, dst, local, wx - EDGE_STEP, wz, surfaceY, band) < weight - EDGE_MARGIN
                || probeColumn(subLevel, pose, src, dst, local, wx, wz + EDGE_STEP, surfaceY, band) < weight - EDGE_MARGIN
                || probeColumn(subLevel, pose, src, dst, local, wx, wz - EDGE_STEP, surfaceY, band) < weight - EDGE_MARGIN;
    }

    /**
     * Sample the waterline of every sub-level crossing {@code surfaceY} and hand each hit to
     * {@code sink}, so ships and contraptions throw the same movement-driven spray an entity does.
     *
     * <p>A sub-level is not an entity and has no delta movement, so its speed comes from the gap
     * between {@link SubLevel#logicalPose()} and {@link SubLevel#lastPose()} -- the distance it
     * travelled over the last tick, which is what the spray needs and what the physics has already
     * settled by the time this runs.
     *
     * <p>Points are sampled from the world footprint and inverse-transformed rather than walked in
     * local space: a rotated hull's footprint is not axis-aligned, and {@code attempts} random probes
     * against it cost the same whatever size the ship is, where a full local scan is cubic.
     */
    static void sampleWakes(Level level, double surfaceY, double band, int attempts,
            RandomSource rnd, WakeSink sink) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        Vector3d src = new Vector3d();
        Vector3d dst = new Vector3d();
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();

        for (SubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc bb = sub.boundingBox();
            if (bb.maxY() < surfaceY - band || bb.minY() > surfaceY + band) {
                continue; // not even within the band of the boundary, so it cannot be raising foam
            }
            Pose3dc pose = sub.logicalPose();
            Vector3dc now = pose.position();
            Vector3dc was = sub.lastPose().position();
            Vec3 motion = new Vec3(now.x() - was.x(), now.y() - was.y(), now.z() - was.z());
            Level subLevel = sub.getLevel();

            for (int i = 0; i < attempts; i++) {
                double wx = bb.minX() + rnd.nextDouble() * (bb.maxX() - bb.minX());
                double wz = bb.minZ() + rnd.nextDouble() * (bb.maxZ() - bb.minZ());
                float weight = probeColumn(subLevel, pose, src, dst, local, wx, wz, surfaceY, band);
                if (weight > 0.0F
                        && atEdge(subLevel, pose, src, dst, local, wx, wz, surfaceY, band, weight)) {
                    sink.accept(wx, wz, motion, weight);
                }
            }
        }
    }

    /** Receives one dissolve disc for a sub-level crossing the surface, in world XZ. */
    @FunctionalInterface
    interface HoleSink {
        /** {@code gap} is the vertical distance from the surface to the hull, 0 while it straddles. */
        void accept(double worldX, double worldZ, double radius, double gap);
    }

    /**
     * Lay dissolve discs along the part of every sub-level that meets the surface, so the plane dithers
     * open around a hull the way it does around a crossing entity. A sub-level is not an entity and so
     * never turns up in the renderer's entity query -- this is that query's other half.
     *
     * <p>One disc per {@code spacing} blocks of the hull's footprint, wherever the hull is actually
     * there: a lattice, not one disc over the whole bounding box, or a pointed bow would open a round
     * hole in the murk well off the ship.
     */
    static void sampleHoles(Level level, double surfaceY, double vertRange, double spacing, double radius,
            double camX, double camZ, double range, HoleSink sink) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        Vector3d src = new Vector3d();
        Vector3d dst = new Vector3d();
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();

        for (SubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc bb = sub.boundingBox();
            double gap = Math.max(0.0, Math.max(surfaceY - bb.maxY(), bb.minY() - surfaceY));
            if (gap > vertRange) {
                continue; // clear of the surface: nothing of it pokes through the plane
            }
            // Nearest point of the footprint to the camera, so a long hull alongside still qualifies.
            double dx = Math.max(0.0, Math.max(bb.minX() - camX, camX - bb.maxX()));
            double dz = Math.max(0.0, Math.max(bb.minZ() - camZ, camZ - bb.maxZ()));
            if (dx * dx + dz * dz > range * range) {
                continue;
            }
            Pose3dc pose = sub.logicalPose();
            Level subLevel = sub.getLevel();
            // The hull's own height nearest the surface: a hull just clear of it is still asked about
            // at the row facing it, which is what its disc is then faded out by.
            double probeY = Mth.clamp(surfaceY, bb.minY(), bb.maxY());
            for (double wz = bb.minZ(); wz <= bb.maxZ(); wz += spacing) {
                for (double wx = bb.minX(); wx <= bb.maxX(); wx += spacing) {
                    if (solidAt(subLevel, pose, src, dst, local, wx, probeY, wz)) {
                        sink.accept(wx, wz, radius, gap);
                    }
                }
            }
        }
    }

    static void stampSubLevels(Level level, double surfaceY, double band, int originX, int originZ,
                               int size, int cellsPerBlock, CellStamper stamper) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        int cells = size * cellsPerBlock;
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
        Vector3d src = new Vector3d();
        Vector3d dst = new Vector3d();

        for (SubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc bb = sub.boundingBox();
            // Skip sub-levels that come nowhere near the boundary. Measured off the surface's own
            // height rather than off its block row, which sits up to a block away from it.
            if (bb.maxY() < surfaceY - band || bb.minY() > surfaceY + band) {
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
            // The whole contact is stamped, not just its outline: the painted ring is a distance field,
            // and a hull lying in the murk should read as murk all the way across what it touches. The
            // spray is the one that goes on the outline alone (see sampleWakes).
            for (int cz = z0; cz <= z1; cz++) {
                double wz = originZ + (cz + 0.5) / cellsPerBlock;
                for (int cx = x0; cx <= x1; cx++) {
                    double wx = originX + (cx + 0.5) / cellsPerBlock;
                    float weight = probeColumn(subLevel, pose, src, dst, local, wx, wz, surfaceY, band);
                    if (weight > 0.0F) {
                        stamper.stamp(cx, cz, weight);
                    }
                }
            }
        }
    }
}
