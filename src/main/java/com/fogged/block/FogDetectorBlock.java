package com.fogged.block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fogged.Config;
import com.fogged.Fogged;
import com.fogged.PlaneSensor;
import com.fogged.registry.ModBlockEntities;
import com.fogged.registry.ModBlocks;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Custom-modelled block with vertical-facing + yaw-spin + waterlogging placement behavior, and the
 * base of a stackable detector tower:
 * <ul>
 *   <li>{@code VERTICAL_DIRECTION} — points UP or DOWN; DOWN only when placed against a ceiling.</li>
 *   <li>{@code FACING} — 4-way horizontal yaw spin, from the placing player's look direction.</li>
 *   <li>{@code WATERLOGGED} — holds water when placed in a fluid.</li>
 * </ul>
 *
 * <p>Right-clicking this block (or any extension above it) while holding a {@code fog_detector} item
 * consumes one item and stacks a {@link FogDetectorExtensionBlock} on top, up to
 * {@link #MAX_EXTENSIONS}. The collision/outline shape is built from the model elements
 * ({@link #DETECTOR_SHAPE}) and rotated to match the blockstate.
 */
public class FogDetectorBlock extends HorizontalDirectionalBlock implements SimpleWaterloggedBlock, EntityBlock {
    public static final MapCodec<FogDetectorBlock> CODEC = simpleCodec(FogDetectorBlock::new);
    public static final DirectionProperty VERTICAL_DIRECTION = BlockStateProperties.VERTICAL_DIRECTION;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    /** True while the column is detecting fog (redstone output > 0): lights the base's bulb. */
    public static final BooleanProperty LIT = BlockStateProperties.LIT;

    /** Items that reskin the column's pole: any stripped log (data-driven tag), like the wool on a filter. */
    public static final TagKey<Item> STRIPPED_LOGS =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "stripped_logs"));

    /** Default pole wood when a column is placed / has never been reskinned. */
    public static final ResourceLocation DEFAULT_LOG =
            ResourceLocation.withDefaultNamespace("stripped_oak_log");

    /** Model data key carrying the column's chosen stripped-log id to the dynamic pole model (client). */
    public static final ModelProperty<ResourceLocation> LOG_ID = new ModelProperty<>();

    /** Max column length (base + extensions). Capped at 15 so one segment == one redstone level. */
    public static final int MAX_COLUMN = 15;
    /** Max number of extensions that may be stacked above a base detector. */
    public static final int MAX_EXTENSIONS = MAX_COLUMN - 1;

    // Shape follows the model elements (pixels): the base slab and the tall antenna only. The redstone
    // bulb is decorative (crossed billboard slabs), so it is intentionally left out of the hitbox. The
    // antenna rises above the block top (y up to 19), so its VoxelShape overhangs the block: Block.box
    // builds an ArrayVoxelShape for out-of-cell coords, and the flip/rotate below carry the overhang into
    // the DOWN and yaw-rotated orientations.
    private static final VoxelShape DETECTOR_SHAPE = Shapes.or(
            Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0),
            Block.box(6.5, 2.0, 4.0, 9.5, 18.0, 12.0));

    // Per-orientation shape cache (block is a singleton).
    private final Map<Long, VoxelShape> shapeCache = new ConcurrentHashMap<>();

    public FogDetectorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(VERTICAL_DIRECTION, Direction.UP)
                .setValue(WATERLOGGED, Boolean.FALSE)
                .setValue(LIT, Boolean.FALSE)); // starts unlit; the block entity lights it when detecting fog
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, VERTICAL_DIRECTION, WATERLOGGED, LIT);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        // Vertical facing from the player's look (up/down); a clicked up/down face wins outright.
        Direction clicked = context.getClickedFace();
        Direction vertical = clicked.getAxis().isVertical() ? clicked : lookVertical(context);
        // Must rest on a full block on the surface side (opposite where it points).
        BlockPos supportPos = pos.relative(vertical.getOpposite());
        if (!level.getBlockState(supportPos).isCollisionShapeFullBlock(level, supportPos)) {
            return null;
        }
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite()) // yaw from player
                .setValue(VERTICAL_DIRECTION, vertical)
                .setValue(WATERLOGGED, level.getFluidState(pos).getType() == Fluids.WATER);
    }

    private static Direction lookVertical(BlockPlaceContext context) {
        Player player = context.getPlayer();
        return player != null && player.getXRot() < 0.0F ? Direction.UP : Direction.DOWN;
    }

    /** Rests on a full block on the surface side (opposite the direction it points). */
    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos supportPos = pos.relative(state.getValue(VERTICAL_DIRECTION).getOpposite());
        return level.getBlockState(supportPos).isCollisionShapeFullBlock(level, supportPos);
    }

    // --- Stacking interaction ----------------------------------------------

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
            Player player, InteractionHand hand, BlockHitResult hitResult) {
        // Right-click any segment with a stripped log (fogged:stripped_logs tag) -> reskin the whole
        // column's pole to that wood, stored by item key on the base entity. Not consumed.
        if (!player.isSecondaryUseActive() && stack.is(STRIPPED_LOGS)) {
            if (!level.isClientSide) {
                ResourceLocation logId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                FogDetectorBlockEntity be = baseEntity(level, pos, state);
                if (be != null && !logId.equals(be.getLogId())) {
                    be.setLogId(logId);
                    SoundType sound = state.getSoundType();
                    level.playSound(null, pos, sound.getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS,
                            (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch());
                }
            }
            return ItemInteractionResult.sidedSuccess(level.isClientSide);
        }

        // Sneak to bypass and place a detector normally instead of extending.
        if (player.isSecondaryUseActive()
                || !stack.is(ModBlocks.FOG_DETECTOR.get().asItem())) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        // Tower grows along VERTICAL_DIRECTION; the base sits at the opposite end.
        Direction stackDir = state.getValue(VERTICAL_DIRECTION);
        BlockPos base = pos;
        while (level.getBlockState(base.relative(stackDir.getOpposite())).getBlock() instanceof FogDetectorBlock) {
            base = base.relative(stackDir.getOpposite());
        }
        BlockPos tip = pos;
        while (level.getBlockState(tip.relative(stackDir)).getBlock() instanceof FogDetectorBlock) {
            tip = tip.relative(stackDir);
        }

        int extensions = Math.abs(tip.getY() - base.getY()); // stackDir is vertical
        BlockPos placePos = tip.relative(stackDir);
        if (extensions >= MAX_EXTENSIONS || !level.getBlockState(placePos).canBeReplaced()) {
            return ItemInteractionResult.CONSUME; // at limit / blocked: swallow, don't place a detector
        }

        if (!level.isClientSide) {
            BlockState baseState = level.getBlockState(base);
            BlockState newState = ModBlocks.FOG_DETECTOR_EXTENSION.get().defaultBlockState()
                    .setValue(FACING, baseState.getValue(FACING))
                    .setValue(VERTICAL_DIRECTION, baseState.getValue(VERTICAL_DIRECTION))
                    .setValue(WATERLOGGED, level.getFluidState(placePos).getType() == Fluids.WATER);
            level.setBlock(placePos, newState, Block.UPDATE_ALL);
            SoundType sound = newState.getSoundType();
            level.playSound(null, placePos, sound.getPlaceSound(), net.minecraft.sounds.SoundSource.BLOCKS,
                    (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    // --- Fluids -------------------------------------------------------------

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
            LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (!canSurvive(state, level, pos)) {
            return Blocks.AIR.defaultBlockState(); // support gone -> pop off and drop, cascading the tower
        }
        if (state.getValue(WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    // --- Shape (follows the model) -----------------------------------------

    /** The un-rotated shape (default orientation: FACING=SOUTH, VERTICAL_DIRECTION=UP). */
    protected VoxelShape baseShape() {
        return DETECTOR_SHAPE;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        Direction facing = state.getValue(FACING);
        boolean down = state.getValue(VERTICAL_DIRECTION) == Direction.DOWN;
        long key = ((long) facing.ordinal() << 1) | (down ? 1 : 0);
        return shapeCache.computeIfAbsent(key, k -> {
            VoxelShape shape = baseShape();
            if (down) {
                shape = flipX(shape); // VERTICAL_DIRECTION=DOWN -> blockstate x=180
            }
            return rotateY(shape, ((int) facing.toYRot()) / 90); // FACING -> blockstate y
        });
    }

    /**
     * Rotate a 0..1 shape clockwise (viewed from above) about the vertical axis, {@code times} * 90
     * degrees, matching the blockstate {@code y} rotation: (x,z) -> (1 - z, x).
     */
    private static VoxelShape rotateY(VoxelShape shape, int times) {
        VoxelShape current = shape;
        for (int i = 0; i < times; i++) {
            VoxelShape[] acc = {Shapes.empty()};
            current.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                    acc[0] = Shapes.or(acc[0], Shapes.box(1 - z2, y1, x1, 1 - z1, y2, x2)));
            current = acc[0];
        }
        return current;
    }

    /** Flip a 0..1 shape 180 degrees about the X axis (for the DOWN-facing variant). */
    private static VoxelShape flipX(VoxelShape shape) {
        VoxelShape[] acc = {Shapes.empty()};
        shape.forAllBoxes((x1, y1, z1, x2, y2, z2) ->
                acc[0] = Shapes.or(acc[0], Shapes.box(x1, 1 - y2, 1 - z2, x2, 1 - y1, 1 - z1)));
        return acc[0];
    }

    // --- Redstone -----------------------------------------------------------

    /** True when this segment is the bottom of its column (nothing supporting it is a detector). */
    public static boolean isBase(BlockGetter level, BlockPos pos, BlockState state) {
        Direction toward = state.getValue(VERTICAL_DIRECTION).getOpposite();
        return !(level.getBlockState(pos.relative(toward)).getBlock() instanceof FogDetectorBlock);
    }

    /**
     * Redstone strength for the column starting at {@code basePos}: scales 0..15 by the fraction of
     * segments submerged under the fog plane. Column length is capped at {@link #MAX_COLUMN} so one
     * segment is worth at most one level.
     */
    public static int columnPower(Level level, BlockPos basePos, BlockState baseState) {
        if (!Config.ITEMS_ENABLED.get()) {
            return 0; // items master switch off: the detector goes inert.
        }
        Direction stackDir = baseState.getValue(VERTICAL_DIRECTION);
        double surfaceY = Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
        int length = 0;
        int submerged = 0;
        BlockPos p = basePos;
        while (length < MAX_COLUMN && level.getBlockState(p).getBlock() instanceof FogDetectorBlock) {
            length++;
            // World-space height (handles Sable contraptions, whose blocks sit in a far-off plot).
            if (PlaneSensor.worldY(level, p) < surfaceY) { // segment under the fog surface -> in contact
                submerged++;
            }
            p = p.relative(stackDir);
        }
        return length == 0 ? 0 : Mth.clamp(Math.round(15.0F * submerged / length), 0, 15);
    }

    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // Only the base emits; it reflects the whole column.
        if (!(level instanceof Level lvl) || !isBase(level, pos, state)) {
            return 0;
        }
        return columnPower(lvl, pos, state);
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        // Strong-power the block it rests on so the signal conducts through it.
        return direction == state.getValue(VERTICAL_DIRECTION) ? getSignal(state, level, pos, direction) : 0;
    }

    // --- Block entity (drives redstone re-evaluation as the fog plane drifts) -----

    /** The block entity on the base of this segment's column (walks down; only the base carries one). */
    static FogDetectorBlockEntity baseEntity(BlockGetter level, BlockPos pos, BlockState state) {
        Direction down = state.getValue(VERTICAL_DIRECTION).getOpposite();
        BlockPos p = pos;
        while (level.getBlockState(p.relative(down)).getBlock() instanceof FogDetectorBlock) {
            p = p.relative(down);
        }
        return level.getBlockEntity(p) instanceof FogDetectorBlockEntity be ? be : null;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FogDetectorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide || type != ModBlockEntities.FOG_DETECTOR.get()) {
            return null;
        }
        return (lvl, pos, st, be) -> FogDetectorBlockEntity.serverTick(lvl, pos, st, (FogDetectorBlockEntity) be);
    }

    // --- Rotation/mirror ----------------------------------------------------

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
