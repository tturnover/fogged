package com.fogged;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import com.fogged.block.MurkBlock;
import com.fogged.registry.ModBlocks;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.ChunkWatchEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * The murk's visible surface, as real blocks: a layer of {@link MurkBlock}s a few blocks thick laid
 * into every open cell at the breathing boundary, rising and falling round each player like a swell
 * on the sea. It replaces the shader plane, so what the player sees of the murk is drawn by the chunk
 * renderer like any other block -- under Sodium, a shader pack, anything.
 *
 * <p>Only the picture moves. The boundary that is breathed, scoured and suffocated by stays where the
 * config puts it ({@link FogBand}); the layer is centred on it.
 *
 * <p>Heights are counted in half blocks ("halves"), the steps the murk comes in. Every column of a
 * loaded chunk holds murk from {@link #DEPTH} blocks under the calm surface up to its surface, which is
 * the calm surface plus the swell there. The swell is a sum of a few travelling waves, a function of
 * the world clock and the column alone, so it is the same for whoever looks; it runs only within
 * {@link #RADIUS} of a player and dies down toward its edge, and the rest of the world lies calm --
 * every step of a wave is a block change sent to the clients and a chunk section rebuilt, and that is
 * only worth paying for where someone is close enough to see it move.
 *
 * <p>Murk only ever goes into air, and only ever replaces itself: whatever else stands in a cell --
 * stone, water, a slab, a flower -- is left as it is (the fog inside a non-full block is drawn by its
 * model instead). Cells in a sealed room stay clear.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class MurkLayer {

    /** Full blocks of murk under the calm surface's own cell. */
    static final int DEPTH = 3;
    /** Height of the swell either way from calm, in halves. */
    static final int AMPLITUDE = 2;
    /** Blocks from a player within which the swell runs. */
    static final int RADIUS = 40;
    /** The swell dies down over the outer this-many blocks of {@link #RADIUS}. */
    static final int FADE = 16;
    /** Wall time per tick the full passes over chunks may take. */
    private static final long BUDGET_NANOS = 3_000_000L;

    private static final class Layer {
        /** Calm surface in halves the layer was last laid for; {@link Integer#MIN_VALUE} before any. */
        int calm = Integer.MIN_VALUE;
        /** Loaded full chunks of the level. */
        final LongOpenHashSet loaded = new LongOpenHashSet();
        /** Chunks waiting for a full pass: just loaded, the boundary moved, or the swell left them. */
        final LongLinkedOpenHashSet queue = new LongLinkedOpenHashSet();
        /** Per chunk the swell runs in: the offset from calm each column was last laid at. */
        final Long2ObjectOpenHashMap<byte[]> swell = new Long2ObjectOpenHashMap<>();
        /** Chunks within the swell's reach of a player, as of the last tick. */
        LongOpenHashSet near = new LongOpenHashSet();
        /** Columns (packed x, 0, z) where some block in the layer changed and it must be laid again. */
        final LongOpenHashSet dirty = new LongOpenHashSet();
    }

    private static final Map<ServerLevel, Layer> LAYERS = new WeakHashMap<>();

    // Set while this class sets a block itself, so its own changes are not taken for the world's.
    private static boolean writing;

    private static Layer layer(ServerLevel level) {
        return LAYERS.computeIfAbsent(level, l -> new Layer());
    }

    /** Calm surface, in halves: the boundary's visible surface rounded to the nearest half block. */
    static int calmHalves(ServerLevel level) {
        return (int) Math.round(FogBand.surfaceY(level) * 2.0);
    }

    /** Lowest block-Y that holds murk for a calm surface of {@code calm} halves. */
    static int bottomY(int calm) {
        return Math.floorDiv(calm, 2) - DEPTH;
    }

    /** Highest block-Y murk can reach for a calm surface of {@code calm} halves. */
    static int topY(int calm) {
        return Math.floorDiv(calm + AMPLITUDE - 1, 2);
    }

    // The swell at a column, -1..1: three travelling waves of different lengths, speeds and headings,
    // so the crests cross and never settle into one repeating pattern.
    static double wave(int x, int z, double seconds) {
        double tau = Math.PI * 2.0;
        double a = Math.sin((x * 0.80 + z * 0.60) * (tau / 41.0) - seconds * (tau / 15.0));
        double b = Math.sin((x * -0.50 + z * 0.87) * (tau / 23.0) - seconds * (tau / 10.0));
        double c = Math.sin((x * 0.94 - z * 0.34) * (tau / 13.0) - seconds * (tau / 7.0));
        return (a + 0.6 * b + 0.35 * c) / 1.95;
    }

    // The swell's offset from calm at a column, in halves, given the players it runs round.
    private static int offset(int x, int z, double seconds, List<ServerPlayer> players) {
        double nearest = Double.MAX_VALUE;
        for (ServerPlayer p : players) {
            double dx = p.getX() - (x + 0.5), dz = p.getZ() - (z + 0.5);
            nearest = Math.min(nearest, dx * dx + dz * dz);
        }
        if (nearest >= (double) RADIUS * RADIUS) {
            return 0;
        }
        double fade = Mth.clamp((RADIUS - Math.sqrt(nearest)) / FADE, 0.0, 1.0);
        return (int) Math.round(wave(x, z, seconds) * AMPLITUDE * fade);
    }

    // --- Ticking ---

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !Config.dimensionEnabled(level)) {
            return;
        }
        Layer layer = layer(level);
        int calm = calmHalves(level);
        if (calm != layer.calm) {
            // The boundary moved (its schedule, a config change, the first tick): lay every chunk anew.
            layer.calm = calm;
            layer.swell.clear();
            layer.queue.addAll(layer.loaded);
        }
        List<ServerPlayer> players = level.players();
        double seconds = level.getGameTime() / 20.0;

        swell(level, layer, players, seconds);

        if (!layer.dirty.isEmpty()) {
            LongIterator it = layer.dirty.iterator();
            while (it.hasNext()) {
                long col = it.nextLong();
                int x = BlockPos.getX(col), z = BlockPos.getZ(col);
                LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
                if (chunk != null) {
                    int s = calm + offset(x, z, seconds, players);
                    layColumn(level, chunk, x, z, s, calm, bottomY(calm), topY(calm));
                }
            }
            layer.dirty.clear();
        }

        long deadline = System.nanoTime() + BUDGET_NANOS;
        while (!layer.queue.isEmpty() && System.nanoTime() < deadline) {
            long key = layer.queue.removeFirstLong();
            LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            // Only chunks a player can see. One that is merely loaded -- another chunk's neighbour, a
            // ticket of some mod's -- is laid when someone first watches it.
            if (chunk != null && watched(level, chunk.getPos())) {
                layChunk(level, layer, chunk, calm, players, seconds);
            }
        }
    }

    // Moves the swell in every chunk within reach of a player, column by column, laying again only the
    // columns whose step changed. A chunk the swell has left is queued for a full pass, which lays it
    // calm.
    private static void swell(ServerLevel level, Layer layer, List<ServerPlayer> players, double seconds) {
        LongOpenHashSet near = new LongOpenHashSet();
        int reach = (RADIUS >> 4) + 1;
        for (ServerPlayer p : players) {
            ChunkPos at = p.chunkPosition();
            for (int cx = at.x - reach; cx <= at.x + reach; cx++) {
                for (int cz = at.z - reach; cz <= at.z + reach; cz++) {
                    near.add(ChunkPos.asLong(cx, cz));
                }
            }
        }
        LongIterator old = layer.swell.keySet().iterator();
        while (old.hasNext()) {
            long key = old.nextLong();
            if (!near.contains(key)) {
                old.remove();
                layer.queue.add(key);
            }
        }
        layer.near = near;
        int calm = layer.calm;
        int bottom = bottomY(calm), top = topY(calm);
        LongIterator it = near.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            int cx = ChunkPos.getX(key), cz = ChunkPos.getZ(key);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
            if (chunk == null) {
                continue;
            }
            byte[] laid = layer.swell.get(key);
            if (laid == null) {
                // Not laid since it came near: a full pass first, ahead of the rest of the queue but
                // under its time budget -- a player arriving brings dozens of these at once.
                layer.queue.addAndMoveToFirst(key);
                continue;
            }
            for (int i = 0; i < 256; i++) {
                int x = (cx << 4) + (i & 15), z = (cz << 4) + (i >> 4);
                int off = offset(x, z, seconds, players);
                if (laid[i] != off) {
                    laid[i] = (byte) off;
                    layColumn(level, chunk, x, z, calm + off, calm, bottom, top);
                }
            }
        }
    }

    // A full pass over a chunk: every column laid from scratch, and any murk left from an earlier
    // boundary (a schedule that moved it while the chunk was away, a world saved before) taken out.
    private static void layChunk(ServerLevel level, Layer layer, LevelChunk chunk, int calm,
            List<ServerPlayer> players, double seconds) {
        int bottom = bottomY(calm), top = topY(calm);
        ChunkPos cp = chunk.getPos();
        clearStale(level, chunk, bottom, top);
        byte[] laid = layer.swell.get(cp.toLong());
        if (laid == null && layer.near.contains(cp.toLong())) {
            laid = new byte[256];
            layer.swell.put(cp.toLong(), laid); // from here on the swell moves it column by column
        }
        for (int i = 0; i < 256; i++) {
            int x = cp.getMinBlockX() + (i & 15), z = cp.getMinBlockZ() + (i >> 4);
            int off = offset(x, z, seconds, players);
            if (laid != null) {
                laid[i] = (byte) off;
            }
            layColumn(level, chunk, x, z, calm + off, calm, bottom, top);
        }
    }

    // Murk outside [bottom, top]: only in sections whose palette holds murk at all, which is a cheap
    // test, so a chunk with none costs next to nothing.
    private static void clearStale(ServerLevel level, LevelChunk chunk, int bottom, int top) {
        LevelChunkSection[] sections = chunk.getSections();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        Block murk = ModBlocks.MURK.get();
        for (int si = 0; si < sections.length; si++) {
            LevelChunkSection section = sections[si];
            if (section.hasOnlyAir() || !section.maybeHas(st -> st.is(murk))) {
                continue;
            }
            int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(si));
            if (baseY >= bottom && baseY + 15 <= top) {
                continue; // wholly inside the layer: the column pass sees to it
            }
            for (int ly = 0; ly < 16; ly++) {
                int y = baseY + ly;
                if (y >= bottom && y <= top) {
                    continue;
                }
                for (int i = 0; i < 256; i++) {
                    if (section.getBlockState(i & 15, ly, i >> 4).is(murk)) {
                        pos.set(chunk.getPos().getMinBlockX() + (i & 15), y, chunk.getPos().getMinBlockZ() + (i >> 4));
                        set(level, pos, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

    // Lays one column for a surface s halves high: full murk below it, a half where it falls mid-cell,
    // air above, from bottom to top. Cells holding anything but air or murk are left alone.
    private static void layColumn(ServerLevel level, LevelChunk chunk, int x, int z, int s, int calm,
            int bottom, int top) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(x, top + 1, z);
        Block murk = ModBlocks.MURK.get();
        // Walk down so the cell above is settled before the one below asks whether it is the surface.
        boolean murkAbove = chunk.getBlockState(pos).is(murk);
        for (int y = top; y >= bottom; y--) {
            pos.setY(y);
            BlockState cur = chunk.getBlockState(pos);
            boolean open = cur.isAir() || cur.is(murk);
            if (!open) {
                murkAbove = false;
                continue;
            }
            int fill = Mth.clamp(s - 2 * y, 0, 2);
            if (fill > 0 && AirtightRooms.shelters(level, pos)) {
                fill = 0;
            }
            BlockState want = fill == 0 ? Blocks.AIR.defaultBlockState()
                    : murk.defaultBlockState().setValue(MurkBlock.LEVEL, fill).setValue(MurkBlock.SURFACE, !murkAbove);
            if (cur != want) {
                set(level, pos, want);
            }
            murkAbove = fill > 0;
        }
    }

    private static void set(ServerLevel level, BlockPos pos, BlockState state) {
        writing = true;
        try {
            // Clients told, but no neighbour updates: to every block round it murk is air.
            level.setBlock(pos, state, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS);
        } finally {
            writing = false;
        }
    }

    /** From {@code LevelChunkMixin}: a block changed somewhere in a server level. */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (writing) {
            return;
        }
        Layer layer = LAYERS.get(level);
        if (layer == null || layer.calm == Integer.MIN_VALUE) {
            return;
        }
        int y = pos.getY();
        if (y < bottomY(layer.calm) - 1 || y > topY(layer.calm) + 1) {
            return;
        }
        layer.dirty.add(BlockPos.asLong(pos.getX(), 0, pos.getZ()));
    }

    private static boolean watched(ServerLevel level, ChunkPos pos) {
        return !level.getChunkSource().chunkMap.getPlayers(pos, false).isEmpty();
    }

    // --- Chunks coming and going ---
    //
    // A chunk is laid when a player starts watching it, not when it loads. Laying writes to the chunk,
    // and something (the light engine, another mod) answers a write by loading the chunk next door: laid
    // on load, that neighbour was laid in turn and loaded the next, and a line of chunks marched off
    // through the world, generating terrain as it went, one a tick for as long as the game ran.

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk
                && Config.dimensionEnabled(level)) {
            long key = chunk.getPos().toLong();
            layer(level).loaded.add(key);
        }
    }

    @SubscribeEvent
    static void onChunkWatch(ChunkWatchEvent.Watch event) {
        ServerLevel level = event.getLevel();
        if (Config.dimensionEnabled(level)) {
            layer(level).queue.add(event.getPos().toLong());
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Layer layer = LAYERS.get(level);
            if (layer != null) {
                long key = event.getChunk().getPos().toLong();
                layer.loaded.remove(key);
                layer.queue.remove(key);
                layer.swell.remove(key);
            }
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LAYERS.remove(level);
        }
    }

    private MurkLayer() {}
}
