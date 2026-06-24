package com.fogged;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import com.fogged.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The fog-moss puddle: a flat patch of {@link ModBlocks#FOG_MOSS} that creeps over the natural ground
 * beneath the fog plane wherever the murk kills vegetation or a mob dies (see {@link FogMossEvents}).
 *
 * <p>A puddle is grown by a horizontal flood-fill that hugs the surface (stepping up/down one block to
 * follow terrain), replacing only "natural" blocks — worldgen dirt/stone/sand and the like — so it eats
 * the landscape but stops at anything a player built. Its size in blocks is {@code strength *}
 * {@link Config#FOG_MOSS_SIZE_PER_STRENGTH}.
 */
public final class FogMoss {
    private FogMoss() {}

    /** World-space Y of the visible fog surface; blocks below this are under the plane. */
    public static double surfaceY(Level level) {
        return Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
    }

    /** Highest block-Y that counts as "under the plane" (its centre sits below the surface). */
    public static int bandTopY(Level level) {
        return (int) Math.floor(surfaceY(level) - 0.5);
    }

    /** Highest block-Y the rot acts on: just below the untouched dead zone under the plane. */
    public static int activeTopY(Level level) {
        return bandTopY(level) - Config.FOG_MOSS_SKIP.getAsInt();
    }

    /** True when the block at {@code y} is in the active rot range: below the dead zone, down to the floor. */
    public static boolean inActiveZone(Level level, int y) {
        return y <= activeTopY(level) && y >= level.getMinBuildHeight();
    }

    /** A block the puddle is allowed to overrun: natural worldgen ground, never fog moss itself. */
    public static boolean isNatural(BlockState state) {
        if (state.is(ModBlocks.FOG_MOSS.get())) {
            return false;
        }
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE);
    }

    /**
     * Grow a puddle of the given strength on the ground at or just below {@code from}. No-op if there is
     * no natural surface there or the strength rounds to zero blocks.
     */
    public static void puddleAt(ServerLevel level, BlockPos from, double strength) {
        int budget = (int) Math.round(strength * Config.FOG_MOSS_SIZE_PER_STRENGTH.getAsInt());
        if (budget <= 0) {
            return;
        }
        BlockPos seed = findSurfaceDown(level, from);
        if (seed == null) {
            return;
        }
        spread(level, seed, budget);
    }

    // Scan down from `from` for the first natural surface block (natural, with a non-solid block above).
    private static BlockPos findSurfaceDown(ServerLevel level, BlockPos from) {
        BlockPos.MutableBlockPos p = from.mutable();
        while (p.getY() >= level.getMinBuildHeight()) {
            if (isSurface(level, p)) {
                return p.immutable();
            }
            p.move(Direction.DOWN);
        }
        return null;
    }

    // A natural block whose top is exposed (the block above can be replaced / is air), i.e. a surface.
    private static boolean isSurface(ServerLevel level, BlockPos pos) {
        if (!isNatural(level.getBlockState(pos))) {
            return false;
        }
        BlockState above = level.getBlockState(pos.above());
        return above.isAir() || above.canBeReplaced();
    }

    // Surface-hugging flood-fill: replace natural surface blocks with fog moss until the budget runs out.
    private static void spread(ServerLevel level, BlockPos seed, int budget) {
        BlockState moss = ModBlocks.FOG_MOSS.get().defaultBlockState();
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> seenColumns = new HashSet<>();
        queue.add(seed);
        seenColumns.add(column(seed));
        int placed = 0;
        while (!queue.isEmpty() && placed < budget) {
            BlockPos pos = queue.poll();
            if (!isNatural(level.getBlockState(pos))) {
                continue; // terrain changed under us / not eligible
            }
            level.setBlock(pos, moss, Block.UPDATE_ALL);
            placed++;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(dir);
                long col = column(neighbour);
                if (seenColumns.contains(col)) {
                    continue;
                }
                BlockPos surface = surfaceNear(level, neighbour, pos.getY());
                if (surface != null) {
                    seenColumns.add(col);
                    queue.add(surface);
                }
            }
        }
    }

    // Find the surface in this column near refY, allowing a one-block step to follow sloping terrain.
    private static BlockPos surfaceNear(ServerLevel level, BlockPos colPos, int refY) {
        for (int dy : new int[] {0, 1, -1}) {
            BlockPos candidate = new BlockPos(colPos.getX(), refY + dy, colPos.getZ());
            if (isSurface(level, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // Pack the (x, z) column into a long so the flood-fill visits each column at most once.
    private static long column(BlockPos pos) {
        return (pos.getX() & 0xFFFFFFFFL) | ((long) pos.getZ() << 32);
    }
}
