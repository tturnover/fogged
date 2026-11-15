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

    // Whether pos lies in a sub-level's plot (a block riding a ship / contraption).
    static boolean inPlot(Level level, BlockPos pos) {
        return subLevelAt(level, pos) != null;
    }

    // A world-space point mapped into the plot of the sub-level that holds anchor, or null when anchor
    // is not in a plot. What an airtight room on a ship needs: its cells are plot coordinates, while
    // the rider asking about them stands in the world.
    static Vec3 toPlot(Level level, BlockPos anchor, Vec3 world) {
        SubLevel sub = subLevelAt(level, anchor);
        if (sub == null) {
            return null;
        }
        Vector3d dst = new Vector3d();
        sub.logicalPose().transformPositionInverse(new Vector3d(world.x, world.y, world.z), dst);
        return new Vec3(dst.x, dst.y, dst.z);
    }

    // Whether a and b lie in the same frame: both in the world, or both in one sub-level's plot.
    static boolean sameFrame(Level level, BlockPos a, BlockPos b) {
        return subLevelAt(level, a) == subLevelAt(level, b);
    }

    /**
     * The other frames about an air fill whose seeds are {@code seeds}: everything but the frame the
     * seeds are in (see {@link AirFill.Others}). Null when there is nothing else -- a fill in the world
     * with no sub-level in its reach -- so the fill runs as it always did.
     */
    static AirFill.Others frames(Level level, java.util.Collection<AirFill.Seed> seeds, int capacity) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return null;
        }
        SubLevel own = subLevelAt(level, seeds.iterator().next().pos());
        // The air can get no farther from its seeds than the cells it may hold.
        double reach = capacity + 2.0;
        java.util.List<Vec3> from = new java.util.ArrayList<>(seeds.size());
        for (AirFill.Seed s : seeds) {
            Vec3 c = Vec3.atCenterOf(s.pos());
            from.add(own == null ? c : own.logicalPose().transformPosition(c));
        }
        java.util.List<SubLevel> others = new java.util.ArrayList<>();
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub == own || sub.isRemoved()) {
                continue;
            }
            BoundingBox3dc bb = sub.boundingBox();
            for (Vec3 c : from) {
                double dx = Math.max(0.0, Math.max(bb.minX() - c.x, c.x - bb.maxX()));
                double dy = Math.max(0.0, Math.max(bb.minY() - c.y, c.y - bb.maxY()));
                double dz = Math.max(0.0, Math.max(bb.minZ() - c.z, c.z - bb.maxZ()));
                if (dx * dx + dy * dy + dz * dz <= reach * reach) {
                    others.add(sub);
                    break;
                }
            }
        }
        if (own == null && others.isEmpty()) {
            return null;
        }
        return new Frames(level, own, others);
    }

    /**
     * The other frames about one fill. The world is one of them for a fill on a ship; every sub-level
     * near the fill but its own is. Walls are found by testing the segment between two cell centres,
     * in world space, against each frame's collision shapes along it.
     */
    private static final class Frames implements AirFill.Others {
        private final Level level;
        private final SubLevel own;      // null: the fill is in the world
        private final LevelPlot ownPlot;
        private final java.util.List<SubLevel> subs;
        private final Vector3d src = new Vector3d();
        private final Vector3d dst = new Vector3d();
        private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        // For a fill in the world, per column: the height a ray up it was cast from, and where it first
        // met a sub-level (infinite: it met none). Rays are cast only for cells open to the sky, and
        // under a dome that is most of the room, so each column is asked once or twice, not per cell.
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<double[]> columns =
                new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        Frames(Level level, SubLevel own, java.util.List<SubLevel> subs) {
            this.level = level;
            this.own = own;
            this.ownPlot = own == null ? null : own.getPlot();
            this.subs = subs;
        }

        private Vec3 world(BlockPos cell) {
            Vec3 c = new Vec3(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
            return own == null ? c : own.logicalPose().transformPosition(c);
        }

        private Vec3 toPlot(SubLevel sub, Vec3 world) {
            src.set(world.x, world.y, world.z);
            sub.logicalPose().transformPositionInverse(src, dst);
            return new Vec3(dst.x, dst.y, dst.z);
        }

        @Override
        public boolean ownHas(BlockPos pos) {
            // A plot chunk that was never made holds nothing; asking the level for it would say it is
            // not loaded and stall the fill for good.
            return own == null || ownPlot != null && ownPlot.contains(new ChunkPos(pos)) && level.isLoaded(pos);
        }

        @Override
        public boolean loaded(BlockPos pos) {
            Vec3 w = world(pos);
            return level.isLoaded(probe.set(Mth.floor(w.x), Mth.floor(w.y), Mth.floor(w.z)));
        }

        @Override
        public boolean occupied(BlockPos pos) {
            Vec3 w = world(pos);
            if (own != null && solidAt(level, w)) {
                return true;
            }
            for (SubLevel sub : subs) {
                BoundingBox3dc bb = sub.boundingBox();
                if (bb.contains(w.x, w.y, w.z) && solidAt(sub.getLevel(), toPlot(sub, w))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public boolean blocks(BlockPos from, BlockPos to) {
            Vec3 a = world(from);
            Vec3 b = world(to);
            if (own != null && mayTouchGround(a, b) && hit(level, a, b) != null) {
                return true;
            }
            double x0 = Math.min(a.x, b.x), x1 = Math.max(a.x, b.x);
            double y0 = Math.min(a.y, b.y), y1 = Math.max(a.y, b.y);
            double z0 = Math.min(a.z, b.z), z1 = Math.max(a.z, b.z);
            for (SubLevel sub : subs) {
                if (sub.boundingBox().intersects(x0, y0, z0, x1, y1, z1)
                        && hit(sub.getLevel(), toPlot(sub, a), toPlot(sub, b)) != null) {
                    return true;
                }
            }
            return false;
        }

        // Whether the world's blocks could be anywhere along a to b: some column it crosses has a block
        // that stops movement at its height or above. Most of a ship's room is up in the open air, where
        // this saves walking the world's blocks at every step.
        private boolean mayTouchGround(Vec3 a, Vec3 b) {
            int x0 = Mth.floor(Math.min(a.x, b.x)), x1 = Mth.floor(Math.max(a.x, b.x));
            int z0 = Mth.floor(Math.min(a.z, b.z)), z1 = Mth.floor(Math.max(a.z, b.z));
            double low = Math.min(a.y, b.y);
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (!level.isLoaded(probe.set(x, Mth.floor(low), z))
                            || low < level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z)) {
                        return true;
                    }
                }
            }
            return false;
        }

        @Override
        public boolean covered(BlockPos pos) {
            Vec3 w = world(pos);
            if (own != null) {
                int x = Mth.floor(w.x), z = Mth.floor(w.z);
                if (level.isLoaded(probe.set(x, Mth.floor(w.y), z)) && w.y < level.getHeight(
                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z)) {
                    return true; // under the world's own roof: a ship in a hangar or a cave
                }
                return coverY(w) < Double.POSITIVE_INFINITY;
            }
            long column = ChunkPos.asLong(pos.getX(), pos.getZ());
            double[] known = columns.get(column);
            if (known != null) {
                if (w.y < known[1]) {
                    return true; // the same line up meets that block over this cell as well
                }
                if (known[1] == Double.POSITIVE_INFINITY && w.y >= known[0]) {
                    return false; // nothing all the way up from lower down, so nothing from here
                }
            }
            double hitY = coverY(w);
            columns.put(column, new double[] {w.y, hitY});
            return hitY < Double.POSITIVE_INFINITY;
        }

        // The world height at which a ray straight up from w first meets a sub-level's block, or
        // infinity when it meets none.
        private double coverY(Vec3 w) {
            double best = Double.POSITIVE_INFINITY;
            for (SubLevel sub : subs) {
                BoundingBox3dc bb = sub.boundingBox();
                if (w.x < bb.minX() || w.x > bb.maxX() || w.z < bb.minZ() || w.z > bb.maxZ() || bb.maxY() <= w.y) {
                    continue;
                }
                Vec3 top = new Vec3(w.x, bb.maxY() + 1.0, w.z);
                Vec3 la = toPlot(sub, w);
                Vec3 lb = toPlot(sub, top);
                Vec3 at = hit(sub.getLevel(), la, lb);
                if (at != null) {
                    double t = at.distanceTo(la) / Math.max(1.0E-9, lb.distanceTo(la));
                    best = Math.min(best, w.y + t * (top.y - w.y));
                }
            }
            return best;
        }
    }

    // Whether a block that stops air fills the point p of `frame` (its own coordinates).
    private static boolean solidAt(Level frame, Vec3 p) {
        BlockPos pos = new BlockPos(Mth.floor(p.x), Mth.floor(p.y), Mth.floor(p.z));
        if (!frame.isLoaded(pos)) {
            return false;
        }
        net.minecraft.world.level.block.state.BlockState state = frame.getBlockState(pos);
        if (lets(state)) {
            return false;
        }
        double lx = p.x - pos.getX(), ly = p.y - pos.getY(), lz = p.z - pos.getZ();
        for (net.minecraft.world.phys.AABB box : state.getCollisionShape(frame, pos).toAabbs()) {
            if (lx >= box.minX && lx <= box.maxX && ly >= box.minY && ly <= box.maxY && lz >= box.minZ && lz <= box.maxZ) {
                return true;
            }
        }
        return false;
    }

    // Where the segment a to b, in `frame`'s own coordinates, first runs into the collision shape of a
    // block that stops air; null when it runs into none. A chunk not loaded holds nothing, so asking
    // never loads one.
    private static Vec3 hit(Level frame, Vec3 a, Vec3 b) {
        return net.minecraft.world.level.BlockGetter.traverseBlocks(a, b, frame, (f, pos) -> {
            if (!f.isLoaded(pos)) {
                return null;
            }
            net.minecraft.world.level.block.state.BlockState state = f.getBlockState(pos);
            if (lets(state)) {
                return null;
            }
            net.minecraft.world.phys.BlockHitResult r = state.getCollisionShape(f, pos).clip(a, b, pos);
            return r == null ? null : r.getLocation();
        }, f -> null);
    }

    private static boolean lets(net.minecraft.world.level.block.state.BlockState state) {
        return state.isAir() || state.is(com.fogged.registry.ModTags.LETS_AIR_THROUGH);
    }

    private static SubLevel subLevelAt(Level level, BlockPos pos) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(pos)) {
            return null;
        }
        LevelPlot plot = container.getPlot(new ChunkPos(pos));
        return plot == null ? null : plot.getSubLevel();
    }

    // Whether a world-space point sits inside a solid block of any sub-level. Sub-level blocks live in
    // their far-off plot, so the main level reports air at the point however solid the hull there is.
    static boolean insideHull(Level level, double x, double y, double z) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        Vector3d src = new Vector3d();
        Vector3d dst = new Vector3d();
        BlockPos.MutableBlockPos local = new BlockPos.MutableBlockPos();
        for (SubLevel sub : container.getAllSubLevels()) {
            BoundingBox3dc bb = sub.boundingBox();
            if (x < bb.minX() || x > bb.maxX() || y < bb.minY() || y > bb.maxY()
                    || z < bb.minZ() || z > bb.maxZ()) {
                continue;
            }
            if (solidAt(sub.getLevel(), sub.logicalPose(), src, dst, local, x, y, z)) {
                return true;
            }
        }
        return false;
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
