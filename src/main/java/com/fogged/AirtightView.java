package com.fogged;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Clears the murk out of the sealed room the camera is in. The murk's fog is radial from the camera,
 * so the room is cleared by pushing its start out past the room: {@link #clearRadius} is how far the
 * room reaches from the camera (its farthest cell), and the fog begins there and thickens over the
 * usual fogDistance beyond. The room is clear air, and the murk outside its windows starts at its
 * walls. Radial, so in a long room the near outside is fogged a little late; it is the price of using
 * the fog everything already draws with rather than a pass of its own.
 *
 * <p>A room on a Sable ship is measured in the ship's own plot, the camera and motes mapped into it
 * through the ship's pose each frame, so it clears the same as one built in the world.
 *
 * <p>The cells come from the server ({@link AirtightSync.RoomCells}); whether the camera is inside is
 * decided here from them, so the murk closes in the moment the camera leaves, with no round trip. The
 * radius eases rather than jumping, over {@link #EASE_SECONDS}, so a door is not a pop.
 *
 * <p>Client only; every method is a no-op answer when no room has been sent.
 */
public final class AirtightView {

    /** Time constant of the ease in and out, in seconds. */
    private static final double EASE_SECONDS = 0.25;
    /** Past the farthest cell's centre, to its far corner. */
    private static final double CELL_REACH = 0.87;

    private static double radius;
    private static long lastNanos;
    // Memo of the farthest-cell scan: it only changes when the camera crosses a block or the room does.
    private static LongOpenHashSet memoRoom;
    private static long memoCell = Long.MIN_VALUE;
    private static double memoTarget;

    private AirtightView() {}

    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");

    /** Whether world block {@code pos} is in the room the local player was last sent. */
    public static boolean inRoom(BlockPos pos) {
        AirtightSync.ClientRoom room = AirtightSync.clientRoom;
        if (room.cells().isEmpty()) {
            return false;
        }
        Vec3 at = inRoomSpace(room, Vec3.atCenterOf(pos));
        return at != null && room.cells().contains(BlockPos.containing(at).asLong());
    }

    // A world point in the room's own coordinates: itself for a room in the world, mapped through the
    // ship's pose this frame for one on a Sable ship (whose cells sit in its plot). Null if the ship
    // cannot be found.
    private static Vec3 inRoomSpace(AirtightSync.ClientRoom room, Vec3 world) {
        if (!room.plot()) {
            return world;
        }
        if (!SABLE) {
            return null;
        }
        return SableClientCompat.worldToPlot(net.minecraft.client.Minecraft.getInstance().level, room.anchor(), world);
    }

    /**
     * How far from the camera the murk is held back this frame, in blocks: the room's reach while the
     * camera is in it, easing to 0 once it leaves. Call once per frame from the fog setup; repeated
     * calls in the same frame return the same value.
     */
    public static float clearRadius(Vec3 camera) {
        long now = System.nanoTime();
        double target = target(camera);
        double dt = lastNanos == 0 ? 1.0 : (now - lastNanos) / 1.0e9;
        lastNanos = now;
        double k = 1.0 - Math.exp(-Math.max(0.0, dt) / EASE_SECONDS);
        radius += (target - radius) * k;
        if (radius < 0.05 && target == 0.0) {
            radius = 0.0;
        }
        return (float) radius;
    }

    // The room's reach from the camera, or 0 when the camera is not in it.
    private static double target(Vec3 worldCamera) {
        AirtightSync.ClientRoom current = AirtightSync.clientRoom;
        LongOpenHashSet room = current.cells();
        if (room.isEmpty()) {
            return 0.0;
        }
        // A ship's room is measured in its own plot: the camera is mapped in rather than every cell
        // out, and distances come out the same, the pose being rigid.
        Vec3 camera = inRoomSpace(current, worldCamera);
        if (camera == null) {
            return 0.0;
        }
        long cell = BlockPos.containing(camera).asLong();
        if (room == memoRoom && cell == memoCell) {
            return memoTarget;
        }
        double target = 0.0;
        if (room.contains(cell)) {
            double far = 0.0;
            for (LongIterator it = room.iterator(); it.hasNext(); ) {
                long c = it.nextLong();
                double dx = BlockPos.getX(c) + 0.5 - camera.x;
                double dy = BlockPos.getY(c) + 0.5 - camera.y;
                double dz = BlockPos.getZ(c) + 0.5 - camera.z;
                far = Math.max(far, dx * dx + dy * dy + dz * dz);
            }
            target = Mth.sqrt((float) far) + CELL_REACH;
        }
        memoRoom = room;
        memoCell = cell;
        memoTarget = target;
        return target;
    }
}
