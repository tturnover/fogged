package com.fogged.block;

import com.fogged.VolumeFog;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Test block for {@link VolumeFog}: breathes out half a cell of fog every {@link #INTERVAL} ticks into
 * the nearest cell with room -- next to it, or beyond the fog it has already made (see
 * {@link VolumeFog#inject}) -- unless powered by redstone. Its fog then flows away by itself.
 */
public class FogEmitterBlock extends Block {

    public static final int INTERVAL = 8;
    /** How many cells of its own fog it pushes through to find room. */
    public static final int REACH = 512;

    public FogEmitterBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState old, boolean moving) {
        super.onPlace(state, level, pos, old, moving);
        if (!level.isClientSide && !old.is(this)) {
            level.scheduleTick(pos, this, INTERVAL);
        }
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        level.scheduleTick(pos, this, INTERVAL);
        if (level.hasNeighborSignal(pos)) {
            return;
        }
        VolumeFog.inject(level, pos, REACH);
    }
}
