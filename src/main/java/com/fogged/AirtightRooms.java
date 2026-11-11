package com.fogged;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.LevelEvent;

/**
 * Registry of the airtight spaces air compressors hold, the room-shaped twin of
 * {@link BreatheSpheres}. Each running compressor owns one {@link Space}: the cells its air has
 * reached so far (breathable) and the box it watches for changes. {@link Shelter} asks it alongside
 * the spheres, so a sealed room counts everywhere a sphere does -- breathing, mobs, and the murk's
 * world changes.
 *
 * <p>Rooms are server-side only. The client is told just one thing, whether its own player is in a
 * room's air ({@link AirtightSync}), which is all its own breathing tick needs; any other client
 * query answers no.
 *
 * <p>A compressor riding a Sable sub-level fills the room in the sub-level's plot, where its walls
 * actually are, so the cells are plot coordinates. A world-space point (a rider's eye) is mapped into
 * the plot before it is looked up; a block position handed in by the scour is already in plot space.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class AirtightRooms {

    private static final boolean SABLE = ModList.get().isLoaded("sable");

    /** One compressor's air: what is breathable now, and what it watches. Server thread only. */
    public static final class Space {
        /** The controller's position; for a sub-level, the plot position it is looked up by. */
        final BlockPos anchor;
        /** Whether the anchor sits in a Sable plot, so world-space points must be mapped in. */
        final boolean plot;
        /** The cells breathable now. Grows as a room pressurizes; cleared when it floods. */
        final LongOpenHashSet cells = new LongOpenHashSet();
        // The box of every cell the last fill touched, grown by one so a wall block's change counts.
        private int minX = 1, minY = 1, minZ = 1, maxX = 0, maxY = 0, maxZ = 0;
        private volatile boolean dirty = true;

        Space(BlockPos anchor, boolean plot) {
            this.anchor = anchor.immutable();
            this.plot = plot;
        }

        /** Watch the box around every cell of {@code fill}: a block change there re-checks the room. */
        public void watch(AirFill fill) {
            watch(fill.order().toLongArray());
        }

        /** As {@link #watch(AirFill)}, taking in the ducts that carried the air there as well. */
        public void watch(AirFill fill, java.util.Collection<BlockPos> ducts) {
            long[] cells = fill.order().toLongArray();
            if (!ducts.isEmpty()) {
                long[] all = java.util.Arrays.copyOf(cells, cells.length + ducts.size());
                int i = cells.length;
                for (BlockPos d : ducts) {
                    all[i++] = d.asLong();
                }
                cells = all;
            }
            watch(cells);
        }

        private void watch(long[] cells) {
            int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
            for (long cell : cells) {
                int x = BlockPos.getX(cell), y = BlockPos.getY(cell), z = BlockPos.getZ(cell);
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z);
                x1 = Math.max(x1, x); y1 = Math.max(y1, y); z1 = Math.max(z1, z);
            }
            // The compressor itself is part of the shell: its own cells count as watched too.
            x0 = Math.min(x0, anchor.getX() - 3); y0 = Math.min(y0, anchor.getY() - 3); z0 = Math.min(z0, anchor.getZ() - 3);
            x1 = Math.max(x1, anchor.getX() + 3); y1 = Math.max(y1, anchor.getY() + 3); z1 = Math.max(z1, anchor.getZ() + 3);
            minX = x0 - 1; minY = y0 - 1; minZ = z0 - 1;
            maxX = x1 + 1; maxY = y1 + 1; maxZ = z1 + 1;
        }

        boolean watches(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        /** Something changed in the watched box since the last call; clears the flag. */
        public boolean takeDirty() {
            boolean was = dirty;
            dirty = false;
            return was;
        }

        public void markDirty() {
            dirty = true;
        }

        public LongOpenHashSet cells() {
            return cells;
        }

        /** Whether the room rides a Sable sub-level. */
        public boolean onShip() {
            return plot;
        }

        // What the compressor last said about this room, for the debug view (AirtightDebug).
        volatile int state;
        volatile int volume;
        volatile int capacity;
        volatile long[] leakPath = new long[0];
        /** Fully pressurized: the room is saved with the world (see {@link Saved}). */
        volatile boolean sealed;

        /** The compressor's state ordinal, room volume, capacity and leak path, for the debug view. */
        public void report(int state, boolean sealed, int volume, int capacity, long[] leakPath) {
            this.state = state;
            this.sealed = sealed;
            this.volume = volume;
            this.capacity = capacity;
            this.leakPath = leakPath;
        }

        boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX && pos.getY() >= minY && pos.getY() <= maxY
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ && cells.contains(pos.asLong());
        }
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Space>> SPACES = new ConcurrentHashMap<>();

    /** The space for the compressor controller at {@code anchor}, made on first use. Server only. */
    public static Space open(Level level, BlockPos anchor) {
        return SPACES.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(anchor.immutable(), a -> new Space(a, SABLE && SableCompatibility.inPlot(level, a)));
    }

    /** Drop the space at {@code anchor}: the compressor is gone or no longer a controller. */
    public static void close(Level level, BlockPos anchor) {
        Map<BlockPos, Space> dim = SPACES.get(level.dimension());
        if (dim != null) {
            dim.remove(anchor);
        }
    }

    /** Every space in {@code level} whose compressor is within {@code range} blocks of {@code pos}. */
    public static java.util.List<Space> near(Level level, BlockPos pos, double range) {
        Map<BlockPos, Space> dim = SPACES.get(level.dimension());
        if (dim == null || dim.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<Space> out = new java.util.ArrayList<>();
        Vec3 at = Vec3.atCenterOf(pos);
        for (Space s : dim.values()) {
            // Where the compressor really is: a ship's is far off in its plot, so ask the ship's pose.
            Vec3 anchor = worldAnchor(level, s);
            if (anchor.distanceToSqr(at) <= range * range) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The room whose air holds the world-space {@code point}, or null. A room on a Sable sub-level is
     * looked up through the ship's pose. Server only.
     */
    public static Space spaceAt(Level level, Vec3 point) {
        Map<BlockPos, Space> dim = SPACES.get(level.dimension());
        if (dim == null || dim.isEmpty() || level.isClientSide) {
            return null;
        }
        BlockPos world = BlockPos.containing(point);
        for (Space s : dim.values()) {
            if (s.cells.isEmpty()) {
                continue;
            }
            BlockPos at = world;
            if (s.plot) {
                Vec3 local = SableCompatibility.toPlot(level, s.anchor, point);
                if (local == null) {
                    continue;
                }
                at = BlockPos.containing(local);
            }
            if (s.contains(at)) {
                return s;
            }
        }
        return null;
    }

    /** Where the space's compressor really is: a ship's is far off in its plot, so ask the ship. */
    static Vec3 worldAnchor(Level level, Space s) {
        return s.plot ? PlaneSensor.worldCenter(level, s.anchor) : Vec3.atCenterOf(s.anchor);
    }

    /** Whether the space is still registered (its compressor has not gone). */
    public static boolean isOpen(Level level, Space space) {
        Map<BlockPos, Space> dim = SPACES.get(level.dimension());
        return dim != null && dim.get(space.anchor) == space;
    }

    /** True when the world-space {@code point} lies in the breathable part of some sealed room. */
    public static boolean isBreathable(Level level, Vec3 point) {
        if (level.isClientSide) {
            return AirtightSync.clientBreathable(point); // the server's word on the local player
        }
        Map<BlockPos, Space> dim = SPACES.get(level.dimension());
        if (dim == null || dim.isEmpty()) {
            return false;
        }
        BlockPos world = BlockPos.containing(point);
        for (Space s : dim.values()) {
            if (s.cells.isEmpty()) {
                continue;
            }
            BlockPos at = world;
            if (s.plot) {
                Vec3 local = SableCompatibility.toPlot(level, s.anchor, point);
                if (local == null) {
                    continue;
                }
                at = BlockPos.containing(local);
            }
            if (s.contains(at)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the block at {@code pos} (in its own level's coordinates, plot space for a sub-level)
     * is in a sealed room or forms part of its shell: the room's air reaches it, or touches one of its
     * faces. So a torch on the wall, a furnace set into it, and the floor the room stands on are all
     * spared the murk.
     */
    public static boolean shelters(Level level, BlockPos pos) {
        if (level.isClientSide) {
            return false;
        }
        Map<BlockPos, Space> dim = SPACES.get(level.dimension());
        if (dim == null || dim.isEmpty()) {
            return false;
        }
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Space s : dim.values()) {
            if (s.cells.isEmpty() || !s.watches(pos)) {
                continue;
            }
            if (s.cells.contains(pos.asLong())) {
                return true;
            }
            for (Direction d : Direction.values()) {
                if (s.cells.contains(n.setWithOffset(pos, d).asLong())) {
                    return true;
                }
            }
        }
        return false;
    }

    // A block changed: every room whose box takes it in re-checks on its compressor's next tick. Doors,
    // trapdoors and pistons all come through here, which is what floods a room the tick it is opened.
    @SubscribeEvent
    static void onNeighborNotify(BlockEvent.NeighborNotifyEvent event) {
        if (!(event.getLevel() instanceof Level level) || level.isClientSide) {
            return;
        }
        Map<BlockPos, Space> dim = SPACES.get(level.dimension());
        if (dim == null || dim.isEmpty()) {
            return;
        }
        BlockPos pos = event.getPos();
        for (Space s : dim.values()) {
            if (s.watches(pos)) {
                s.markDirty();
            }
        }
    }

    // ================================================================================================
    // Saved with the world
    // ================================================================================================

    /**
     * The sealed rooms of one dimension, saved with it. They are back in place the moment the level
     * loads -- before any chunk is sent to a player, and so before the murk's scour sweeps those
     * chunks and knocks the torches in a room down for want of a room that its compressor has not
     * ticked back into being yet. A room whose compressor's chunk is unloaded stays too, for the same
     * reason. The compressor checks it again on its first tick and floods it then if it has to.
     */
    static final class Saved extends SavedData {
        private final Level level;

        Saved(Level level) {
            this.level = level;
        }

        static Saved load(Level level, CompoundTag tag) {
            Saved saved = new Saved(level);
            Map<BlockPos, Space> dim = SPACES.computeIfAbsent(level.dimension(), k -> new ConcurrentHashMap<>());
            ListTag rooms = tag.getList("Rooms", Tag.TAG_COMPOUND);
            for (int i = 0; i < rooms.size(); i++) {
                CompoundTag room = rooms.getCompound(i);
                long[] cells = room.getLongArray("Cells");
                if (cells.length == 0) {
                    continue;
                }
                Space space = new Space(BlockPos.of(room.getLong("Anchor")), room.getBoolean("Plot"));
                space.cells.addAll(it.unimi.dsi.fastutil.longs.LongArrayList.wrap(cells));
                space.watch(cells);
                space.sealed = true;
                space.volume = cells.length;
                dim.put(space.anchor, space);
            }
            return saved;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag rooms = new ListTag();
            Map<BlockPos, Space> dim = SPACES.get(level.dimension());
            if (dim != null) {
                for (Space s : dim.values()) {
                    if (!s.sealed || s.cells.isEmpty()) {
                        continue;
                    }
                    CompoundTag room = new CompoundTag();
                    room.putLong("Anchor", s.anchor.asLong());
                    room.putBoolean("Plot", s.plot);
                    room.putLongArray("Cells", s.cells.toLongArray());
                    rooms.add(room);
                }
            }
            tag.put("Rooms", rooms);
            return tag;
        }

        // Rooms change as they are played in, so every save writes them; there are only ever a few.
        @Override
        public boolean isDirty() {
            return true;
        }
    }

    @SubscribeEvent
    static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SPACES.remove(level.dimension()); // a world opened before this one in the same session
            level.getDataStorage().computeIfAbsent(new SavedData.Factory<>(
                    () -> new Saved(level), (tag, registries) -> Saved.load(level, tag), null), "fogged_airtight");
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SPACES.remove(level.dimension());
        }
    }

    private AirtightRooms() {}
}
