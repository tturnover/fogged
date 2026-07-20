package com.fogged.block;

import com.fogged.Config;
import com.fogged.FogMoss;
import com.fogged.registry.ModBlocks;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The puff bush sapling: a rare little tree that the flora plants (in place of foggy grass) on a fog-moss
 * puddle. Left to itself it random-ticks into a small blob of log and leaves shaped like a jungle bush —
 * a single trunk under a low dome of foliage. On a freshly-revealed chunk it is instead grown out on the
 * spot (see {@link #grow}) so the terrain comes into view already bushy, bar the odd sapling left unsprung.
 */
public class PuffBushSaplingBlock extends BushBlock {
    public static final MapCodec<PuffBushSaplingBlock> CODEC = simpleCodec(PuffBushSaplingBlock::new);

    public PuffBushSaplingBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BushBlock> codec() {
        return CODEC;
    }

    @Override
    protected boolean mayPlaceOn(BlockState state, BlockGetter level, BlockPos pos) {
        return FogMoss.isFloraSoil(state);
    }

    @Override
    public boolean canSurvive(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos pos) {
        return level.getFluidState(pos).isEmpty() && super.canSurvive(state, level, pos);
    }

    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!Config.FLORA_ENABLED.get()) {
            return;
        }
        if (random.nextDouble() < Config.PUFF_BUSH_GROW_CHANCE.get()) {
            grow(level, pos, random);
        }
    }

    /**
     * Grow the sapling at {@code pos} into a jungle-bush-shaped blob: a trunk log under a broad canopy of
     * leaves, laid only into free space. Surrounding blocks never obstruct it — it simply fills what fits.
     */
    public static boolean grow(ServerLevel level, BlockPos pos, RandomSource random) {
        // Vertically symmetric blob: narrow, broad, narrow about the middle layer (level with the trunk).
        leafLayer(level, pos.below(), 1);
        leafLayer(level, pos, 2);
        leafLayer(level, pos.above(), 1);

        // Trunk LAST: leaves are laid with the default (max) decay distance, and it is the log landing next
        // to them that fires the neighbour-shape update recomputing and cascading that distance out. Placed
        // first, the leaves never get that update, read as detached, and decay away.
        BlockState log = ModBlocks.PUFF_BUSH_LOG.get().defaultBlockState()
                .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Y);
        level.setBlock(pos, log, Block.UPDATE_ALL);

        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, 0);
        return true;
    }

    private static void leafLayer(ServerLevel level, BlockPos centre, int radius) {
        BlockState leaves = ModBlocks.PUFF_BUSH_LEAVES.get().defaultBlockState();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.abs(dx) == radius && Math.abs(dz) == radius) {
                    continue; // clip corners into a dome
                }
                BlockPos at = centre.offset(dx, 0, dz);
                if (canReplace(level, at)) {
                    level.setBlock(at, leaves, Block.UPDATE_ALL);
                }
            }
        }
    }

    private static boolean canReplace(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Foggy grass is overgrown by the bush rather than left poking through it.
        return (state.isAir() || state.canBeReplaced() || state.is(ModBlocks.FOGGY_GRASS.get()))
                && level.getFluidState(pos).isEmpty();
    }
}
