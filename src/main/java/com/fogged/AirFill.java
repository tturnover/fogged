package com.fogged;

import java.util.Collection;

import java.util.List;

import com.fogged.registry.ModTags;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * The flood fill behind an airtight space: from the cells in front of a compressor's outlet, spread
 * through every neighbouring cell the air can reach, and report whether it stayed shut in.
 *
 * <p>Air crosses from one cell into the next unless either block closes that face with its
 * <em>collision</em> shape: the leaving cell's face on that side, or the entering cell's opposite
 * face (see {@link #closes}). So stone, planks, glass and a closed door or trapdoor seal, as does a
 * run of panes; an open door, a slab's open half, or anything tagged
 * {@link ModTags#LETS_AIR_THROUGH} -- leaves, fences, iron bars -- lets the air through. A cell is
 * "inside" as soon as the air can get into any part of it, so a stair or a door's own cell counts as
 * room.
 *
 * <p>This is Starship EVO's life-support idea -- release air and see whether it gets out -- with an
 * exact fill instead of molecules bouncing in a box, so an L-shaped room is as tight as a square one.
 *
 * <p>The fill fails as a {@link Result#LEAK} the moment it reaches a cell open to the sky or the edge
 * of the world, stops as {@link Result#UNLOADED} at a chunk that is not loaded (there is no telling
 * what is in it yet), and fails as {@link Result#OVERSIZE} once it holds
 * more cells than the compressor can keep: a cave or an open valley is just a very big room. Either way
 * the room floods. Runs on the server thread only.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class AirFill {

    public enum Result {
        /** Shut in and within capacity: {@link #order()} is the room. */
        SEALED,
        /** Escaped to the sky or into the unknown: {@link #leakPath()} leads from the outlet to the hole. */
        LEAK,
        /** Bigger than the compressor can hold. */
        OVERSIZE,
        /** Every outlet cell is walled off: there is no room in front of the compressor at all. */
        BLOCKED,
        /** The air ran into a chunk that is not loaded, so nothing can be said yet. */
        UNLOADED
    }

    private static final Direction[] DIRECTIONS = Direction.values();

    // Bit per Direction ordinal: whether the state's collision shape covers that face completely.
    // Kept per state, and only for blocks whose shape is fixed; a dynamic-shape block is asked each time.
    private static final Reference2ByteOpenHashMap<BlockState> FACE_CACHE = new Reference2ByteOpenHashMap<>();

    private final Result result;
    private final LongArrayList order;
    private final Long2LongOpenHashMap parent;
    private final long leakFrom;
    private final long leakTo;

    private AirFill(Result result, LongArrayList order, Long2LongOpenHashMap parent, long leakFrom, long leakTo) {
        this.result = result;
        this.order = order;
        this.parent = parent;
        this.leakFrom = leakFrom;
        this.leakTo = leakTo;
    }

    /**
     * Where the air first comes in: the cell {@code pos}, entered travelling {@code into} -- from a
     * compressor's outlet, or out of the open end of an air duct.
     */
    public record Seed(BlockPos pos, Direction into) {}

    /** Flood the air in from {@code seeds} up to {@code capacity} cells. */
    public static AirFill run(Level level, Collection<Seed> seeds, int capacity) {
        LongArrayList order = new LongArrayList();
        Long2LongOpenHashMap parent = new Long2LongOpenHashMap();
        parent.defaultReturnValue(Long.MIN_VALUE);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos next = new BlockPos.MutableBlockPos();

        for (Seed s : seeds) {
            BlockPos seed = s.pos();
            long key = seed.asLong();
            if (parent.containsKey(key)) {
                continue;
            }
            if (!level.isLoaded(seed)) {
                return new AirFill(Result.UNLOADED, order, parent, key, key);
            }
            if (closes(level, seed, level.getBlockState(seed), s.into().getOpposite())) {
                continue; // a wall right against the outlet: this seed is no way in
            }
            parent.put(key, key); // a seed is its own parent: the leak path stops here
            order.add(key);
            queue.enqueue(key);
        }
        if (order.isEmpty()) {
            return new AirFill(Result.BLOCKED, order, parent, 0L, 0L);
        }

        while (!queue.isEmpty()) {
            long cur = queue.dequeueLong();
            at.set(cur);
            if (escapes(level, at)) {
                // Out under the sky: the way out is straight up, so the leak sprays from here upward.
                return new AirFill(Result.LEAK, order, parent, cur, BlockPos.offset(cur, Direction.UP));
            }
            BlockState here = level.getBlockState(at);
            for (Direction d : DIRECTIONS) {
                if (closes(level, at, here, d)) {
                    continue;
                }
                next.setWithOffset(at, d);
                long key = next.asLong();
                if (parent.containsKey(key)) {
                    continue;
                }
                if (next.getY() < level.getMinBuildHeight() || next.getY() >= level.getMaxBuildHeight()) {
                    return new AirFill(Result.LEAK, order, parent, cur, key);
                }
                if (!level.isLoaded(next)) {
                    return new AirFill(Result.UNLOADED, order, parent, cur, key);
                }
                if (closes(level, next, level.getBlockState(next), d.getOpposite())) {
                    continue;
                }
                parent.put(key, cur);
                order.add(key);
                if (order.size() > capacity) {
                    return new AirFill(Result.OVERSIZE, order, parent, cur, key);
                }
                queue.enqueue(key);
            }
        }
        return new AirFill(Result.SEALED, order, parent, 0L, 0L);
    }

    // Open air: a cell with nothing above it that stops movement is under the open sky, and outside
    // whatever else surrounds it. Asked of the MOTION_BLOCKING heightmap, not canSeeSky: that one
    // answers by sky light, which glass lets straight through, so every cell under a glass roof read
    // as open sky and no glass room could seal. A dimension without a sky (the Nether) never reports
    // this, and leans on the capacity alone.
    private static boolean escapes(Level level, BlockPos pos) {
        return level.dimensionType().hasSkyLight()
                && pos.getY() >= level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ());
    }

    /**
     * Whether the block at {@code pos} closes its {@code face} to air. It does when its collision
     * shape covers that face whole, or makes a whole wall across the cell anywhere along the face's
     * axis -- a door whose panel stands in the middle of its block (Create's sliding doors) shuts the
     * way through as surely as one flush against a face. A block tagged
     * {@link ModTags#LETS_AIR_THROUGH} never closes anything.
     */
    public static boolean closes(Level level, BlockPos pos, BlockState state, Direction face) {
        if (state.isAir()) {
            return false;
        }
        if (state.getBlock().hasDynamicShape()) {
            return (mask(state, state.getCollisionShape(level, pos)) & (1 << face.ordinal())) != 0;
        }
        byte mask = FACE_CACHE.getOrDefault(state, (byte) -1);
        if (mask == -1) {
            mask = mask(state, state.getCollisionShape(level, pos));
            FACE_CACHE.put(state, mask);
        }
        return (mask & (1 << face.ordinal())) != 0;
    }

    // Bit per Direction ordinal: the faces this state closes (see closes).
    private static byte mask(BlockState state, VoxelShape collision) {
        if (state.is(ModTags.LETS_AIR_THROUGH)) {
            return 0;
        }
        VoxelShape shape = inCell(collision);
        byte mask = 0;
        for (Direction d : DIRECTIONS) {
            if (Block.isFaceFull(shape, d) || wallAcross(shape, d.getAxis())) {
                mask |= (byte) (1 << d.ordinal());
            }
        }
        return mask;
    }

    // Whether the shape holds a whole wall across the cell somewhere along `axis`: some slice of it at
    // right angles to the axis covers the full square. Checked at the middle of every span between the
    // shape's own breaks along the axis, which is everywhere a slice can differ.
    private static boolean wallAcross(VoxelShape shape, Direction.Axis axis) {
        if (shape.isEmpty()) {
            return false;
        }
        List<AABB> boxes = shape.toAabbs();
        DoubleList breaks = shape.getCoords(axis);
        for (int i = 0; i + 1 < breaks.size(); i++) {
            double t = (breaks.getDouble(i) + breaks.getDouble(i + 1)) * 0.5;
            if (t <= 0.0 || t >= 1.0) {
                continue;
            }
            VoxelShape slice = Shapes.empty();
            for (AABB b : boxes) {
                if (b.min(axis) <= t && b.max(axis) >= t) {
                    // The box's footprint across the axis, stretched the whole way along it.
                    slice = Shapes.or(slice, switch (axis) {
                        case X -> Shapes.box(0, b.minY, b.minZ, 1, b.maxY, b.maxZ);
                        case Y -> Shapes.box(b.minX, 0, b.minZ, b.maxX, 1, b.maxZ);
                        case Z -> Shapes.box(b.minX, b.minY, 0, b.maxX, b.maxY, 1);
                    });
                }
            }
            if (Block.isShapeFullBlock(slice)) {
                return true;
            }
        }
        return false;
    }

    // The part of a shape inside its own cell. Only that can close the cell's faces, and the full-face
    // test compares a face against exactly one block: a shape running past its cell -- an air
    // compressor's housing, a fence's post -- has faces wider than that and would read as open, so
    // the air went straight through a compressor's sides.
    private static VoxelShape inCell(VoxelShape shape) {
        return Shapes.join(shape, Shapes.block(), BooleanOp.AND);
    }

    // The cache knows tags (LETS_AIR_THROUGH); a datapack reload can change them.
    @SubscribeEvent
    static void onTagsUpdated(TagsUpdatedEvent event) {
        FACE_CACHE.clear();
    }

    public Result result() {
        return result;
    }

    /** Every cell reached, in the order the air reached them: seeds first, then outward. */
    public LongArrayList order() {
        return order;
    }

    /** The cell the air came from on its way into {@code cell}; a seed is its own parent. */
    public long parentOf(long cell) {
        return parent.get(cell);
    }

    /**
     * For a {@link Result#LEAK}: the cells from an outlet seed to the hole, ending with the last cell
     * still inside followed by the first cell outside. Empty for any other result.
     */
    public long[] leakPath() {
        if (result != Result.LEAK) {
            return new long[0];
        }
        LongArrayList path = new LongArrayList();
        path.add(leakTo);
        long cur = leakFrom;
        // Bounded by the fill itself; the guard only protects against a malformed parent chain.
        for (int guard = order.size() + 1; guard > 0; guard--) {
            path.add(cur);
            long up = parent.get(cur);
            if (up == cur || up == Long.MIN_VALUE) {
                break;
            }
            cur = up;
        }
        long[] out = path.toLongArray();
        for (int i = 0, j = out.length - 1; i < j; i++, j--) {
            long t = out[i];
            out[i] = out[j];
            out[j] = t;
        }
        return out;
    }
}
