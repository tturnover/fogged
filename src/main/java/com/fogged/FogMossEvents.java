package com.fogged;

import com.fogged.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the {@link FogMoss} behaviour. Server-side only.
 *
 * <ul>
 *   <li>A throttled scan of the rot band below the fog plane (around each player) tills farmland back
 *       to dirt — clearing whatever was planted on it — and kills leaves, flowers and grass, leaving a
 *       fog-moss puddle where each plant stood.</li>
 *   <li>A mob dying inside the band leaves a (larger) puddle where it fell.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FogMossEvents {
    private FogMossEvents() {}

    // The whole area around each player is swept once per this many ticks, but only one x-stripe of it
    // is processed each tick (see onLevelTick), so the cost is spread out instead of spiking TPS.
    private static final int SCAN_PERIOD = 40;

    // Whether Sable (physics sub-levels) is present, so we also rot blocks riding ships/contraptions.
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!Config.FOG_MOSS_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Sweep one x-stripe of the area this tick; over SCAN_PERIOD ticks every column is covered.
        int phase = (int) (level.getGameTime() % SCAN_PERIOD);
        int top = FogMoss.activeTopY(level);
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

        // Same rot for blocks riding Sable sub-levels (ships / contraptions) under the plane, but no
        // moss — moss only puddles on the world ground. Run once per sweep to keep it cheap.
        if (SABLE && phase == 0) {
            double activeSurfaceY = FogMoss.surfaceY(level) - Config.FOG_MOSS_SKIP.getAsInt();
            SableFoam.rotSubLevels(level, activeSurfaceY, (subLevel, p) -> rot(subLevel, p, false));
        }
    }

    /**
     * Rot a chunk's whole band when it is sent to a player — i.e. it has entered that player's render
     * distance — so terrain is already scoured by the time they see it. Firing after generation (not
     * during it) avoids reentrant edits on world-gen. The periodic tick scan ({@link #onLevelTick})
     * then keeps it rotted as vegetation regrows or is replanted.
     */
    @SubscribeEvent
    static void onChunkWatch(ChunkWatchEvent.Sent event) {
        if (!Config.FOG_MOSS_ENABLED.get()) {
            return;
        }
        ServerLevel level = event.getLevel();
        int top = FogMoss.activeTopY(level);
        int bottom = level.getMinBuildHeight();
        ChunkPos cp = event.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                rotColumn(level, pos, x, z, top, bottom);
            }
        }
    }

    // Rot the active fog band of a single x/z column, top down. World blocks leave a moss puddle.
    private static void rotColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                  int x, int z, int top, int bottom) {
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            if (level.isLoaded(pos)) {
                rot(level, pos, true);
            }
        }
    }

    // Horizontal reach in blocks: the server's SIMULATION distance (in chunks) converted to blocks.
    // Vegetation only regrows / random-ticks within the simulation range, so that is all the maintenance
    // sweep must cover; chunks entering view distance are scoured once on arrival by onChunkWatch.
    private static int scanRadius(ServerLevel level) {
        return level.getServer().getPlayerList().getSimulationDistance() * 16;
    }

    /**
     * Apply the under-fog rot to one block. {@code spawnMoss} leaves a fog-moss puddle when vegetation
     * is killed (world ground); sub-level blocks pass {@code false} so ships are scoured but not mossed.
     */
    private static void rot(Level level, BlockPos pos, boolean spawnMoss) {
        BlockState state = level.getBlockState(pos);

        // Submerged under the fog behaves like underwater: snuff fire, freeze lava, drown torches.
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
            if (spawnMoss && level instanceof ServerLevel server) {
                FogMoss.puddleAt(server, pos.below(), Config.FOG_MOSS_STRENGTH_LEAVES);
            }
            return;
        }

        if (isPlant(state)) {
            level.destroyBlock(pos, false);
            if (spawnMoss && level instanceof ServerLevel server) {
                FogMoss.puddleAt(server, pos.below(), Config.FOG_MOSS_STRENGTH_PLANT);
            }
        }
    }

    @SubscribeEvent
    static void onLivingDeath(LivingDeathEvent event) {
        if (!Config.FOG_MOSS_ENABLED.get()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        if (!FogMoss.inActiveZone(level, mob.getBlockY())) {
            return;
        }
        FogMoss.puddleAt(level, mob.blockPosition().below(), Config.FOG_MOSS_STRENGTH_MOB);
    }

    // Flowers, ferns and tall/short grass that the murk wilts into a puddle.
    private static boolean isPlant(BlockState state) {
        return state.is(BlockTags.FLOWERS)
                || state.is(Blocks.SHORT_GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN);
    }

    // Anything growing on tilled soil: crops, stems and other small plants/bushes.
    private static boolean isFarmPlant(BlockState state) {
        return state.getBlock() instanceof CropBlock
                || state.getBlock() instanceof StemBlock
                || state.getBlock() instanceof BushBlock
                || isPlant(state);
    }
}
