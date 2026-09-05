package com.fogged.block;

import com.fogged.CreateCompatibility;
import com.fogged.NozzleFilterEvents;
import com.fogged.registry.ModBlockEntities;
import com.mojang.serialization.MapCodec;

import java.util.EnumMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The block a Create nozzle becomes once a wool block is applied to it (see
 * {@code com.fogged.NozzleFilterEvents}). It is never placed from an item -- it only ever replaces an
 * in-world {@code create:nozzle}, inheriting that nozzle's {@code FACING} so it points away from the
 * fan it is attached to (the fan therefore sits on the {@code FACING.getOpposite()} side).
 *
 * <p>Its {@link NozzleFilterBlockEntity} reads the attached fan's speed each tick and opens a breathing
 * sphere around itself, so the block must genuinely replace the nozzle rather than sit alongside it --
 * that removes Create's own nozzle behaviour and avoids two air sources fighting over the same spot.
 */
public class NozzleFilterBlock extends BaseEntityBlock {
    public static final MapCodec<NozzleFilterBlock> CODEC = simpleCodec(NozzleFilterBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    /** The dye colour of the wool the filter was made from; drives both its model and its drop. */
    public static final EnumProperty<DyeColor> COLOR = EnumProperty.create("color", DyeColor.class);

    public NozzleFilterBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(COLOR, DyeColor.WHITE));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, COLOR);
    }

    /** The block carries its own model (BlockBench-style), so render it as a normal model, not a BE renderer. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    // --- Hitbox: follows the authored model rather than a full cube ----------------------------------

    // The shape for a filter pointing UP: the full-footprint body up to 15, capped by the flange that
    // rings the last pixel (1..15 across, 15..16 up). The flange is four boxes in the model, a frame
    // around the open mesh face; the hitbox takes its bounding box instead, so the mesh is walked on
    // rather than stepped into. Rotated per FACING to match the model's own rotations.
    private static final Map<Direction, VoxelShape> SHAPES = buildShapes();

    private static Map<Direction, VoxelShape> buildShapes() {
        VoxelShape up = Shapes.or(Block.box(0, 0, 0, 16, 15, 16), Block.box(1, 15, 1, 15, 16, 15));
        VoxelShape north = rotateX(up); // x:90
        Map<Direction, VoxelShape> map = new EnumMap<>(Direction.class);
        map.put(Direction.UP, up);
        map.put(Direction.DOWN, rotateX(north));            // x:180
        map.put(Direction.NORTH, north);                    // x:90, y:0
        map.put(Direction.EAST, rotateY(north));            // x:90, y:90
        map.put(Direction.SOUTH, rotateY(rotateY(north)));  // x:90, y:180
        map.put(Direction.WEST, rotateY(rotateY(rotateY(north)))); // x:90, y:270
        return map;
    }

    // One 90-degree turn about X, matching a vanilla blockstate x-rotation: (y, z) -> (z, 1 - y).
    private static VoxelShape rotateX(VoxelShape shape) {
        VoxelShape[] out = { Shapes.empty() };
        shape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                out[0] = Shapes.or(out[0], Shapes.box(x1, z1, 1 - y2, x2, z2, 1 - y1)));
        return out[0];
    }

    // One 90-degree turn about Y, matching a vanilla blockstate y-rotation: (x, z) -> (1 - z, x).
    private static VoxelShape rotateY(VoxelShape shape) {
        VoxelShape[] out = { Shapes.empty() };
        shape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                out[0] = Shapes.or(out[0], Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)));
        return out[0];
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.getOrDefault(state.getValue(FACING), Shapes.block());
    }

    // --- Attachment: the filter rides on its fan, so it pops off if that fan is broken ---------------

    /**
     * Survives only while an Encased Fan sits on the attachment side (opposite the filter's FACING) AND
     * still faces the filter. The nozzle points away from its fan, so an aligned fan's own facing equals
     * the filter's FACING; rotate the fan away and the filter pops off.
     */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!CreateCompatibility.installed()) {
            return true; // no Create to validate against: leave the block be
        }
        Direction facing = state.getValue(FACING);
        return CreateCompatibility.fanFacing(level, pos.relative(facing.getOpposite())) == facing;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!canSurvive(state, level, pos)) {
            // Fan gone -> pop off. The wool comes back via the loot table; drop the nozzle here too.
            if (level instanceof Level lvl) {
                NozzleFilterEvents.dropNozzle(lvl, pos);
            }
            return Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new NozzleFilterBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (type != ModBlockEntities.NOZZLE_FILTER.get()) {
            return null;
        }
        if (level.isClientSide) {
            // Client: spawn the air particles the nozzle used to make around itself.
            return (lvl, pos, st, be) -> NozzleFilterBlockEntity.clientTick(lvl, pos, st, (NozzleFilterBlockEntity) be);
        }
        return (lvl, pos, st, be) -> NozzleFilterBlockEntity.serverTick(lvl, pos, st, (NozzleFilterBlockEntity) be);
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.setValue(FACING, mirror.mirror(state.getValue(FACING)));
    }
}
