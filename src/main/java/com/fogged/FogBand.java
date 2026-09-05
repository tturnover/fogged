package com.fogged;

import net.minecraft.world.level.Level;

/**
 * Geometry of the fog band: where the visible fog surface sits and which block-Y range counts as
 * "under the plane". Shared by the breathing checks, the fog/plane renderers and the under-fog world
 * scour ({@link FogScour}) so they all agree on the murk side of the boundary.
 */
public final class FogBand {
    private FogBand() {}

    /** World-space Y of the visible fog surface; blocks below this are under the plane. */
    public static double surfaceY(Level level) {
        return Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
    }

    /** Highest block-Y that counts as "under the plane" (its centre sits below the surface). */
    public static int bandTopY(Level level) {
        return (int) Math.floor(surfaceY(level) - 0.5);
    }

    /** Highest block-Y the scour acts on: just below the untouched dead zone under the plane. */
    public static int activeTopY(Level level) {
        return bandTopY(level) - Config.WORLD_CHANGE_SKIP.getAsInt();
    }

    /** True when the block at {@code y} is in the active scour range: below the dead zone, down to the floor. */
    public static boolean inActiveZone(Level level, int y) {
        return y <= activeTopY(level) && y >= level.getMinBuildHeight();
    }
}
