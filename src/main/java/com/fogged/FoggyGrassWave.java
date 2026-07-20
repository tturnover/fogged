package com.fogged;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;

import com.fogged.block.FoggyGrassBlock;
import com.fogged.block.FoggyGrassSideBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * A sculk-catalyst-style charge wave that plants {@link FoggyGrassBlock foggy grass} across fog moss:
 * planting ripples outward from a source over successive ticks. A {@link #bloom} is the big one-shot wave
 * when a {@link FogMoss} puddle forms; a {@link #spark} is the one-ring wave a mature tuft throws as it
 * creeps. State is a per-level frontier drained by {@link #tick}, transient by design — it only ever
 * plants the grass the moss would have grown anyway.
 *
 * <p>Where the moss runs out the wave does not simply stop: it carries a second, small budget of
 * {@link Config#FOGGY_GRASS_FRINGE_RINGS fringe rings} that let it step a couple of blocks onto the bare
 * ground beyond, planting {@link FoggyGrassSideBlock side tufts} on whatever walls it meets there. That is
 * what keeps a puddle from ending in a hard edge — the growth climbs the cliff or cellar wall it ran into.
 */
public final class FoggyGrassWave {
    private FoggyGrassWave() {}

    private static final int MAX_PER_TICK = 64;

    /**
     * One column the wave is due to plant.
     *
     * <p>{@code ringsLeft} is the moss budget — how much further the wave may walk across fog moss.
     * {@code fringeLeft} is the separate budget for stepping off the moss onto bare ground, where the
     * wave plants the side variant on whatever walls it finds; {@code fringe} marks a charge that has
     * already left the moss. `visited` is shared by reference across a single wave's charges, so each
     * wave dedupes its own columns.
     */
    private record Charge(BlockPos pos, long dueTick, int ringsLeft, int fringeLeft,
            boolean bloom, boolean fringe, Set<Long> visited) {}

    // WeakHashMap so an unloaded level's queue is collectable.
    private static final Map<ServerLevel, PriorityQueue<Charge>> FRONTIERS = new WeakHashMap<>();

    /**
     * Kick off a bloom over the puddle at {@code seedMoss}, its reach scaling with the puddle's block
     * {@code budget}. When {@code instant} the whole bloom resolves at once (a freshly-revealed chunk comes
     * into view already grassed); otherwise it ripples out over the following ticks.
     */
    public static void bloom(ServerLevel level, BlockPos seedMoss, int budget, boolean instant) {
        BlockPos top = FoggyGrassBlock.mossTopNear(level, seedMoss, seedMoss.getY() + 1);
        if (top == null) {
            return;
        }
        int rings = (int) Math.ceil(Math.sqrt(Math.max(1, budget)));
        int fringe = Config.FOGGY_GRASS_FRINGE_RINGS.getAsInt();
        Set<Long> visited = new HashSet<>();
        visited.add(column(top));
        if (instant) {
            floodNow(level, new Charge(top, 0L, rings, fringe, true, false, visited));
            return;
        }
        enqueue(level, new Charge(top, level.getGameTime(), rings, fringe, true, false, visited));
        level.playSound(null, seedMoss, SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.BLOCKS, 0.6F, 1.2F);
    }

    private static void floodNow(ServerLevel level, Charge start) {
        ArrayDeque<Charge> q = new ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            Charge c = q.poll();
            plant(level, c, true);
            expand(level, c, 0L, q::add);
        }
    }

    /** Throw a one-ring spark off the tuft at {@code tuftPos} onto the surrounding bare moss. */
    public static void spark(ServerLevel level, BlockPos tuftPos) {
        Set<Long> visited = new HashSet<>();
        visited.add(column(tuftPos));
        enqueue(level, new Charge(tuftPos, level.getGameTime(), 1,
                Config.FOGGY_GRASS_FRINGE_RINGS.getAsInt(), false, false, visited));
    }

    // Plant whatever this charge calls for: side tufts once the wave has left the moss, otherwise the
    // upright grass — seeded thinly for a bloom, density-capped for a spark. A live wave never
    // fast-forwards a bush (instant=false).
    private static void plant(ServerLevel level, Charge c, boolean instant) {
        if (c.fringe) {
            FoggyGrassSideBlock.tryPlantSide(level, c.pos, level.random, !c.bloom);
        } else if (c.bloom) {
            FoggyGrassBlock.tryPlantSeeded(level, c.pos, level.random, instant);
        } else {
            FoggyGrassBlock.tryPlantSpread(level, c.pos, level.random, instant);
        }
    }

    // Hand each horizontal neighbour to `sink` as the next charge. A charge still on the moss prefers
    // to stay on it; where the moss (or the ring budget) runs out it fringes onto bare ground instead,
    // and a fringe charge only ever spends its fringe budget.
    private static void expand(ServerLevel level, Charge c, long dueTick, java.util.function.Consumer<Charge> sink) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos neighbour = c.pos.relative(dir);
            if (!c.fringe && c.ringsLeft > 0) {
                BlockPos nextTop = FoggyGrassBlock.mossTopNear(level, neighbour, c.pos.getY());
                if (nextTop != null) {
                    if (c.visited.add(column(nextTop))) {
                        sink.accept(new Charge(nextTop, dueTick, c.ringsLeft - 1, c.fringeLeft,
                                c.bloom, false, c.visited));
                    }
                    continue;
                }
            }
            if (c.fringeLeft > 0) {
                BlockPos spot = FoggyGrassSideBlock.fringeSpotNear(level, neighbour, c.pos.getY());
                if (spot != null && c.visited.add(column(spot))) {
                    sink.accept(new Charge(spot, dueTick, 0, c.fringeLeft - 1, c.bloom, true, c.visited));
                }
            }
        }
    }

    private static void enqueue(ServerLevel level, Charge charge) {
        FRONTIERS.computeIfAbsent(level, l -> new PriorityQueue<>((a, b) -> Long.compare(a.dueTick, b.dueTick)))
                .add(charge);
    }

    /** Resolve every charge now due for {@code level}, up to {@link #MAX_PER_TICK}. */
    public static void tick(ServerLevel level) {
        PriorityQueue<Charge> frontier = FRONTIERS.get(level);
        if (frontier == null || frontier.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        int step = Config.FOGGY_GRASS_WAVE_STEP.getAsInt();
        int resolved = 0;
        while (resolved < MAX_PER_TICK && !frontier.isEmpty() && frontier.peek().dueTick <= now) {
            Charge c = frontier.poll();
            resolved++;
            plant(level, c, false);
            expand(level, c, now + step, frontier::add);
        }
        if (frontier.isEmpty()) {
            FRONTIERS.remove(level);
        }
    }

    private static long column(BlockPos pos) {
        return (pos.getX() & 0xFFFFFFFFL) | ((long) pos.getZ() << 32);
    }
}
