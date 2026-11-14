package com.fogged;

import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import it.unimi.dsi.fastutil.ints.Int2ByteMap;
import it.unimi.dsi.fastutil.ints.Int2ByteOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.fogged.block.MurkBlock;
import com.fogged.registry.ModBlocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentSerializer;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Volumetric fog: a finite amount of murk held cell by cell, which pours and settles like a heavy gas.
 *
 * <p>Every cell holds 0, 1 (a half cell, sitting in its lower half) or 2 (a full cell) units. The fog
 * lives beside the blocks rather than as one, the way a waterlogged block holds its water: any cell
 * whose collision shape is not a full cube can hold it -- air, a slab's open half, a fence, a door --
 * and it crosses between two cells wherever neither block closes that face to air
 * ({@link AirFill#closes}, the rule the airtight rooms seal by). Real fluids push it out.
 *
 * <p>Nothing makes it but a source (the fog emitter, for now). It is lost to a solid block set into a
 * cell with nowhere to push it, and it thins from its top: every exposed cell has a small chance each
 * second of losing half a cell ({@link #DECAY_CHANCE}), so fog fed by a source rises only until the
 * surface loses what the source makes. It moves every {@link #STEP} ticks, only in cells that
 * were woken -- by a change of their own or a neighbour's, or a block changing beside them -- so a
 * settled pool costs nothing. Each woken cell, in turn:
 * <ol>
 *   <li>falls into the cell below, as far as that one has room;</li>
 *   <li>slips a unit sideways into an empty cell it can fall out of (fog runs off a ledge);</li>
 *   <li>if full, spreads a half into an empty neighbour; or, with none, pushes it through the half
 *       cells around it at the same height to the nearest empty cell they touch, so a pool levels out
 *       into a sheet of halves instead of a lone full cell standing in it forever.</li>
 * </ol>
 * A half cell never splits, so a thin sheet stops spreading and stays. Fog stacks: more poured on a
 * full cell stays above it, and a mound grows where the push runs out of reach ({@link #PUSH_REACH}).
 *
 * <p>Stored per chunk as an attachment (saved with it). What players see is a {@link MurkBlock} in each
 * air cell holding fog, kept in step at the end of every tick ({@link #mirror}): an ordinary block, so
 * it reaches clients and renderers the way any block does.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class VolumeFog {

    public static final byte HALF = 1;
    public static final byte FULL = 2;

    /** Ticks between flow steps. Water flows every 5. */
    private static final int STEP = 4;
    /** Most cells one step may update; the rest wait for the next. */
    private static final int BUDGET = 8192;
    /** Most half cells the push from a full cell searches through for somewhere to go. */
    private static final int PUSH_REACH = 192;
    /** Ticks between decay passes. */
    private static final int DECAY_STEP = 20;
    /**
     * Chance per decay pass that an exposed cell -- one with no fog above it -- loses half a cell.
     * Fog thins from its top, so a source feeding it settles where what it makes matches what the
     * surface loses instead of climbing for ever: the wider the fog spreads, the more surface it has.
     */
    private static final float DECAY_CHANCE = 1.0F / 90.0F;

    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    // --- Storage ---

    /** One chunk's fog: packed cell (see {@link #key}) to its amount. Only non-zero cells are kept. */
    public static final class Cells {
        final Int2ByteOpenHashMap map = new Int2ByteOpenHashMap();

        public boolean isEmpty() {
            return map.isEmpty();
        }

        public Int2ByteOpenHashMap map() {
            return map;
        }
    }

    private static final IAttachmentSerializer<CompoundTag, Cells> SERIALIZER = new IAttachmentSerializer<>() {
        @Override
        public Cells read(IAttachmentHolder holder, CompoundTag tag, HolderLookup.Provider provider) {
            Cells cells = new Cells();
            int[] keys = tag.getIntArray("k");
            byte[] vals = tag.getByteArray("v");
            for (int i = 0; i < Math.min(keys.length, vals.length); i++) {
                if (vals[i] > 0) {
                    cells.map.put(keys[i], (byte) Math.min(vals[i], FULL));
                }
            }
            return cells;
        }

        @Override
        public CompoundTag write(Cells cells, HolderLookup.Provider provider) {
            if (cells.map.isEmpty()) {
                return null;
            }
            int[] keys = new int[cells.map.size()];
            byte[] vals = new byte[keys.length];
            int i = 0;
            for (Int2ByteMap.Entry e : cells.map.int2ByteEntrySet()) {
                keys[i] = e.getIntKey();
                vals[i++] = e.getByteValue();
            }
            CompoundTag tag = new CompoundTag();
            tag.putIntArray("k", keys);
            tag.putByteArray("v", vals);
            return tag;
        }
    };

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Fogged.MODID);

    public static final Supplier<AttachmentType<Cells>> CELLS = ATTACHMENTS.register("volume_fog",
            () -> AttachmentType.builder(Cells::new).serialize(SERIALIZER).build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    /** A cell's place in its chunk's map: x and z within the chunk, then the world y. */
    public static int key(int x, int y, int z) {
        return (x & 15) | (z & 15) << 4 | (y + 2048) << 8;
    }

    public static int keyX(long chunk, int key) {
        return ChunkPos.getX(chunk) * 16 + (key & 15);
    }

    public static int keyY(int key) {
        return (key >>> 8) - 2048;
    }

    public static int keyZ(long chunk, int key) {
        return ChunkPos.getZ(chunk) * 16 + (key >> 4 & 15);
    }

    /**
     * How much fog {@code pos} holds, 0..{@link #FULL}. The client has only the murk blocks to go by,
     * so there fog standing in a fence or a doorway (no block of its own) reads as none.
     */
    public static int amount(Level level, BlockPos pos) {
        if (level.isClientSide) {
            BlockState state = level.getBlockState(pos);
            return state.getBlock() instanceof MurkBlock ? state.getValue(MurkBlock.LEVEL) : 0;
        }
        LevelChunk chunk = chunkNow((ServerLevel) level, pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !chunk.hasData(CELLS)) {
            return 0;
        }
        return chunk.getData(CELLS).map.get(key(pos.getX(), pos.getY(), pos.getZ()));
    }

    /**
     * Whether {@code point} is inside fog: in a cell holding some, below the top of what it holds. A
     * half cell fills its lower half.
     */
    public static boolean inFog(Level level, Vec3 point) {
        BlockPos pos = BlockPos.containing(point);
        int a = amount(level, pos);
        return a > 0 && point.y - pos.getY() < a * 0.5;
    }

    @Nullable
    private static LevelChunk chunkNow(ServerLevel level, int cx, int cz) {
        return level.getChunkSource().getChunkNow(cx, cz);
    }

    // --- Simulation state, per level ---

    private static final class Sim {
        /** Cells to look at next step. Insertion-ordered, so the oldest go first when over budget. */
        final LongLinkedOpenHashSet active = new LongLinkedOpenHashSet();
        /** Chunks holding any fog, for the cheap "is there fog near this block change" test. */
        final LongOpenHashSet chunks = new LongOpenHashSet();
        /** Cells whose murk block may no longer match, put right at the end of the tick ({@link #mirror}). */
        final LongLinkedOpenHashSet mirror = new LongLinkedOpenHashSet();
        /**
         * Murk blocks someone else set or took away (a command, an explosion): the fog to set those
         * cells to, applied at the start of the next tick rather than from inside the block change.
         */
        final Long2IntOpenHashMap adopt = new Long2IntOpenHashMap();
    }

    private static final Map<ResourceKey<Level>, Sim> SIMS = new java.util.concurrent.ConcurrentHashMap<>();

    private static Sim sim(ServerLevel level) {
        return SIMS.computeIfAbsent(level.dimension(), k -> new Sim());
    }

    // --- Changing it ---

    /**
     * Set {@code pos} to {@code amount} units, waking it and its neighbours. False when the chunk is
     * not loaded, or the amount would go into a cell that cannot hold fog.
     */
    public static boolean set(ServerLevel level, BlockPos pos, int amount) {
        return set(level, pos.getX(), pos.getY(), pos.getZ(), amount);
    }

    private static boolean set(ServerLevel level, int x, int y, int z, int amount) {
        LevelChunk chunk = chunkNow(level, x >> 4, z >> 4);
        if (chunk == null || y < level.getMinBuildHeight() || y >= level.getMaxBuildHeight()) {
            return false;
        }
        amount = Math.max(0, Math.min(amount, FULL));
        Cells cells = chunk.getData(CELLS);
        int k = key(x, y, z);
        int was = cells.map.get(k);
        if (was == amount) {
            return true;
        }
        if (amount == 0) {
            cells.map.remove(k);
        } else {
            cells.map.put(k, (byte) amount);
        }
        chunk.setUnsaved(true);
        Sim sim = sim(level);
        long ck = ChunkPos.asLong(x >> 4, z >> 4);
        if (cells.map.isEmpty()) {
            sim.chunks.remove(ck);
        } else {
            sim.chunks.add(ck);
        }
        sim.mirror.add(BlockPos.asLong(x, y, z));
        sim.mirror.add(BlockPos.asLong(x, y - 1, z)); // its SURFACE depends on this cell
        wakeAround(sim, x, y, z);
        return true;
    }

    /** Add {@code units} to {@code pos} as far as it has room; returns how many went in. */
    public static int add(ServerLevel level, BlockPos pos, int units) {
        if (!holds(level, pos)) {
            return 0;
        }
        int have = amount(level, pos);
        int put = Math.min(units, FULL - have);
        if (put > 0 && !set(level, pos, have + put)) {
            return 0;
        }
        return Math.max(put, 0);
    }

    /**
     * Breathe one unit out of the solid block at {@code source}: into the nearest cell with room,
     * searching out from its sides (above first) through the fog already there, so a source buried in
     * its own fog keeps pushing it outward and upward instead of choking. False when nothing within
     * {@code reach} cells of fog has room.
     */
    public static boolean inject(ServerLevel level, BlockPos source, int reach) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(source.asLong());
        for (Direction d : new Direction[] {Direction.UP, Direction.NORTH, Direction.EAST, Direction.SOUTH,
                Direction.WEST, Direction.DOWN}) {
            BlockPos n = source.relative(d);
            if (seen.add(n.asLong()) && holds(level, n)) {
                queue.enqueue(n.asLong());
            }
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int visited = 0;
        while (!queue.isEmpty() && visited++ < reach) {
            BlockPos here = at.set(queue.dequeueLong()).immutable();
            int a = amount(level, here);
            if (a < FULL) {
                return set(level, here, a + 1);
            }
            for (Direction d : Direction.values()) {
                BlockPos n = here.relative(d);
                if (!seen.contains(n.asLong()) && opens(level, here, d) && holds(level, n)) {
                    seen.add(n.asLong());
                    queue.enqueue(n.asLong());
                }
            }
        }
        return false;
    }

    /** Remove all fog within {@code radius} (a cube) of {@code centre}; returns the units removed. */
    public static int clear(ServerLevel level, BlockPos centre, int radius) {
        int removed = 0;
        int cx0 = (centre.getX() - radius) >> 4, cx1 = (centre.getX() + radius) >> 4;
        int cz0 = (centre.getZ() - radius) >> 4, cz1 = (centre.getZ() + radius) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                LevelChunk chunk = chunkNow(level, cx, cz);
                if (chunk == null || !chunk.hasData(CELLS)) {
                    continue;
                }
                long ck = ChunkPos.asLong(cx, cz);
                IntArrayList doomed = new IntArrayList();
                for (Int2ByteMap.Entry e : chunk.getData(CELLS).map.int2ByteEntrySet()) {
                    int k = e.getIntKey();
                    if (Math.abs(keyX(ck, k) - centre.getX()) <= radius && Math.abs(keyY(k) - centre.getY()) <= radius
                            && Math.abs(keyZ(ck, k) - centre.getZ()) <= radius) {
                        doomed.add(k);
                        removed += e.getByteValue();
                    }
                }
                for (int k : doomed) {
                    set(level, keyX(ck, k), keyY(k), keyZ(ck, k), 0);
                }
            }
        }
        return removed;
    }

    /** Total fog units in the loaded chunks of {@code level}, and the cells holding them. */
    public static int[] census(ServerLevel level) {
        int units = 0, cells = 0;
        for (long ck : sim(level).chunks) {
            LevelChunk chunk = chunkNow(level, ChunkPos.getX(ck), ChunkPos.getZ(ck));
            if (chunk == null || !chunk.hasData(CELLS)) {
                continue;
            }
            for (byte v : chunk.getData(CELLS).map.values()) {
                units += v;
                cells++;
            }
        }
        return new int[] {units, cells};
    }

    private static void wakeAround(Sim sim, int x, int y, int z) {
        sim.active.add(BlockPos.asLong(x, y, z));
        sim.active.add(BlockPos.asLong(x + 1, y, z));
        sim.active.add(BlockPos.asLong(x - 1, y, z));
        sim.active.add(BlockPos.asLong(x, y + 1, z));
        sim.active.add(BlockPos.asLong(x, y - 1, z));
        sim.active.add(BlockPos.asLong(x, y, z + 1));
        sim.active.add(BlockPos.asLong(x, y, z - 1));
    }

    /** Set while this class puts its own murk blocks down, so {@link #onBlockChanged} ignores them. */
    private static boolean mirroring;

    /**
     * A block changed at {@code pos} (see {@code LevelChunkMixin}): the fog there may have to give way,
     * and the fog beside it may now have somewhere to go. Cheap when there is no fog near. A murk block
     * put down or taken away by anything but this class is taken at its word: {@code /setblock} of
     * murk makes fog, and an explosion or {@code /fill ... air} through it clears it.
     */
    public static void onBlockChanged(ServerLevel level, BlockPos pos, BlockState old, BlockState now) {
        if (mirroring) {
            return;
        }
        boolean wasMurk = old.getBlock() instanceof MurkBlock, isMurk = now.getBlock() instanceof MurkBlock;
        if (isMurk || (wasMurk && now.isAir())) {
            Sim sim = sim(level);
            sim.adopt.put(pos.asLong(), isMurk ? (int) now.getValue(MurkBlock.LEVEL) : 0);
            return;
        }
        Sim sim = SIMS.get(level.dimension());
        if (sim == null || sim.chunks.isEmpty()) {
            return;
        }
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        boolean near = false;
        for (int dx = -1; dx <= 1 && !near; dx++) {
            for (int dz = -1; dz <= 1 && !near; dz++) {
                near = sim.chunks.contains(ChunkPos.asLong(cx + dx, cz + dz));
            }
        }
        if (near) {
            wakeAround(sim, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    // --- Flow ---

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        Sim sim = SIMS.get(level.dimension());
        if (sim == null) {
            return;
        }
        if (!sim.adopt.isEmpty()) {
            Long2IntOpenHashMap adopt = new Long2IntOpenHashMap(sim.adopt);
            sim.adopt.clear();
            for (Long2IntMap.Entry e : adopt.long2IntEntrySet()) {
                BlockPos at = BlockPos.of(e.getLongKey());
                set(level, at, e.getIntValue());
                sim.mirror.add(at.asLong()); // even unchanged, the block itself may need putting right
            }
        }
        if (level.getGameTime() % STEP == 0 && !sim.active.isEmpty()) {
            step(level, sim);
        }
        if (level.getGameTime() % DECAY_STEP == 0 && !sim.chunks.isEmpty()) {
            decay(level, sim);
        }
        if (!sim.mirror.isEmpty()) {
            mirror(level, sim);
        }
    }

    // Put the murk blocks right for the cells whose fog changed: a block of that level where the cell
    // is air or murk, air where the fog has gone. Cells in a fence, a door and so on keep their block;
    // the fog there is real but unseen. No neighbour updates: nothing reacts to murk.
    private static void mirror(ServerLevel level, Sim sim) {
        long[] cells = sim.mirror.toLongArray();
        sim.mirror.clear();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        mirroring = true;
        try {
            for (long cell : cells) {
                at.set(cell);
                if (chunkNow(level, at.getX() >> 4, at.getZ() >> 4) == null
                        || at.getY() < level.getMinBuildHeight() || at.getY() >= level.getMaxBuildHeight()) {
                    continue;
                }
                BlockState state = level.getBlockState(at);
                if (!state.isAir() && !(state.getBlock() instanceof MurkBlock)) {
                    continue;
                }
                int a = amount(level, at);
                BlockState want = a == 0 ? Blocks.AIR.defaultBlockState()
                        : ModBlocks.MURK.get().defaultBlockState()
                                .setValue(MurkBlock.LEVEL, a)
                                .setValue(MurkBlock.SURFACE, amount(level, at.above()) == 0);
                if (want != state) {
                    level.setBlock(at, want, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        } finally {
            mirroring = false;
        }
    }

    private static void decay(ServerLevel level, Sim sim) {
        RandomSource random = level.getRandom();
        IntArrayList doomed = new IntArrayList();
        for (long ck : sim.chunks.toLongArray()) {
            LevelChunk chunk = chunkNow(level, ChunkPos.getX(ck), ChunkPos.getZ(ck));
            if (chunk == null || !chunk.hasData(CELLS)) {
                continue;
            }
            Int2ByteOpenHashMap map = chunk.getData(CELLS).map;
            doomed.clear();
            for (int k : map.keySet()) {
                // The cell above is in the same column, so in the same chunk's map.
                if (!map.containsKey(k + (1 << 8)) && random.nextFloat() < DECAY_CHANCE) {
                    doomed.add(k);
                }
            }
            for (int k : doomed) {
                set(level, keyX(ck, k), keyY(k), keyZ(ck, k), map.get(k) - 1);
            }
        }
    }

    private static void step(ServerLevel level, Sim sim) {
        int n = Math.min(sim.active.size(), BUDGET);
        long[] batch = new long[n];
        for (int i = 0; i < n; i++) {
            batch[i] = sim.active.removeFirstLong();
        }
        // Shuffled, so a sheet does not always spread from the same side first.
        RandomSource random = level.getRandom();
        for (int i = n - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            long t = batch[i];
            batch[i] = batch[j];
            batch[j] = t;
        }
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (long cell : batch) {
            at.set(cell);
            update(level, at.immutable(), random);
        }
    }

    private static void update(ServerLevel level, BlockPos c, RandomSource random) {
        int a = amount(level, c);
        if (a == 0) {
            return;
        }
        if (!holds(level, c)) {
            displace(level, c, a, random);
            return;
        }

        // 1. Fall.
        BlockPos below = c.below();
        if (opens(level, c, Direction.DOWN) && holds(level, below)) {
            int room = FULL - amount(level, below);
            if (room > 0) {
                int m = Math.min(a, room);
                move(level, c, below, m);
                a -= m;
                if (a == 0) {
                    return;
                }
            }
        }

        // 2. Run off a ledge: an empty neighbour with room under it.
        int start = random.nextInt(4);
        for (int i = 0; i < 4 && a > 0; i++) {
            Direction d = HORIZONTAL[(start + i) & 3];
            BlockPos n = c.relative(d);
            if (amount(level, n) == 0 && opens(level, c, d) && holds(level, n) && canFallFrom(level, n)) {
                move(level, c, n, 1);
                a--;
            }
        }
        if (a < FULL) {
            return;
        }

        // 3. Spread a half from a full cell.
        for (int i = 0; i < 4; i++) {
            Direction d = HORIZONTAL[(start + i) & 3];
            BlockPos n = c.relative(d);
            if (amount(level, n) == 0 && opens(level, c, d) && holds(level, n)) {
                move(level, c, n, 1);
                return;
            }
        }
        BlockPos to = pushTarget(level, c);
        if (to != null) {
            move(level, c, to, 1);
        }
    }

    // The nearest empty cell at c's height reachable through connected half cells, breadth first.
    @Nullable
    private static BlockPos pushTarget(ServerLevel level, BlockPos c) {
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        seen.add(c.asLong());
        queue.enqueue(c.asLong());
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        int visited = 0;
        while (!queue.isEmpty() && visited++ < PUSH_REACH) {
            at.set(queue.dequeueLong());
            BlockPos here = at.immutable();
            for (Direction d : HORIZONTAL) {
                BlockPos n = here.relative(d);
                if (!seen.add(n.asLong()) || !opens(level, here, d) || !holds(level, n)) {
                    continue;
                }
                int na = amount(level, n);
                if (na == 0) {
                    return n;
                }
                if (na == HALF) {
                    queue.enqueue(n.asLong());
                }
            }
        }
        return null;
    }

    // A solid block took the cell: push the fog out, upward first, then sideways, then down. What has
    // nowhere to go is lost.
    private static void displace(ServerLevel level, BlockPos c, int a, RandomSource random) {
        set(level, c, 0);
        Direction[] order = {Direction.UP, HORIZONTAL[random.nextInt(4)], Direction.NORTH, Direction.EAST,
                Direction.SOUTH, Direction.WEST, Direction.DOWN};
        for (Direction d : order) {
            if (a == 0) {
                return;
            }
            a -= add(level, c.relative(d), a);
        }
    }

    private static void move(ServerLevel level, BlockPos from, BlockPos to, int units) {
        set(level, to, amount(level, to) + units);
        set(level, from, amount(level, from) - units);
    }

    private static boolean canFallFrom(ServerLevel level, BlockPos n) {
        BlockPos below = n.below();
        return opens(level, n, Direction.DOWN) && holds(level, below) && amount(level, below) < FULL;
    }

    /**
     * Whether {@code pos} can hold fog: loaded, inside the world, not a full collision cube, no fluid.
     */
    public static boolean holds(Level level, BlockPos pos) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        if (level instanceof ServerLevel server && chunkNow(server, pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        return state.getFluidState().isEmpty() && !state.isCollisionShapeFullBlock(level, pos);
    }

    // Whether fog can cross from `from` into its neighbour on side `d`: neither block shuts that face.
    private static boolean opens(Level level, BlockPos from, Direction d) {
        BlockPos to = from.relative(d);
        if (level instanceof ServerLevel server && chunkNow(server, to.getX() >> 4, to.getZ() >> 4) == null) {
            return false; // never load a chunk just to ask
        }
        return !AirFill.closes(level, from, level.getBlockState(from), d)
                && !AirFill.closes(level, to, level.getBlockState(to), d.getOpposite());
    }

    // --- Chunks coming and going ---

    @SubscribeEvent
    static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)
                || !chunk.hasData(CELLS) || chunk.getData(CELLS).isEmpty()) {
            return;
        }
        Sim sim = sim(level);
        long ck = chunk.getPos().toLong();
        sim.chunks.add(ck);
        // Wake it all: the world may have changed round it while it was away (a neighbour generated).
        for (int k : chunk.getData(CELLS).map.keySet()) {
            sim.active.add(BlockPos.asLong(keyX(ck, k), keyY(k), keyZ(ck, k)));
        }
    }

    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            Sim sim = SIMS.get(level.dimension());
            if (sim != null) {
                sim.chunks.remove(event.getChunk().getPos().toLong());
            }
        }
    }

    @SubscribeEvent
    static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SIMS.remove(level.dimension());
        }
    }

    private VolumeFog() {}
}
