package com.fogged.block;

import com.fogged.Config;
import com.fogged.MurkLayer;
import com.fogged.registry.ModParticles;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The murk's visible surface: a layer of these, a few blocks thick, lies at the breathing boundary
 * and rises and falls in waves ({@link MurkLayer} places, moves and removes them). An ordinary block,
 * so the chunk renderer -- vanilla, Sodium, a shader pack -- draws it like any other, with no renderer
 * of this mod's own in the way. The boundary itself stays where the config puts it: this is only its
 * picture, and nothing breathes or suffocates by it.
 *
 * <p>{@link #LEVEL} is how much of the cell it fills (1 the lower half, 2 all of it); {@link #SURFACE}
 * whether no murk lies above it, which is where the vapour is drawn.
 *
 * <p>There is nothing to it to touch: no collision, no outline (a click goes straight through to the
 * block behind), light passes, anything placed into it replaces it. Its look is all in the models
 * ({@code models/block/murk_*.json}), tinted the murk's colour, with the textures laid out in world
 * space by {@link com.fogged.MurkModel}. Where it ends at open air, foam pours off it
 * (see {@link #animateTick}).
 */
public class MurkBlock extends Block {

    public static final IntegerProperty LEVEL = IntegerProperty.create("level", 1, 2);
    public static final BooleanProperty SURFACE = BooleanProperty.create("surface");

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    public MurkBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(LEVEL, 2).setValue(SURFACE, true));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LEVEL, SURFACE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return true;
    }

    // Murk against murk: no wall between them unless this side stands taller than its neighbour. Murk
    // above or below always hides the face between.
    @Override
    protected boolean skipRendering(BlockState state, BlockState adjacent, Direction side) {
        if (!adjacent.is(this)) {
            return false;
        }
        if (side.getAxis().isVertical()) {
            return true;
        }
        return adjacent.getValue(LEVEL) >= state.getValue(LEVEL);
    }

    /**
     * Foam pouring off the rim, like the mist off liquid nitrogen: from each side of a surface cell
     * that opens onto plain air, now and then, a heavy puff that creeps outward and over any drop.
     */
    @Override
    public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
        if (!state.getValue(SURFACE)) {
            return;
        }
        float[] c = Config.foamColor();
        ColorParticleOption foam = ColorParticleOption.create(ModParticles.FOG_SPILL.get(),
                lift(c[0]), lift(c[1]), lift(c[2]));
        double top = pos.getY() + state.getValue(LEVEL) * 0.5;
        for (Direction d : SIDES) {
            if (!level.getBlockState(pos.relative(d)).isAir()) {
                continue;
            }
            double along = random.nextDouble() - 0.5;
            double x = pos.getX() + 0.5 + d.getStepX() * 0.5 + d.getStepZ() * along;
            double z = pos.getZ() + 0.5 + d.getStepZ() * 0.5 + d.getStepX() * along;
            double y = top - 0.1 - random.nextDouble() * 0.2;
            double speed = 0.02 + random.nextDouble() * 0.03;
            level.addParticle(foam, x, y, z, d.getStepX() * speed, 0.0, d.getStepZ() * speed);
        }
    }

    private static float lift(float c) {
        return c + (1.0F - c) * 0.5F;
    }
}
