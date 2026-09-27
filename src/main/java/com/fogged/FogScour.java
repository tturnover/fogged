package com.fogged;

import java.util.Map;
import java.util.WeakHashMap;

import com.fogged.registry.ModParticles;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.StemBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Drives the under-fog "drowned world" scour: below the fog plane the murk snuffs fire, freezes lava,
 * drowns torches and unmakes farmland, as if the world were underwater. Server-side only, gated by
 * {@link Config#ENABLE_WORLD_CHANGES}. The block-Y range it acts on comes from {@link FogBand}.
 *
 * <p>What happens to the vegetation, and to the ground it grows in, is config: the leaves, flowers,
 * grasses and berry bushes are {@link Config#SCOURED_BLOCKS} defaults, and grass block going back to
 * coarse dirt is a {@link Config#TRANSFORMS} default. They ran from here until a pack had reason to
 * argue with one of them; {@link ScourRules} applies them before any of the rules below.
 *
 * <p>Terrain is worked the moment it is sent to a player, so it arrives already scoured. Anything that
 * appears under the murk after that -- placed, grown, spread -- is found by a periodic sweep or one of
 * the block events, and rather than vanish on the spot it steams from its sides for
 * {@link Config#WORLD_CHANGE_DELAY} and then turns. Fire and the devices go out at once.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FogScour {
    private FogScour() {}

    // The whole area around each player is swept once per this many ticks, one slice of its chunks
    // per tick, so the cost is spread out instead of spiking TPS.
    private static final int SCAN_PERIOD = 40;

    // Whether Sable (physics sub-levels) is present, so we also scour blocks riding ships/contraptions.
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    // Blocks found by the sweep or an event and not yet turned: packed position -> ticks left. Per
    // level, dropped with it.
    private static final Map<ServerLevel, Long2IntOpenHashMap> pending = new WeakHashMap<>();

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!Config.ENABLE_WORLD_CHANGES.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level) || !Config.dimensionEnabled(level)) {
            return;
        }
        tickPending(level);

        int phase = (int) (level.getGameTime() % SCAN_PERIOD);
        int top = FogBand.activeTopY(level);
        int radius = level.getServer().getPlayerList().getSimulationDistance();
        int side = 2 * radius + 1;
        for (ServerPlayer player : level.players()) {
            ChunkPos at = player.chunkPosition();
            // Every chunk of the square gets an index; this tick takes every SCAN_PERIOD-th of them.
            for (int i = phase; i < side * side; i += SCAN_PERIOD) {
                scanChunk(level, at.x - radius + i % side, at.z - radius + i / side, top, false);
            }
        }

        // Same scour for blocks riding Sable sub-levels (ships / contraptions) under the plane. Run once
        // per sweep to keep it cheap.
        if (SABLE && phase == 0) {
            double activeSurfaceY = FogBand.surfaceY(level) - Config.WORLD_CHANGE_SKIP.getAsInt();
            SableCompatibility.rotSubLevels(level, activeSurfaceY, FogScour::rot);
        }
    }

    /**
     * Scour a chunk's whole band when it is sent to a player — i.e. it has entered that player's render
     * distance — so terrain is already scoured by the time they see it. Firing after generation (not
     * during it) avoids reentrant edits on world-gen. The periodic sweep ({@link #onLevelTick}) then
     * keeps it scoured as vegetation regrows or is replanted.
     */
    @SubscribeEvent
    static void onChunkWatch(ChunkWatchEvent.Sent event) {
        if (!Config.ENABLE_WORLD_CHANGES.get()) {
            return;
        }
        ServerLevel level = event.getLevel();
        if (!Config.dimensionEnabled(level)) {
            return;
        }
        scanChunk(level, event.getPos().x, event.getPos().z, FogBand.activeTopY(level), true);
    }

    // Something set down under the murk is noticed on the spot rather than on the sweep's next pass.
    @SubscribeEvent
    static void onPlace(BlockEvent.EntityPlaceEvent event) {
        notice(event.getLevel(), event.getPos(), event.getPlacedBlock());
    }

    @SubscribeEvent
    static void onGrow(CropGrowEvent.Post event) {
        notice(event.getLevel(), event.getPos(), event.getState());
    }

    private static void notice(LevelAccessor accessor, BlockPos pos, BlockState state) {
        if (!(accessor instanceof ServerLevel level) || !Config.ENABLE_WORLD_CHANGES.get()
                || !Config.dimensionEnabled(level)) {
            return;
        }
        if (pos.getY() > FogBand.activeTopY(level)) {
            return;
        }
        if (Config.ENABLE_EXTINGUISH.get() && extinguishable(state)) {
            extinguish(level, pos, state);
        } else if (wantsSlow(level, state, pos.getY())) {
            schedule(level, pos);
        }
    }

    // Walk the chunk's band section by section. A section whose palette holds nothing the murk has a
    // word for is skipped whole -- deep stone and deepslate, which is most of the band. At once, or
    // for the slow path (see tickPending), what is found is only marked.
    private static void scanChunk(ServerLevel level, int cx, int cz, int top, boolean immediate) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
        if (chunk == null) {
            return;
        }
        if (top < chunk.getMinBuildHeight()) {
            return;
        }
        boolean extinguish = Config.ENABLE_EXTINGUISH.get();
        LevelChunkSection[] sections = chunk.getSections();
        int topIndex = Math.min(chunk.getSectionIndex(top), sections.length - 1);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int index = topIndex; index >= 0; index--) {
            LevelChunkSection section = sections[index];
            if (section.hasOnlyAir() || !section.maybeHas(FogScour::mayTouch)) {
                continue;
            }
            int y0 = chunk.getSectionYFromSectionIndex(index) << 4;
            int yMax = Math.min(15, top - y0);
            for (int y = yMax; y >= 0; y--) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (!mayTouch(state)) {
                            continue;
                        }
                        pos.set((cx << 4) + x, y0 + y, (cz << 4) + z);
                        if (immediate) {
                            rot(level, pos);
                        } else if (extinguish && extinguishable(state)) {
                            extinguish(level, pos, state);
                        } else if (wantsSlow(level, state, pos.getY())) {
                            schedule(level, pos);
                        }
                    }
                }
            }
        }
    }

    // Count the marked blocks down, steaming them meanwhile, and turn each when its time is up. One
    // that has changed into something the murk has no word for in the meantime is let go.
    private static void tickPending(ServerLevel level) {
        Long2IntOpenHashMap marks = pending.get(level);
        if (marks == null || marks.isEmpty()) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        ParticleOptions steam = steam();
        RandomSource rand = level.random;
        ObjectIterator<Long2IntMap.Entry> it = marks.long2IntEntrySet().fastIterator();
        while (it.hasNext()) {
            Long2IntMap.Entry entry = it.next();
            pos.set(entry.getLongKey());
            if (!level.hasChunkAt(pos)) {
                it.remove();
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (pos.getY() > FogBand.activeTopY(level) || !wantsSlow(level, state, pos.getY())) {
                it.remove();
                continue;
            }
            int left = entry.getIntValue() - 1;
            if (left > 0) {
                entry.setValue(left);
                steamSides(level, pos, steam, rand);
                continue;
            }
            it.remove();
            rot(level, pos);
        }
    }

    private static void schedule(ServerLevel level, BlockPos pos) {
        int delay = Config.WORLD_CHANGE_DELAY.getAsInt() * 20;
        if (delay <= 0) {
            rot(level, pos);
            return;
        }
        pending.computeIfAbsent(level, l -> new Long2IntOpenHashMap()).putIfAbsent(pos.asLong(), delay);
    }

    /** The murk's steam: the nozzle filter's puff in the foam colour (see NozzleFilterBlockEntity). */
    static ParticleOptions steam() {
        float[] c = Config.foamColor();
        return ColorParticleOption.create(ModParticles.PUFF.get(), c[0], c[1], c[2]);
    }

    // A wisp off one side of the block, drifting up along its face.
    private static void steamSides(ServerLevel level, BlockPos pos, ParticleOptions steam, RandomSource rand) {
        if (rand.nextInt(2) != 0) {
            return; // sparse, as the filter's own emission is
        }
        Direction side = Direction.from2DDataValue(rand.nextInt(4));
        double along = rand.nextDouble();
        double x = pos.getX() + 0.5 + side.getStepX() * 0.55 + side.getStepZ() * (along - 0.5);
        double z = pos.getZ() + 0.5 + side.getStepZ() * 0.55 + side.getStepX() * (along - 0.5);
        double y = pos.getY() + rand.nextDouble();
        level.sendParticles(steam, x, y, z, 0, 0.0, 0.04 + rand.nextDouble() * 0.03, 0.0, 1.0);
    }

    // Everything the sweep ever stops on: what the config names, and the built-in fire and farmland.
    private static boolean mayTouch(BlockState state) {
        return ScourRules.mayTouch(state) || extinguishable(state) || state.is(Blocks.FARMLAND);
    }

    // What takes the slow path: a transform, a scoured block, or farmland. Fire is not slow.
    private static boolean wantsSlow(Level level, BlockState state, int y) {
        return ScourRules.wants(level, state, y)
                || (Config.ENABLE_SCOUR.get() && state.is(Blocks.FARMLAND));
    }

    private static boolean extinguishable(BlockState state) {
        return state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE) || state.is(Blocks.LAVA)
                || state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)
                || FogSnuff.isDevice(state) || BurntCompatibility.isBurning(state);
    }

    /** Apply the under-fog scour to one block: the config's rules first, then fire, lava, farmland. */
    private static void rot(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        // The config's rules first: they are where the vegetation and the ground it grows in are
        // decided, and an entry there overrides what the murk would otherwise do to that block.
        if (ScourRules.apply(level, pos, state)) {
            return;
        }

        if (Config.ENABLE_EXTINGUISH.get()) {
            if (extinguish(level, pos, state)) {
                return;
            }
        }
        if (Config.ENABLE_SCOUR.get() && state.is(Blocks.FARMLAND)) {
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), Block.UPDATE_ALL);
            BlockPos above = pos.above();
            if (isFarmPlant(level.getBlockState(above))) {
                level.destroyBlock(above, false); // remove whatever was planted on it
            }
        }
    }

    // Everything the murk puts out: loose fire, lava, torches and the configured devices. Returns true
    // when this block was one of them and is dealt with.
    private static boolean extinguish(Level level, BlockPos pos, BlockState state) {
        if (state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)) {
            level.removeBlock(pos, false);
            return true;
        }
        if (state.is(Blocks.LAVA)) {
            BlockState frozen = state.getFluidState().isSource()
                    ? Blocks.OBSIDIAN.defaultBlockState()
                    : Blocks.COBBLESTONE.defaultBlockState();
            level.setBlock(pos, frozen, Block.UPDATE_ALL);
            level.levelEvent(1501, pos, 0); // lava-extinguish fizz
            return true;
        }
        if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.SOUL_TORCH) || state.is(Blocks.SOUL_WALL_TORCH)) {
            level.destroyBlock(pos, true);
            return true;
        }
        // Fire-burning devices (furnaces, campfires, burners, engines) are drowned rather than broken.
        if (FogSnuff.isDevice(state)) {
            FogSnuff.snuff(level, pos, state);
            return true;
        }
        // Burnt's flames and whatever it has burning, put out its own way (see BurntCompatibility).
        if (BurntCompatibility.isBurning(state)) {
            BurntCompatibility.extinguish(level, pos, state);
            return true;
        }
        return false;
    }

    // The small plants a farm grows, kept only for the farmland rule above -- what the murk wilts in
    // general is scouredBlocks now, and it runs before any of this.
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
