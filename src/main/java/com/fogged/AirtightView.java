package com.fogged;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Which blocks the local player sees in a sealed room's air, so the murk's particles -- motes, foam --
 * are not spawned there. The fog itself is left alone: a sealed room under the murk still looks it.
 *
 * <p>Two sets come from the server ({@link AirtightSync}): the room the player stands in
 * ({@link AirtightSync.RoomCells}, a room on a Sable ship mapped through the ship's pose each frame,
 * whose cells sit in its plot), and every other sealed room in the world near them
 * ({@link AirtightSync.NearbyCells}), so a room seen through a window is clear of them too.
 *
 * <p>Client only; every answer is false when nothing has been sent.
 */
public final class AirtightView {

    private AirtightView() {}

    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");

    /** Whether world block {@code pos} is in a sealed room's air the local player knows of. */
    public static boolean inRoom(BlockPos pos) {
        LongOpenHashSet nearby = AirtightSync.nearbyCells;
        if (!nearby.isEmpty() && nearby.contains(pos.asLong())) {
            return true;
        }
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
}
