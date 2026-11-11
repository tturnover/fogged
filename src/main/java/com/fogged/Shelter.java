package com.fogged;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The one question the murk's rules ask: is this spot in breathable air? Either a nozzle filter's
 * sphere ({@link BreatheSpheres}) or a sealed room an air compressor holds ({@link AirtightRooms}).
 * Breathing, mob suppression and the world changes all ask here, so the two kinds of shelter can
 * never disagree about what they spare.
 */
public final class Shelter {

    /** True when the world-space {@code point} is in breathable air. */
    public static boolean isBreathable(Level level, Vec3 point) {
        return BreatheSpheres.isBreathable(level, point) || AirtightRooms.isBreathable(level, point);
    }

    /**
     * True when {@code entity} is breathing clean air at its eyes. For a player, a sealed room's air
     * is smoothed ({@link AirtightSync}): they keep breathing it for a moment after stepping out, so a
     * doorway or a door swinging shut does not flicker their air bar. Anything else is asked as a point.
     */
    public static boolean breathes(Entity entity) {
        if (BreatheSpheres.isBreathable(entity.level(), entity.getEyePosition())) {
            return true;
        }
        return entity instanceof Player player
                ? AirtightSync.sheltered(player)
                : AirtightRooms.isBreathable(entity.level(), entity.getEyePosition());
    }

    /** True when the block at {@code pos} stands in breathable air, and so is none of the murk's business. */
    public static boolean shelters(Level level, BlockPos pos) {
        return BreatheSpheres.shelters(level, pos) || AirtightRooms.shelters(level, pos);
    }

    private Shelter() {}
}
