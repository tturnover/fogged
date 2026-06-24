package com.fogged.block;

import com.fogged.registry.ModBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Lives on a base {@link FogDetectorBlock}. The redstone strength itself is computed on demand in
 * {@link FogDetectorBlock#getSignal}; this entity exists only to drive re-evaluation: the fog plane
 * height drifts with the day, so redstone needs a nudge when the submerged fraction changes. Every
 * {@link #INTERVAL} ticks it recomputes the column power and, on change, notifies neighbours.
 */
public class FogDetectorBlockEntity extends BlockEntity {
    private static final long INTERVAL = 8L;

    private int lastPower = -1;

    public FogDetectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FOG_DETECTOR.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, FogDetectorBlockEntity be) {
        if ((level.getGameTime() % INTERVAL) != 0L
                || !(state.getBlock() instanceof FogDetectorBlock)
                || !FogDetectorBlock.isBase(level, pos, state)) {
            return;
        }
        int power = FogDetectorBlock.columnPower(level, pos, state);
        if (power != be.lastPower) {
            be.lastPower = power;
            level.updateNeighborsAt(pos, state.getBlock());
            // Propagate through the block it rests on, so strong power conducts.
            Direction support = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION).getOpposite();
            level.updateNeighborsAt(pos.relative(support), state.getBlock());
        }
    }
}
