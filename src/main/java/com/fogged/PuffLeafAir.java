package com.fogged;

import com.fogged.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * True when a world-space point sits inside a {@code puff_bush_leaves} block -- the canopy traps a
 * pocket of breathable air, so a head poking into one shouldn't count as being under the fog.
 * {@link BreathHandler} (players) and {@link MobSuppressor} (mobs) both check this the same way they
 * already check {@link BreatheSpheres}.
 *
 * <p>Never grants free breathing underwater: both callers already skip this check entirely once the
 * entity's eye is in a real water fluid, so a leaves block that happens to be submerged still drowns
 * normally.
 *
 * <p>Also checks Sable sub-levels (ships / contraptions) via {@link SableFoam}, since a leaves block
 * riding one lives at far-off plot coordinates that an ordinary world-space lookup would miss.
 */
final class PuffLeafAir {
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    static boolean isBreathable(Level level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point.x, point.y, point.z);
        if (level.getBlockState(pos).is(ModBlocks.PUFF_BUSH_LEAVES.get())) {
            return true;
        }
        return SABLE && SableFoam.isPuffLeaves(level, point);
    }

    private PuffLeafAir() {}
}
