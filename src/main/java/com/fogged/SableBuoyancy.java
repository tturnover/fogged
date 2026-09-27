package com.fogged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

import dev.ryanhcode.sable.api.physics.force.ForceGroup;
import dev.ryanhcode.sable.api.physics.force.ForceGroups;
import dev.ryanhcode.sable.api.physics.force.QueuedForceGroup;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.physics.mass.MassData;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
import dev.ryanhcode.sable.physics.config.dimension_physics.DimensionPhysicsData;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;

import org.joml.Vector3d;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Floats Sable's sub-levels -- its ships and contraptions -- on the murk, as water would float them.
 * Second of the two classes allowed to name Sable types (see {@link SableCompatibility}); {@link Fogged}
 * only reaches it when Sable is actually loaded.
 *
 * <p>Plain Archimedes, per block: every FLOAT block under the visible surface (see
 * {@link Config#SABLE_FLOAT_BLOCKS}) is pushed back along the dimension's gravity by the weight of the
 * murk it displaces, at the block's own position, so a hull that lists gets a righting torque out of
 * the geometry rather than out of a special case. Everything else on board is weight and nothing more,
 * which is what makes where the floats are built matter. The submerged part is then dragged, linearly
 * and in its spin, which is what settles the bob.
 *
 * <p>Sable's force API is body-local: positions are the plot block coordinates the sub-level's blocks
 * actually live at (the same space as {@link MassData#getCenterOfMass()}), and forces are in the
 * body's own frame, so each world-space force is rotated back through the pose before it is queued.
 * Sable resets the queued groups every physics substep and applies them just after the pre-tick event,
 * so everything here is queued afresh each substep and scaled by that substep's own timestep.
 */
final class SableBuoyancy {

    // Cells one hull rebuild may look at. A bigger hull is walked on a coarser lattice instead (see
    // build), so this bounds the rebuild rather than the size of ship that can float.
    private static final long MAX_SCAN_CELLS = 262_144L; // 64^3

    // Hulls are re-measured this often even when the mass says nothing changed -- cheap insurance
    // against a shape change that happens to keep the mass (a block swapped for an equal one).
    private static final long REBUILD_TICKS = 100;

    private static final Map<SubLevel, Hull> HULLS = new WeakHashMap<>();

    // What the configured list resolved to, rebuilt when the list itself changes (it is replaced
    // wholesale on a reload). Globs are matched against the registry once, here, rather than per block,
    // so what is left at lookup time is a map hit and a walk over however many tags were listed.
    private static List<? extends String> cachedFloatList;
    private static Map<Block, Double> floatBlocks = Map.of();
    private static List<FloatTag> floatTags = List.of();

    /** One listed block tag and how hard the murk lifts what it covers. */
    private record FloatTag(TagKey<Block> tag, double strength) {}

    private static void ensureFloatBlocks() {
        List<? extends String> raw = Config.SABLE_FLOAT_BLOCKS.get();
        if (raw == cachedFloatList) {
            return;
        }
        cachedFloatList = raw;
        Map<Pattern, Double> globs = new LinkedHashMap<>();
        List<FloatTag> tags = new ArrayList<>();
        for (String entry : raw) {
            Config.FloatBlock parsed = Config.parseFloatBlock(entry);
            if (parsed == null) {
                Fogged.LOGGER.warn("Fogged: ignoring malformed sableFloatBlocks entry \"{}\" -- expected "
                        + "\"block[=strength]\".", entry);
                continue;
            }
            if (parsed.id().startsWith("#")) {
                ResourceLocation key = ResourceLocation.tryParse(parsed.id().substring(1));
                if (key != null) {
                    tags.add(new FloatTag(TagKey.create(Registries.BLOCK, key), parsed.strength()));
                }
            } else {
                globs.merge(Config.idGlob(parsed.id()), parsed.strength(), Math::max);
            }
        }
        Map<Block, Double> blocks = new HashMap<>();
        if (!globs.isEmpty()) {
            for (Block block : BuiltInRegistries.BLOCK) {
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                for (Map.Entry<Pattern, Double> glob : globs.entrySet()) {
                    if (glob.getKey().matcher(id).matches()) {
                        blocks.merge(block, glob.getValue(), Math::max);
                    }
                }
            }
        }
        floatBlocks = Map.copyOf(blocks);
        floatTags = List.copyOf(tags);
        HULLS.clear(); // every measured hull was measured against the old list
    }

    // How hard the murk lifts this block, 0 for anything unlisted. The strongest entry that names it
    // wins, so a tag can be given a baseline and one block inside it raised, lowered or -- at 0 --
    // taken back out, without the order entries happen to be written in deciding it.
    private static double floatStrength(BlockState state) {
        double strength = floatBlocks.getOrDefault(state.getBlock(), 0.0);
        for (FloatTag listed : floatTags) {
            if (listed.strength() > strength && state.is(listed.tag())) {
                strength = listed.strength();
            }
        }
        return strength;
    }

    // The groups Sable files these forces under, for its own force readout. Looked up in its registry
    // rather than through ForceGroups' registry objects, which are Veil types this mod does not compile
    // against. Null if Sable ever renames them: the forces are then simply not grouped.
    private static final ResourceLocation LIFT_GROUP = ResourceLocation.fromNamespaceAndPath("sable", "balloon_lift");
    private static final ResourceLocation DRAG_GROUP = ResourceLocation.fromNamespaceAndPath("sable", "drag");

    private SableBuoyancy() {}

    static void register() {
        SableEventPlatform.INSTANCE.onPhysicsTick(SableBuoyancy::prePhysicsTick);
    }

    private static void prePhysicsTick(SubLevelPhysicsSystem system, double timeStep) {
        if (!Config.SABLE_BUOYANCY.get() || Config.FLIP_FOG.getAsBoolean()) {
            return;
        }
        Level level = system.getLevel();
        if (!Config.dimensionEnabled(level)) {
            return;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        ensureFloatBlocks();
        if (floatBlocks.isEmpty() && floatTags.isEmpty()) {
            return; // nothing is buoyant, so nothing to work out
        }
        double surfaceY = FogBand.surfaceY(level);
        for (SubLevel sub : container.getAllSubLevels()) {
            if (sub instanceof ServerSubLevel server && !server.isRemoved()) {
                buoy(server, system, surfaceY, timeStep);
            }
        }
    }

    private static void buoy(ServerSubLevel sub, SubLevelPhysicsSystem system, double surfaceY, double timeStep) {
        BoundingBox3dc bounds = sub.boundingBox();
        if (bounds.minY() >= surfaceY) {
            return; // rides entirely above the surface: nothing is displacing murk
        }
        MassData mass = sub.getMassTracker();
        if (mass == null || mass.isInvalid() || mass.getMass() <= 0.0) {
            return;
        }
        RigidBodyHandle handle = system.getPhysicsHandle(sub);
        if (handle == null || !handle.isValid()) {
            return;
        }
        Hull hull = hull(sub, mass.getMass());
        if (hull == null) {
            return;
        }

        Pose3dc pose = sub.logicalPose();
        Vector3d gravity = DimensionPhysicsData.getGravity(sub.getLevel(), pose.position());
        double g = gravity.length();
        if (g <= 0.0) {
            return; // nothing to float against
        }
        Vector3d up = gravity.div(-g); // world-space up: the way the displaced murk pushes

        double density = Config.SABLE_MURK_DENSITY.get();
        // The debug force overlay keeps whatever vectors it is handed, so it gets copies; with the
        // overlay off nothing is retained and one pair of scratch vectors does for the whole hull.
        boolean tracking = sub.isTrackingIndividualQueuedForces();
        QueuedForceGroup lift = group(sub, LIFT_GROUP);
        if (lift == null) {
            return;
        }
        Vector3d point = new Vector3d();
        Vector3d world = new Vector3d();
        Vector3d impulse = new Vector3d();
        double displaced = 0.0;

        for (int i = 0; i < hull.size; i++) {
            point.set(hull.x[i] + 0.5, hull.y[i] + 0.5, hull.z[i] + 0.5);
            pose.transformPosition(point, world);
            double depth = surfaceY - world.y;
            if (depth <= 0.0) {
                continue;
            }
            // Eased over the block that straddles the surface, so a hull rising out of the murk loses
            // its lift smoothly instead of a block's worth at a time.
            double share = Math.min(1.0, depth) * hull.lift[i];
            displaced += share;
            if (density <= 0.0) {
                continue;
            }
            impulse.set(up).mul(density * share * g * timeStep);
            pose.orientation().transformInverse(impulse);
            lift.applyAndRecordPointForce(tracking ? new Vector3d(point) : point,
                    tracking ? new Vector3d(impulse) : impulse);
        }

        double submerged = hull.liftTotal <= 0.0 ? 0.0 : displaced / hull.liftTotal;
        if (submerged > 0.0) {
            drag(sub, handle, pose, mass, submerged, timeStep);
        }
    }

    // The murk's viscosity: bleed a share of the submerged hull's speed and spin per second. The speed
    // reads the same murkDrag every mob is held back by (see MurkDrag), so a ship and a mob alongside it
    // are slowed alike. Capped at taking all of the motion in one substep, so a large drag can never
    // push it back the other way.
    private static void drag(ServerSubLevel sub, RigidBodyHandle handle, Pose3dc pose, MassData mass,
            double submerged, double timeStep) {
        double linear = Math.min(1.0, Config.MURK_DRAG.get() * submerged * timeStep);
        double spin = Math.min(1.0, Config.SABLE_MURK_SPIN_DRAG.get() * submerged * timeStep);
        if (linear <= 0.0 && spin <= 0.0) {
            return;
        }
        Vector3d velocity = handle.getLinearVelocity(new Vector3d());
        Vector3d spinning = handle.getAngularVelocity(new Vector3d());
        pose.orientation().transformInverse(velocity);
        pose.orientation().transformInverse(spinning);
        // Momentum, not velocity: the impulse that removes this share of the motion. The inertia tensor
        // is the body's own, which is why the spin has to be in the body's frame first.
        velocity.mul(-mass.getMass() * linear);
        mass.getInertiaTensor().transform(spinning).mul(-spin);
        QueuedForceGroup drag = group(sub, DRAG_GROUP);
        if (drag != null) {
            drag.getForceTotal().applyLinearAndAngularImpulse(velocity, spinning);
        }
    }

    /** The sub-level's queue for one of Sable's force groups, or null when Sable has no such group. */
    private static QueuedForceGroup group(ServerSubLevel sub, ResourceLocation id) {
        ForceGroup group = ForceGroups.REGISTRY.get(id);
        return group == null ? null : sub.getOrCreateQueuedForceGroup(group);
    }

    /** The hull's float blocks, remeasured when its mass moves or the cache goes stale. */
    private static Hull hull(ServerSubLevel sub, double mass) {
        Hull cached = HULLS.get(sub);
        long now = sub.getLevel().getGameTime();
        if (cached != null && now - cached.builtTick < REBUILD_TICKS
                && Math.abs(mass - cached.mass) <= 0.01 * cached.mass) {
            return cached.size == 0 ? null : cached;
        }
        Hull built = build(sub, mass, now);
        HULLS.put(sub, built);
        return built.size == 0 ? null : built;
    }

    // Walk the sub-level's own block bounds and keep every float block. A hull too big to
    // walk whole is walked on a lattice, and one with more blocks than the probe budget keeps every
    // k-th of them -- spread through the hull, not taken off its keel, or the lift would all be applied
    // low and the ship would float on its bottom layer. Each kept block carries the lift of the
    // blocks it stands in for, so how deep the ship floats does not change with the sampling.
    private static Hull build(ServerSubLevel sub, double mass, long tick) {
        BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
        Level level = sub.getLevel();
        int width = bounds.maxX() - bounds.minX() + 1;
        int height = bounds.maxY() - bounds.minY() + 1;
        int length = bounds.maxZ() - bounds.minZ() + 1;
        if (width <= 0 || height <= 0 || length <= 0) {
            return new Hull(0, tick, mass);
        }
        int stride = 1;
        long cells = (long) width * height * length;
        while (cells / ((long) stride * stride * stride) > MAX_SCAN_CELLS) {
            stride++;
        }
        double latticeShare = (double) stride * stride * stride; // blocks each scanned cell stands in for

        // First pass: how many float blocks there are, and how much lift they carry between them.
        int found = 0;
        double liftTotal = 0.0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = bounds.minY(); y <= bounds.maxY(); y += stride) {
            for (int x = bounds.minX(); x <= bounds.maxX(); x += stride) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z += stride) {
                    double lift = liftVolume(level, pos.set(x, y, z));
                    if (lift > 0.0) {
                        found++;
                        liftTotal += lift * latticeShare;
                    }
                }
            }
        }
        if (found == 0) {
            return new Hull(0, tick, mass);
        }

        // Second pass: keep every keepEvery-th of them, and give each kept block an equal share of the
        // hull's lift so the total is preserved whatever the sampling threw away.
        int budget = Config.SABLE_BUOYANCY_PROBES.getAsInt();
        int keepEvery = (found + budget - 1) / budget;
        Hull hull = new Hull((found + keepEvery - 1) / keepEvery, tick, mass);
        hull.liftTotal = liftTotal;
        double perProbe = liftTotal / hull.size;
        int seen = 0;
        int kept = 0;
        for (int y = bounds.minY(); y <= bounds.maxY() && kept < hull.size; y += stride) {
            for (int x = bounds.minX(); x <= bounds.maxX() && kept < hull.size; x += stride) {
                for (int z = bounds.minZ(); z <= bounds.maxZ() && kept < hull.size; z += stride) {
                    if (liftVolume(level, pos.set(x, y, z)) <= 0.0) {
                        continue;
                    }
                    if (seen++ % keepEvery != 0) {
                        continue;
                    }
                    hull.x[kept] = x;
                    hull.y[kept] = y;
                    hull.z[kept] = z;
                    hull.lift[kept] = perProbe;
                    kept++;
                }
            }
        }
        return hull;
    }

    /**
     * What the block at {@code pos} is worth to the lift: its Sable volume times its listed strength, so
     * one entry can float harder than another. 0 for everything unlisted -- air, and the rest of the
     * ship, which is weight without lift.
     */
    private static double liftVolume(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        double strength = floatStrength(state);
        return strength <= 0.0 ? 0.0 : strength * Math.max(0.0, PhysicsBlockPropertyHelper.getVolume(state));
    }

    // One hull's float blocks, in the sub-level's own plot coordinates, with the lift each one carries
    // (its displaced volume times its listed strength). Cheap to walk every substep; rebuilt only when
    // the sub-level changes.
    private static final class Hull {
        final int[] x;
        final int[] y;
        final int[] z;
        final double[] lift;
        final int size;
        final long builtTick;
        final double mass;
        double liftTotal;

        Hull(int size, long builtTick, double mass) {
            this.size = size;
            this.x = new int[size];
            this.y = new int[size];
            this.z = new int[size];
            this.lift = new double[size];
            this.builtTick = builtTick;
            this.mass = mass;
        }
    }
}
