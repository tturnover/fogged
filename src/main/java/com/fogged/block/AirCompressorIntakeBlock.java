package com.fogged.block;

import java.util.EnumMap;
import java.util.Map;

import com.fogged.registry.ModBlocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The back half of an air compressor: its intake, a half-block in the cell behind it, ending in
 * the intake fan. Never obtained -- it is set down with its compressor and goes with it, as a bed's
 * other half does. {@link #FACING} is its compressor's: the compressor stands one step that way.
 *
 * <p>Breaking it breaks the compressor (which drops); it drops nothing itself. The air comes in
 * through its open end, from the cell beyond it (see {@link AirCompressorCore}).
 */
public class AirCompressorIntakeBlock extends Block implements EntityBlock {

    public static final DirectionProperty FACING = AirCompressorBlock.FACING;

    // The half of its cell next to the compressor. Authored for a compressor facing NORTH, which puts
    // the intake south of it, against the intake cell's north face.
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Map.of(
            Direction.NORTH, Block.box(0, 0, 0, 16, 16, 8),
            Direction.SOUTH, Block.box(0, 0, 8, 16, 16, 16),
            Direction.EAST, Block.box(8, 0, 0, 16, 16, 16),
            Direction.WEST, Block.box(0, 0, 0, 8, 16, 16),
            Direction.UP, Block.box(0, 8, 0, 16, 16, 16),
            Direction.DOWN, Block.box(0, 0, 0, 16, 8, 16)));

    public AirCompressorIntakeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** Where this intake's compressor stands. */
    public static BlockPos compressorPos(BlockPos pos, BlockState state) {
        return pos.relative(state.getValue(FACING));
    }

    private static boolean compressorThere(BlockState intake, BlockState there) {
        return there.getBlock() instanceof CompressorBlock
                && there.getValue(AirCompressorBlock.FACING) == intake.getValue(FACING);
    }

    // Without its compressor it goes too.
    @Override
    protected BlockState updateShape(BlockState state, Direction side, BlockState neighbour, LevelAccessor level,
            BlockPos pos, BlockPos neighbourPos) {
        if (side == state.getValue(FACING) && !compressorThere(state, neighbour)) {
            return Blocks.AIR.defaultBlockState();
        }
        return state;
    }

    // Broken by a player: the compressor goes with it, and drops as if it were the one broken.
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        BlockPos at = compressorPos(pos, state);
        BlockState compressor = level.getBlockState(at);
        if (!level.isClientSide && compressorThere(state, compressor)) {
            if (player.isCreative()) {
                level.removeBlock(at, false);
            } else {
                level.destroyBlock(at, true, player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return AirCompressorIntakeBlockEntity.create(pos, state);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBlocks.AIR_COMPRESSOR.get());
    }
}
