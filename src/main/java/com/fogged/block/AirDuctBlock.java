package com.fogged.block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fogged.AirFill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * An air duct: carries an air compressor's output away from it, to come out somewhere else. Placed
 * like a log -- along the axis of the face it is set against -- so a duct set on a compressor's outlet
 * runs straight out of it.
 *
 * <p>Two ducts side by side connect when either points at the other: end to end is a straight run,
 * and a duct whose end meets another's side is a branch off it, so a network can T and cross. Two
 * parallel ducts lying side by side do not connect. The air leaves by every open end -- an end facing
 * anything but a connected duct or a compressor -- and the room fills from there (see
 * {@link #ends}). A duct is a full block, so it is also part of whatever wall it runs through.
 */
public class AirDuctBlock extends RotatedPillarBlock {

    /** Most duct cells one compressor face's network walks, so a vast tangle cannot stall a tick. */
    public static final int MAX_NETWORK = 512;

    public AirDuctBlock(Properties properties) {
        super(properties);
    }

    private static boolean isDuct(BlockState state) {
        return state.getBlock() instanceof AirDuctBlock;
    }

    /** Whether ducts at {@code a} and {@code a.relative(dir)} connect: either points at the other. */
    private static boolean connects(BlockState a, Direction dir, BlockState b) {
        return isDuct(a) && isDuct(b)
                && (a.getValue(AXIS) == dir.getAxis() || b.getValue(AXIS) == dir.getAxis());
    }

    /**
     * The open ends of the duct network that starts at {@code start}, entered from a compressor going
     * {@code from}: where the air comes out, each with the way it is travelling. Empty when
     * {@code start} is not a duct pointing back at the compressor -- the air then goes straight out
     * of the outlet. Every duct cell walked is added to {@code walked}, for the room to watch.
     */
    public static List<AirFill.Seed> ends(Level level, BlockPos start, Direction from, Set<BlockPos> walked) {
        List<AirFill.Seed> out = new ArrayList<>();
        BlockState first = level.getBlockState(start);
        if (!isDuct(first) || first.getValue(AXIS) != from.getAxis()) {
            return out;
        }
        Set<BlockPos> seen = new HashSet<>();
        List<BlockPos> queue = new ArrayList<>();
        queue.add(start.immutable());
        seen.add(start.immutable());
        for (int i = 0; i < queue.size(); i++) {
            BlockPos at = queue.get(i);
            BlockState here = level.getBlockState(at);
            Direction.Axis axis = here.getValue(AXIS);
            for (Direction d : Direction.values()) {
                BlockPos next = at.relative(d);
                if (seen.contains(next) || !level.isLoaded(next)) {
                    continue;
                }
                BlockState there = level.getBlockState(next);
                if (connects(here, d, there)) {
                    if (seen.size() < MAX_NETWORK) {
                        seen.add(next.immutable());
                        queue.add(next.immutable());
                    }
                } else if (d.getAxis() == axis && !(there.getBlock() instanceof AirCompressorBlock)) {
                    out.add(new AirFill.Seed(next.immutable(), d)); // an open end: the air comes out here
                }
            }
        }
        walked.addAll(seen);
        return out;
    }
}
