package com.fogged;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the under-fog "drowned world" scour: below the fog plane the murk snuffs fire, freezes lava,
 * drowns torches and wilts plants / crops / leaves, as if the world were underwater. Server-side only,
 * gated by {@link Config#SUBMERGE_WORLD}. The block-Y range it acts on comes from {@link FogBand}.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FogScour {
    private FogScour() {}

    // The whole area around each player is swept once per this many ticks, but only one x-stripe of it
    // is processed each tick (see onLevelTick), so the cost is spread out instead of spiking TPS.
    private static final int SCAN_PERIOD = 40;

    // Whether Sable (physics sub-levels) is present, so we also scour blocks riding ships/contraptions.
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!Config.SUBMERGE_WORLD.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Sweep one x-stripe of the area this tick; over SCAN_PERIOD ticks every column is covered.
        int phase = (int) (level.getGameTime() % SCAN_PERIOD);
        int top = FogBand.activeTopY(level);
        int bottom = level.getMinBuildHeight();
        int radius = scanRadius(level);
        int stripe = (2 * radius + 1 + SCAN_PERIOD - 1) / SCAN_PERIOD;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (ServerPlayer player : level.players()) {
            int cx = player.getBlockX();
            int cz = player.getBlockZ();
            int x0 = cx - radius + phase * stripe;
            int x1 = Math.min(cx + radius, x0 + stripe - 1);
            for (int x = x0; x <= x1; x++) {
                for (int z = cz - radius; z <= cz + radius; z++) {
                    rotColumn(level, pos, x, z, top, bottom);
                }
            }
        }

        // Same scour for blocks riding Sable sub-levels (ships / contraptions) under the plane. Run once
        // per sweep to keep it cheap.
        if (SABLE && phase == 0) {
            double activeSurfaceY = FogBand.surfaceY(level) - Config.SUBMERGE_SKIP.getAsInt();
            SableCompatibility.rotSubLevels(level, activeSurfaceY, FogScour::rot);
        }
    }

    /**
     * Scour a chunk's whole band when it is sent to a player — i.e. it has entered that player's render
     * distance — so terrain is already scoured by the time they see it. Firing after generation (not
     * during it) avoids reentrant edits on world-gen. The periodic tick scan ({@link #onLevelTick})
     * then keeps it scoured as vegetation regrows or is replanted.
     */
    @SubscribeEvent
    static void onChunkWatch(ChunkWatchEvent.Sent event) {
        if (!Config.SUBMERGE_WORLD.get()) {
            return;
        }
        ServerLevel level = event.getLevel();
        int top = FogBand.activeTopY(level);
        int bottom = level.getMinBuildHeight();
        ChunkPos cp = event.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                rotColumn(level, pos, x, z, top, bottom);
            }
        }
    }

    // Scour the active fog band of a single x/z column, top down.
    private static void rotColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                  int x, int z, int top, int bottom) {
        // Load state is per-CHUNK, so check the column once instead of every block in the deep band --
        // this is the hottest loop of the per-tick sweep. If the chunk is absent, the whole column is.
        if (!level.hasChunkAt(x, z)) {
            return;
        }
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            rot(level, pos);
        }
    }

    /** Apply the under-fog scour to one block: snuff fire, freeze lava, drown torches, wilt vegetation. */
    private static void rot(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
            level.removeBlock(pos, false);
            return;
        }
        if (state.is(Blocks.LAVA)) {
            BlockState frozen = state.getFluidState().isSource()
                    ? Blocks.OBSIDIAN.defaultBlockState()
                    : Blocks.COBBLESTONE.defaultBlockState();
            level.setBlock(pos, frozen, Block.UPDATE_ALL);
            level.levelEvent(1501, pos, 0); // lava-extinguish fizz
            return;
        }
        if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)) {
            level.destroyBlock(pos, true);
            return;
        }
        // Fire-burning devices (furnaces, campfires, burners, engines) are drowned rather than broken.
        if (FogSnuff.isDevice(state)) {
            FogSnuff.snuff(level, pos, state);
            return;
        }

        // Grass blocks die back to coarse dirt: the sward is the first thing the murk takes, and coarse
        // dirt will not spread or regrow the way plain dirt re-grasses from a lit neighbour.
        if (state.is(Blocks.GRASS_BLOCK)) {
            level.setBlock(pos, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
            return;
        }

        if (state.is(Blocks.FARMLAND)) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos above = pos.above();
            if (isFarmPlant(level.getBlockState(above))) {
                level.destroyBlock(above, false); // remove whatever was planted on it
            }
            return;
        }

        if (state.is(BlockTags.LEAVES)) {
            level.destroyBlock(pos, false);
            return;
        }

        if (isPlant(state)) {
            level.destroyBlock(pos, false);
        }
    }

    // Horizontal reach in blocks: the server's SIMULATION distance (in chunks) converted to blocks.
    // Vegetation only regrows / random-ticks within the simulation range, so that is all the maintenance
    // sweep must cover; chunks entering view distance are scoured once on arrival by onChunkWatch.
    private static int scanRadius(ServerLevel level) {
        return level.getServer().getPlayerList().getSimulationDistance() * 16;
    }

    // Flowers, ferns, tall/short grass and sweet berry bushes that the murk wilts.
    private static boolean isPlant(BlockState state) {
        return state.is(BlockTags.FLOWERS)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SWEET_BERRY_BUSH);
    }

    // Anything growing on tilled soil: crops, stems and other small plants/bushes.
    private static boolean isFarmPlant(BlockState state) {
        return state.getBlock() instanceof CropBlock
                || state.getBlock() instanceof StemBlock
                || state.getBlock() instanceof BushBlock
                || isPlant(state);
    }
}
