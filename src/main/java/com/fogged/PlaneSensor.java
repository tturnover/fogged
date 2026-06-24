package com.fogged;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Resolves the world-space height of a block for fog-plane tests, transparently handling Sable
 * sub-levels (ships / contraptions). For an ordinary world block this is just the block centre; for
 * a block riding a sub-level it is the centre mapped through the sub-level's physics pose.
 *
 * <p>All Sable types are kept behind {@link SableFoam} and only reached when Sable is loaded, so the
 * mod still runs without it (same isolation pattern as the foam map).
 */
public final class PlaneSensor {
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    private PlaneSensor() {}

    /** World-space Y of the centre of the block at {@code pos}. */
    public static double worldY(Level level, BlockPos pos) {
        if (SABLE) {
            double y = SableFoam.worldY(level, pos);
            if (!Double.isNaN(y)) {
                return y; // block rides a sub-level: use its transformed world height
            }
        }
        return pos.getY() + 0.5;
    }
}
