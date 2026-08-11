package com.fogged;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Registry of active "breathing spheres" opened by {@code nozzle_filter} blocks. Each
 * live {@link com.fogged.block.NozzleFilterBlockEntity} publishes its <em>world-space</em> centre and
 * current radius here every tick; {@link BreathHandler} (players) and {@link MobSuppressor} (mobs)
 * consult it so anything inside a sphere breathes normally under the fog instead of drowning.
 *
 * <p>The centre is stored explicitly rather than derived from the key, because a filter riding a Sable
 * contraption sits at far-off plot coordinates while its rider is an ordinary world-space entity -- the
 * publisher transforms the plot position to world space (see {@link PlaneSensor#worldCenter}) so the
 * two line up. The key stays the plot {@link BlockPos}, which is stable and unique per filter.
 *
 * <p>Spheres are keyed per dimension. The map is tiny in practice (a handful of nozzle filters), so a
 * linear scan per query is cheaper than any spatial index and keeps the lookup allocation-free.
 *
 * <p>Each side keeps its own map: the server's drives breathing, the client mirrors it only for
 * {@link #insideOther}. One shared map would let the sides -- same dimension keys and positions inside
 * an integrated server -- evict each other's entries when only one has a filter loaded.
 */
public final class BreatheSpheres {

    private record Sphere(Vec3 center, double radius) {}

    private static final Map<ResourceKey<Level>, Map<BlockPos, Sphere>> SERVER = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, Map<BlockPos, Sphere>> CLIENT = new ConcurrentHashMap<>();

    private static Map<ResourceKey<Level>, Map<BlockPos, Sphere>> side(Level level) {
        return level.isClientSide ? CLIENT : SERVER;
    }

    /** Publish/refresh a sphere at a world-space {@code center}. A radius {@code <= 0} removes it. */
    public static void set(Level level, BlockPos pos, Vec3 center, double radius) {
        if (radius <= 0.0 || center == null) {
            remove(level, pos);
            return;
        }
        side(level).computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .put(pos.immutable(), new Sphere(center, radius));
    }

    /** Drop a sphere (block removed / chunk unloaded / fan stopped). */
    public static void remove(Level level, BlockPos pos) {
        Map<BlockPos, Sphere> dim = side(level).get(level.dimension());
        if (dim != null) {
            dim.remove(pos.immutable());
        }
    }

    /** True when {@code point} lies within any active sphere in this level. */
    public static boolean isBreathable(Level level, Vec3 point) {
        Map<BlockPos, Sphere> dim = side(level).get(level.dimension());
        if (dim == null || dim.isEmpty()) {
            return false;
        }
        for (Sphere s : dim.values()) {
            if (point.distanceToSqr(s.center()) <= s.radius() * s.radius()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when {@code point} lies inside some sphere other than {@code self}'s. Hides edge particles
     * where a neighbour already covers the spot -- two overlapping filters otherwise draw their edges
     * through the shared volume, which reads as a wall across open air.
     */
    public static boolean insideOther(Level level, BlockPos self, Vec3 point) {
        Map<BlockPos, Sphere> dim = side(level).get(level.dimension());
        if (dim == null || dim.size() < 2) {
            return false;
        }
        BlockPos key = self.immutable();
        for (Map.Entry<BlockPos, Sphere> e : dim.entrySet()) {
            if (e.getKey().equals(key)) {
                continue;
            }
            Sphere s = e.getValue();
            if (point.distanceToSqr(s.center()) <= s.radius() * s.radius()) {
                return true;
            }
        }
        return false;
    }

    private BreatheSpheres() {}
}
