package com.fogged;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;

// Where the waterline actually touches something, cached per chunk, so foam particles can be thrown
// anywhere in the loaded world rather than inside a radius around the camera.
//
// The waterline map (WaterlineMap) is a camera-centred grid capped at 192 blocks, and emitting from it
// meant foam stopped a short way out and visibly travelled with the player. This is the same idea
// Particular uses for waterfall spray: find the interesting columns once, remember them per chunk, and
// emit from the remembered set. A site is an OPEN column (the surface exists there) with at least one
// closed neighbour -- exactly the block edge the painted foam ring is drawn around.
//
// Scanning is spread over ticks and each chunk is refreshed on a slow rotation, so nothing here is a
// frame cost: a chunk's waterline only changes when someone builds at the boundary, or when the
// boundary itself drifts a whole block, and the latter clears the cache outright.
final class FoamSites {

    // How far out sites are kept, bounded by the render distance -- past that the chunks are not
    // loaded and there is nothing to scan.
    private static final int MAX_REACH_BLOCKS = 128;
    private static final int CHUNKS_PER_TICK = 8;      // scan budget
    private static final int RESCAN_INTERVAL = 200;    // ticks before a cached chunk is refreshed
    private static final int SPAWN_ATTEMPTS = 20;      // sites picked per tick, one particle each

    // Sites of one chunk, packed as (localX << 4 | localZ) -- a chunk holds at most 256 columns, so a
    // byte pair each rather than a BlockPos per site.
    private record Sites(short[] packed, long scannedTick) {
    }

    private static final Long2ObjectOpenHashMap<Sites> cache = new Long2ObjectOpenHashMap<>();
    private static final LongArrayList active = new LongArrayList();
    private static int cachedBoundaryY = Integer.MIN_VALUE;

    private FoamSites() {
    }

    /** Number of chunks currently holding sites, for the debug HUD. */
    static int cachedChunks() {
        return cache.size();
    }

    /**
     * Refresh part of the cache and throw this tick's foam. Called once per tick while the camera is
     * near the surface.
     */
    static void tick(Level level, Vec3 cam, int boundaryY, double surfaceY,
                     ParticleOptions foam, RandomSource rnd) {
        if (boundaryY != cachedBoundaryY) {
            // The boundary moved a whole block: every cached waterline is at the wrong height now.
            cache.clear();
            cachedBoundaryY = boundaryY;
        }
        int reach = reach(level);
        scanSome(level, cam, boundaryY, reach);
        emit(level, cam, reach, surfaceY, foam, rnd);
    }

    private static int reach(Level level) {
        int renderBlocks = net.minecraft.client.Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
        return Math.min(renderBlocks, MAX_REACH_BLOCKS);
    }

    // Walk the chunks in range, scanning the ones with no entry and refreshing the stalest of the rest,
    // a few per tick. Entries for chunks that have left the range are dropped so the map stays bounded.
    private static void scanSome(Level level, Vec3 cam, int boundaryY, int reach) {
        int chunkReach = (reach >> 4) + 1;
        int camChunkX = Mth.floor(cam.x) >> 4;
        int camChunkZ = Mth.floor(cam.z) >> 4;
        long tick = level.getGameTime();

        cache.keySet().removeIf(key -> {
            int dx = ChunkPos.getX(key) - camChunkX;
            int dz = ChunkPos.getZ(key) - camChunkZ;
            return Math.abs(dx) > chunkReach || Math.abs(dz) > chunkReach;
        });

        int budget = CHUNKS_PER_TICK;
        for (int dz = -chunkReach; dz <= chunkReach && budget > 0; dz++) {
            for (int dx = -chunkReach; dx <= chunkReach && budget > 0; dx++) {
                long key = ChunkPos.asLong(camChunkX + dx, camChunkZ + dz);
                Sites have = cache.get(key);
                if (have != null && tick - have.scannedTick() < RESCAN_INTERVAL) {
                    continue;
                }
                if (scanChunk(level, key, boundaryY, tick)) {
                    budget--;
                }
            }
        }
    }

    // Scan one chunk's boundary row. Returns false without spending budget when the chunk is not
    // loaded -- there is nothing to look at and no point coming back for it this tick.
    private static boolean scanChunk(Level level, long key, int boundaryY, long tick) {
        int chunkX = ChunkPos.getX(key);
        int chunkZ = ChunkPos.getZ(key);
        ChunkAccess chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        if (chunk == null) {
            return false;
        }
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        // Openness over the chunk plus a one-block border, so a column on the chunk edge can see the
        // neighbour it borders without a second lookup per side.
        boolean[] open = new boolean[18 * 18];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = -1; z <= 16; z++) {
            for (int x = -1; x <= 16; x++) {
                pos.set(baseX + x, boundaryY, baseZ + z);
                BlockState state = level.getBlockState(pos);
                open[(z + 1) * 18 + (x + 1)] = !WaterlineMap.columnClosed(level, pos, state);
            }
        }

        short[] sites = new short[256];
        int count = 0;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int i = (z + 1) * 18 + (x + 1);
                if (!open[i]) {
                    continue; // no surface here, so nothing for foam to sit on
                }
                boolean edge = !open[i - 1] || !open[i + 1] || !open[i - 18] || !open[i + 18];
                if (edge) {
                    sites[count++] = (short) ((x << 4) | z);
                }
            }
        }

        if (count == 0) {
            cache.put(key, new Sites(EMPTY, tick));
        } else {
            short[] trimmed = new short[count];
            System.arraycopy(sites, 0, trimmed, 0, count);
            cache.put(key, new Sites(trimmed, tick));
        }
        return true;
    }

    private static final short[] EMPTY = new short[0];

    // Throw a few particles from remembered sites. Chunks are drawn from uniformly rather than
    // positions from the whole area, so the budget lands on waterline instead of mostly on open ground
    // -- the reason a plain scatter had to be widened and thinned to reach any distance at all.
    private static void emit(Level level, Vec3 cam, int reach, double surfaceY,
                             ParticleOptions foam, RandomSource rnd) {
        active.clear();
        for (var entry : cache.long2ObjectEntrySet()) {
            if (entry.getValue().packed().length > 0) {
                active.add(entry.getLongKey());
            }
        }
        if (active.isEmpty()) {
            return;
        }
        for (int i = 0; i < SPAWN_ATTEMPTS; i++) {
            long key = active.getLong(rnd.nextInt(active.size()));
            short[] sites = cache.get(key).packed();
            short packed = sites[rnd.nextInt(sites.length)];
            double wx = (ChunkPos.getX(key) << 4) + ((packed >> 4) & 0xF) + rnd.nextDouble();
            double wz = (ChunkPos.getZ(key) << 4) + (packed & 0xF) + rnd.nextDouble();
            // A chunk can straddle the reach, so the site itself is checked rather than its chunk.
            if (Math.abs(wx - cam.x) > reach || Math.abs(wz - cam.z) > reach) {
                continue;
            }
            level.addParticle(foam, wx, surfaceY + 0.05, wz, 0.0, 0.0, 0.0);
        }
    }
}
