package com.fogged;

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
 * Drives the under-fog "drowned world" scour and the {@link FogMoss} behaviour. Server-side only.
 *
 * <p>Two independent behaviours share one scan of the band below the fog plane (around each player):
 * <ul>
 *   <li><b>Submerge the world</b> ({@link Config#SUBMERGE_WORLD}): snuff fire, freeze lava, drown
 *       torches and wilt plants / crops / leaves, like being underwater. Core fog behaviour, not flora.</li>
 *   <li><b>Flora</b> ({@link Config#FLORA_ENABLED}): leave a fog-moss puddle where vegetation is wilted,
 *       and a larger one where a mob dies in the band. Wilting also runs (to seed the moss) when only
 *       flora is on.</li>
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

    // True when the band scan has anything to do: either the world-submerge scour or the flora moss.
    private static boolean scanActive() {
        return Config.SUBMERGE_WORLD.get() || Config.FLORA_ENABLED.get();
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!scanActive()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        // Advance any in-flight foggy-grass bloom / spark waves for this level.
        FoggyGrassWave.tick(level);
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
                    rotColumn(level, pos, x, z, top, bottom, false);
                }
            }
        }

        // Same rot for blocks riding Sable sub-levels (ships / contraptions) under the plane, but no
        // moss — moss only puddles on the world ground. Run once per sweep to keep it cheap.
        if (SABLE && phase == 0) {
            double activeSurfaceY = FogMoss.surfaceY(level) - Config.FOG_MOSS_SKIP.getAsInt();
            SableCompatibility.rotSubLevels(level, activeSurfaceY, (subLevel, p) -> rot(subLevel, p, false, false));
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
        if (!scanActive()) {
            return;
        }
        ServerLevel level = event.getLevel();
        int top = FogMoss.activeTopY(level);
        int bottom = level.getMinBuildHeight();
        ChunkPos cp = event.getPos();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = cp.getMinBlockX(); x <= cp.getMaxBlockX(); x++) {
            for (int z = cp.getMinBlockZ(); z <= cp.getMaxBlockZ(); z++) {
                // Instant: a chunk coming into view arrives already overgrown, not seeding in front of you.
                rotColumn(level, pos, x, z, top, bottom, true);
            }
        }
    }

    // Rot the active fog band of a single x/z column, top down. World blocks leave a moss puddle; when
    // `instant`, that puddle's grass and eyes are grown out on the spot instead of over the next ticks.
    private static void rotColumn(ServerLevel level, BlockPos.MutableBlockPos pos,
                                  int x, int z, int top, int bottom, boolean instant) {
        // Load state is per-CHUNK, so check the column once instead of every block in the deep band --
        // this is the hottest loop of the per-tick sweep. If the chunk is absent, the whole column is.
        if (!level.hasChunkAt(x, z)) {
            return;
        }
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            rot(level, pos, true, instant);
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
    private static void rot(Level level, BlockPos pos, boolean spawnMoss, boolean instant) {
        BlockState state = level.getBlockState(pos);
        boolean submerge = Config.SUBMERGE_WORLD.get();
        boolean flora = Config.FLORA_ENABLED.get();
        // Only flora leaves a moss puddle behind; the caller allows it only for world ground.
        boolean moss = spawnMoss && flora;

        // Underwater environmental effects: core "submerge the world" behaviour, not flora.
        if (submerge) {
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
        }

        // Wilting vegetation runs for the world scour OR to seed flora moss; the puddle needs flora.
        if (!submerge && !flora) {
            return;
        }

        // Grass blocks die back to coarse dirt: the sward is the first thing the murk takes, and coarse
        // dirt will not spread or regrow the way plain dirt re-grasses from a lit neighbour. Coarse dirt
        // is itself flora soil (FogMoss#isNatural, same as plain dirt), so the patch left behind seeds
        // its own small puddle right there instead of only ever catching one that spreads in from a
        // nearby leaf/plant/mob death.
        if (state.is(Blocks.GRASS_BLOCK)) {
            level.setBlock(pos, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
            if (moss && level instanceof ServerLevel server) {
                FogMoss.puddleAt(server, pos, Config.FOG_MOSS_STRENGTH_GRASS, instant);
            }
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
            if (moss && level instanceof ServerLevel server) {
                FogMoss.puddleAt(server, pos.below(), Config.FOG_MOSS_STRENGTH_LEAVES, instant);
            }
            return;
        }

        if (isPlant(state)) {
            level.destroyBlock(pos, false);
            if (moss && level instanceof ServerLevel server) {
                FogMoss.puddleAt(server, pos.below(), Config.FOG_MOSS_STRENGTH_PLANT, instant);
            }
        }
    }

    @SubscribeEvent
    static void onLivingDeath(LivingDeathEvent event) {
        if (!Config.FLORA_ENABLED.get()) {
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

    // Flowers, ferns, tall/short grass and sweet berry bushes that the murk wilts into a puddle.
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
