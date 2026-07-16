package com.fogged.block;

import javax.annotation.Nullable;

import com.fogged.Config;
import com.fogged.FogMoss;
import com.fogged.registry.ModBlocks;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The fog eye: the chorus-flower-like head that rides the top of a {@link FogEyeStemBlock} column and
 * drives its growth.
 *
 * <p>An eye is seeded rarely on a fresh {@link FogMoss} puddle (far more rarely than
 * {@link FoggyGrassBlock foggy grass}, and the warmer the biome the more of them). What it does then
 * is vanilla's chorus flower, near enough line for line: it grows a column two to five blocks tall,
 * throws a random handful of arms out sideways when the column gets too long, and each arm is a fresh
 * eye one {@link #AGE} older that carries on by itself, until age {@link #MAX_AGE} stops the forking.
 * Same segment lengths, same arm counts, same "is there room" checks, so plants come out gnarled the
 * way chorus does.
 *
 * <p>Two things are ours rather than vanilla's, and both follow from the eye chasing the fog:
 * <ul>
 *   <li><b>It never dies.</b> Where a chorus flower would go dead and inert, the eye keeps its head
 *       and climbs on. That is the whole point of the plant: the eye rides the boundary, so it also
 *       <em>sinks</em>, eating its own stem back down when the plane drops. A whole forked plant
 *       follows it down — the arms fold away as they run out of stem (see {@link #sink}) and the trunk
 *       carries on to its root.</li>
 *   <li><b>The climb wins over the shape rules.</b> Vanilla's segment rule is what makes a chorus
 *       plant stop and branch; if we obeyed it outright a plant would stall short of a surface that
 *       can sit 60 blocks up. So when the rules would have it die — out of ages, or no room for arms —
 *       the eye grows straight up instead, as long as the structure allows it. Plants read chorus-like
 *       and branchy low down, straighter as they run for the surface.</li>
 * </ul>
 *
 * <p>The two clocks do different jobs. Growth steps run on scheduled ticks
 * ({@link Config#FOG_EYE_GROW_TICKS}) rather than vanilla's random ticks, so a climb is paced instead
 * of streaky, and once the eye settles at the boundary it drops to a slow {@link #IDLE_TICKS}
 * heartbeat that only watches for the plane moving. The <em>pull-back</em> is the random-tick half:
 * an eye resting open ducks back down its own branch on a {@link Config#FOG_EYE_RETREAT_CHANCE} roll,
 * so it keeps the world's own rhythm and answers to randomTickSpeed like any other plant.
 */
public class FogEyeBlock extends Block {
    /** Oldest an eye gets. Vanilla's live chorus flowers span the same 0..4; its dead 5 has no use here. */
    public static final int MAX_AGE = 4;
    public static final IntegerProperty AGE = IntegerProperty.create("age", 0, MAX_AGE);

    /**
     * Which way the eye points: away from whatever it grew off. UP for a head seated on the stem (or
     * moss) beneath it, and sideways for an arm still hanging on the flank of the column that threw
     * it. The blockstate turns the whole block to match, so an arm's base plugs into its stem.
     */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /**
     * Whether the eye is open. Only a head seated on something below ({@link #FACING} UP) and level
     * with the fog surface opens: an arm hanging off a stem's side has nothing under it to brace
     * against, so it stays shut however high it hangs.
     */
    public static final BooleanProperty OPEN = BooleanProperty.create("open");

    public static final MapCodec<FogEyeBlock> CODEC = simpleCodec(FogEyeBlock::new);

    /**
     * How often a settled eye looks up to see whether the plane has moved. Only that: the pull-back is
     * on random ticks now. Twenty seconds is far finer than the boundary drifts (a block every few
     * minutes) and costs nothing while nothing is happening.
     */
    private static final int IDLE_TICKS = 400;

    public FogEyeBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(AGE, 0).setValue(FACING, Direction.UP).setValue(OPEN, false));
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AGE, FACING, OPEN);
    }

    /** Placed by hand: point away from whatever it finds to hang on. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, facingFor(context.getLevel(), context.getClickedPos()));
    }

    /**
     * Which way an eye at {@code pos} should point: UP when it is seated on stem or moss, otherwise
     * away from the single stem it hangs off. Falls back to UP, which is what a seeded head gets.
     */
    private static Direction facingFor(BlockGetter level, BlockPos pos) {
        BlockState below = level.getBlockState(pos.below());
        if (below.is(ModBlocks.FOG_EYE_STEM.get()) || FogMoss.isFogMoss(below)) {
            return Direction.UP;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(dir)).is(ModBlocks.FOG_EYE_STEM.get())) {
                return dir.getOpposite(); // point away from the stem, not into it
            }
        }
        return Direction.UP;
    }

    /**
     * An eye rides its own stem or sits straight on the moss root; a freshly-thrown arm instead hangs
     * off the side of the stem that threw it, so it also survives on a single horizontal stem with
     * nothing but air around and below it. This is vanilla's chorus flower rule, moss for end stone.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!level.getFluidState(pos).isEmpty()) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (below.is(ModBlocks.FOG_EYE_STEM.get()) || FogMoss.isFogMoss(below)) {
            return true;
        }
        if (!below.isAir()) {
            return false;
        }
        boolean attached = false;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState side = level.getBlockState(pos.relative(dir));
            if (side.is(ModBlocks.FOG_EYE_STEM.get())) {
                if (attached) {
                    return false; // two stems to hang from is a loop, not an arm
                }
                attached = true;
            } else if (!side.isAir()) {
                return false;
            }
        }
        return attached;
    }

    /**
     * Keep {@link #FACING} derived from what actually holds the eye up, rather than trusting whatever
     * it was placed with. Growth sets the facing itself, but nothing else does: an eye from
     * {@code /setblock}, from a structure, or saved before this property existed all arrive pointing
     * up, and would sit with their base aimed at thin air instead of at the stem beside them. Working
     * it out here means any neighbour change puts it right, and an arm placed before the stem that
     * throws it exists still ends up facing correctly once that stem lands.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction dir, BlockState neighbour,
            LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        if (dir != Direction.UP && !canSurvive(state, level, pos)) {
            level.scheduleTick(pos, this, 1);
        }
        return state.setValue(FACING, facingFor(level, pos));
    }

    /**
     * However an eye arrives — seeded, climbing, thrown as an arm, or placed by hand — it starts
     * stepping. An eye merely changing its own state (opening, or counting its rest down) is not an
     * arrival: re-arming there would replace the slow idle step with the fast growth one, because a
     * pending tick wins over a later one scheduled for the same block.
     */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        if (!oldState.is(this)) {
            level.scheduleTick(pos, this, Config.FOG_EYE_GROW_TICKS.getAsInt());
        }
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!canSurvive(state, level, pos)) {
            level.destroyBlock(pos, true);
            return;
        }
        state = fixFacing(level, pos, state);
        if (!Config.FLORA_ENABLED.get()) {
            return; // flora off: stop stepping. randomTick re-arms us if it is switched back on.
        }
        int target = targetY(level);
        if (pos.getY() == target) {
            rest(level, pos, state, random);
            return;
        }
        BlockPos next;
        if (pos.getY() < target) {
            next = grow(level, pos, state, random);
            if (next == null) {
                return; // this head branched away into stem; its arms carry their own schedules
            }
        } else {
            next = sink(level, pos, state);
            if (next == null) {
                return; // a spent arm folded away
            }
        }
        // Moved = keep racing at the growth rate; stuck = settle into the slow idle step.
        level.scheduleTick(next, this, next.equals(pos) ? IDLE_TICKS : Config.FOG_EYE_GROW_TICKS.getAsInt());
    }

    @Override
    public boolean isRandomlyTicking(BlockState state) {
        return true;
    }

    /**
     * Random ticks do no growing — the climb is paced by scheduled ticks — but they are what makes an
     * open eye duck back down, so the pull-back runs at the world's own rhythm and answers to
     * randomTickSpeed like any other plant. They also restore a schedule the eye lost while flora was
     * off.
     */
    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (!level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, 1);
        }
        if (!Config.FLORA_ENABLED.get() || !state.getValue(OPEN)) {
            return; // only a head resting open at the surface has anywhere to pull back from
        }
        if (random.nextDouble() >= Config.FOG_EYE_RETREAT_CHANCE.get() || pos.getY() != targetY(level)) {
            return;
        }
        pullBack(level, pos, state, random); // the eye it lands on arms its own climb back up
    }

    /**
     * Point the eye away from whatever is actually holding it up, correcting it if it is not already.
     *
     * <p>Growth points a head the right way when it places it, and {@link #updateShape} fixes one whose
     * neighbours change — but between them they miss the case that matters: a head that is simply
     * sitting there. An arm that cannot climb gets no neighbour updates for as long as it hangs, and an
     * eye grown before this property existed loads with the default (UP) baked in. Either way it points
     * up with its base aimed at thin air while its stem is off to the side. Since the eye ticks anyway,
     * settling this here means every head comes right within a tick, whatever it was placed with.
     */
    private BlockState fixFacing(ServerLevel level, BlockPos pos, BlockState state) {
        Direction facing = facingFor(level, pos);
        if (state.getValue(FACING) == facing) {
            return state;
        }
        BlockState fixed = state.setValue(FACING, facing);
        level.setBlock(pos, fixed, UPDATE_CLIENTS);
        return fixed;
    }

    /** The Y the eye wants to rest at: the top block under the plane, less the configured offset. */
    private static int targetY(ServerLevel level) {
        return FogMoss.bandTopY(level) - Config.FOG_EYE_TOP_OFFSET.getAsInt();
    }

    /**
     * The eye is level with the fog: open it, then idle a step at a time until its rest runs out and
     * it pulls back into the murk. An eye that has just arrived opens and draws a fresh rest.
     */
    private void rest(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        // An arm hanging off the flank of a stem has nothing under it to brace against, so it cannot
        // open however high it hangs. It only waits here until a climb seats it.
        if (state.getValue(FACING) == Direction.UP && !state.getValue(OPEN)) {
            level.setBlock(pos, state.setValue(OPEN, true), UPDATE_CLIENTS);
        }
        level.scheduleTick(pos, this, IDLE_TICKS);
    }

    /**
     * Duck back down the branch: the eye drops a random 1..N blocks, where N is its own column down to
     * the base of the branch, eating the stem as it goes. It shuts on the way, and since it is now
     * below the fog it simply climbs back — the pull-back is a blink, the recovery is a slow creep.
     *
     * @return where the eye ended up, or {@code pos} if it is sitting on the base already
     */
    private BlockPos pullBack(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        int stem = heightFromRoot(level, pos) - 1; // stem under this head, down to the branch base
        if (stem <= 0) {
            return pos; // nothing to pull back into
        }
        int depth = 1 + random.nextInt(stem);
        BlockState shut = state.setValue(OPEN, false);
        BlockPos at = pos;
        for (int i = 0; i < depth; i++) {
            BlockPos below = at.below();
            if (!level.getBlockState(below).is(ModBlocks.FOG_EYE_STEM.get())) {
                break; // hit the base early (terrain changed under us)
            }
            level.removeBlock(at, false);
            // The head takes the stem's place, so it has to point away from whatever held that stem up
            // -- not simply up. The deepest a retreat goes is the base of the branch, and on an arm
            // that base is hanging off the trunk with nothing underneath: a head landing there points
            // sideways, and assuming up would leave its base aimed at thin air.
            level.setBlock(below, shut.setValue(FACING, facingFor(level, below)), UPDATE_CLIENTS);
            at = below;
        }
        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, at, 0);
        return at;
    }

    /**
     * One growth step, following vanilla's chorus flower: climb while the column is still short enough,
     * otherwise throw arms, otherwise (where vanilla would die) climb anyway so the eye keeps its date
     * with the fog surface.
     *
     * @return where the eye ended up, {@code pos} if it could not move, or null if it branched away
     *         into stem and no longer exists at {@code pos}
     */
    @Nullable
    private BlockPos grow(ServerLevel level, BlockPos pos, BlockState state, RandomSource random) {
        BlockPos above = pos.above();
        if (!level.isEmptyBlock(above) || above.getY() >= level.getMaxBuildHeight()) {
            return pos; // something in the way: wait for it to clear
        }
        if (heightFromRoot(level, pos) >= Config.FOG_EYE_MAX_HEIGHT.getAsInt()) {
            return pos; // capped: stop short rather than chase a far-off plane forever
        }
        int age = state.getValue(AGE);

        // Vanilla's rule for whether the column may run on: straight off the root it always may, and on
        // a stem only while the run below is short (2..5 blocks, one longer when the root is near).
        boolean mayClimb = false;
        boolean nearRoot = false;
        BlockState below = level.getBlockState(pos.below());
        if (FogMoss.isFogMoss(below)) {
            mayClimb = true;
        } else if (below.is(ModBlocks.FOG_EYE_STEM.get())) {
            int run = 1;
            for (int i = 0; i < 4; i++) {
                BlockState under = level.getBlockState(pos.below(run + 1));
                if (!under.is(ModBlocks.FOG_EYE_STEM.get())) {
                    nearRoot = FogMoss.isFogMoss(under);
                    break;
                }
                run++;
            }
            if (run < 2 || run <= random.nextInt(nearRoot ? 5 : 4)) {
                mayClimb = true;
            }
        } else if (below.isAir()) {
            mayClimb = true; // an arm just thrown clear of its column
        }

        // Structural room to climb. Unlike the segment rule above this is not negotiable: growing into
        // a spot that touches another part of the plant would make a loop, and a loop tears itself down.
        boolean roomAbove = allNeighborsEmpty(level, above, null) && level.isEmptyBlock(pos.above(2));

        if (mayClimb && roomAbove) {
            return climb(level, pos, state);
        }
        if (age < MAX_AGE && throwArms(level, pos, age, nearRoot, random)) {
            return null;
        }
        // Vanilla would go dead here. The eye keeps climbing instead, if the structure allows it.
        return roomAbove ? climb(level, pos, state) : pos;
    }

    // Vanilla's branching: try a random handful of random directions (one more try when the root is
    // near), sprouting an arm on each that has room. If any took, this head dies into stem.
    private boolean throwArms(ServerLevel level, BlockPos pos, int age, boolean nearRoot, RandomSource random) {
        int tries = random.nextInt(4) + (nearRoot ? 1 : 0);
        boolean any = false;
        for (int i = 0; i < tries; i++) {
            Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            BlockPos arm = pos.relative(dir);
            if (level.isEmptyBlock(arm) && level.isEmptyBlock(arm.below())
                    && allNeighborsEmpty(level, arm, dir.getOpposite())) {
                placeEye(level, arm, age + 1, dir);
                any = true;
            }
        }
        if (any) {
            level.setBlock(pos, FogEyeStemBlock.getStateWithConnections(
                    level, pos, ModBlocks.FOG_EYE_STEM.get().defaultBlockState()), UPDATE_CLIENTS);
        }
        return any;
    }

    // Leave a stem behind and re-form one block up, keeping the lineage's age. The stem is placed first
    // and wires itself to what it touches; the eye landing above then updates its UP connection.
    private BlockPos climb(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlock(pos, FogEyeStemBlock.getStateWithConnections(
                level, pos, ModBlocks.FOG_EYE_STEM.get().defaultBlockState()), UPDATE_CLIENTS);
        placeEye(level, pos.above(), state.getValue(AGE), Direction.UP);
        return pos.above();
    }

    /**
     * Eat the stem beneath and re-form one block down, so the plant follows a sinking plane.
     *
     * <p>An eye that has run out of stem to eat is either the trunk on its moss root, which is as low
     * as the plant goes, or an arm that has retreated to the joint it was thrown from. A spent arm
     * <em>folds</em>: it withers, and hands its head back to the joint by turning that stem into an
     * eye, which then carries on down the column below it. Folding rather than simply withering is
     * what lets a forked plant follow the plane all the way down — remember that branching kills the
     * head that branched (it becomes the joint), so after the first fork every head is an arm, and
     * arms that only withered would leave an eyeless skeleton standing.
     *
     * <p>A joint holding several arms is only promoted by the last of them to fold, so arms still
     * growing keep the stem they hang from.
     *
     * @return where the eye ended up, {@code pos} if it is rooted and can go no lower, or null if it
     *         folded away (either handing its head to the joint, or spent)
     */
    @Nullable
    private BlockPos sink(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos below = pos.below();
        BlockState under = level.getBlockState(below);
        if (under.is(ModBlocks.FOG_EYE_STEM.get())) {
            level.removeBlock(pos, false);
            // As in pullBack: the head inherits the stem's spot, so it inherits how that stem was held
            // up. Landing on the base of an arm means hanging off the trunk sideways, not sitting on it.
            placeEye(level, below, state.getValue(AGE), facingFor(level, below));
            return below;
        }
        if (FogMoss.isFogMoss(under)) {
            return pos; // the trunk, home on its root
        }
        BlockPos joint = armJoint(level, pos);
        level.removeBlock(pos, false);
        if (joint != null && lastArmOff(level, joint)) {
            // The joint is the head that branched, so it gets its own age back: one younger than the
            // arm it threw. Folding therefore unwinds the ages it spent, and a plant that retracts all
            // the way to its root is age 0 again -- free to grow up as gnarled as the first time.
            placeEye(level, joint, Math.max(0, state.getValue(AGE) - 1), facingFor(level, joint));
        }
        return null;
    }

    // The single stem an arm hangs from, or null if it is not hanging off one (canSurvive already
    // guarantees there is at most one, so the first found is the one).
    @Nullable
    private static BlockPos armJoint(ServerLevel level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(dir);
            if (level.getBlockState(side).is(ModBlocks.FOG_EYE_STEM.get())) {
                return side;
            }
        }
        return null;
    }

    // Whether a joint has nothing left hanging on it and is free to take a head back: no other arm on
    // its sides, and the top of its column (which a joint always is — the head that branched here grew
    // no further up).
    private static boolean lastArmOff(ServerLevel level, BlockPos joint) {
        return allNeighborsEmpty(level, joint, null) && level.isEmptyBlock(joint.above());
    }

    // Place an eye pointing away from what it grew off, and let the world know something grew, the
    // way vanilla's chorus flower does. A fresh head is always shut; opening is the surface's business.
    private void placeEye(Level level, BlockPos pos, int age, Direction facing) {
        level.setBlock(pos, defaultBlockState().setValue(AGE, age).setValue(FACING, facing), UPDATE_CLIENTS);
        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, 0);
    }

    // Nothing of the plant (or anything else) touching this spot sideways, ignoring the side it grew
    // from. Straight out of vanilla: it is what keeps arms from fusing into loops.
    private static boolean allNeighborsEmpty(LevelReader level, BlockPos pos, @Nullable Direction except) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (dir != except && !level.isEmptyBlock(pos.relative(dir))) {
                return false;
            }
        }
        return true;
    }

    // Blocks of plant straight down from `pos` to the moss root. Stops counting at the cap, so a tall
    // column costs at most fogEyeMaxHeight lookups per growth step. An arm counts only its own column.
    private static int heightFromRoot(ServerLevel level, BlockPos pos) {
        int cap = Config.FOG_EYE_MAX_HEIGHT.getAsInt();
        BlockPos.MutableBlockPos p = pos.mutable();
        int height = 1;
        while (height < cap) {
            p.move(Direction.DOWN);
            if (!level.getBlockState(p).is(ModBlocks.FOG_EYE_STEM.get())) {
                break;
            }
            height++;
        }
        return height;
    }

    /**
     * Rarely seed an eye on a freshly-placed moss block, with a probability scaled by biome
     * temperature (warm puddles sprout far more). Called by {@link FogMoss} as a puddle spreads.
     *
     * @return true if an eye was placed, so the caller does not also seed grass on that block
     */
    public static boolean trySeed(ServerLevel level, BlockPos mossPos, RandomSource random) {
        if (!Config.FLORA_ENABLED.get()) {
            return false;
        }
        BlockPos top = mossPos.above();
        BlockState above = level.getBlockState(top);
        if ((!above.isAir() && !above.canBeReplaced()) || !level.getFluidState(top).isEmpty()) {
            return false; // occupied, or submerged in water
        }
        double chance = Config.FOG_EYE_SEED_CHANCE.get()
                * FogMoss.tempLerp(level, mossPos, Config.fogEyeColdChance(), Config.fogEyeWarmChance());
        if (random.nextDouble() >= chance) {
            return false;
        }
        // onPlace arms the growth schedule from here.
        level.setBlock(top, ModBlocks.FOG_EYE.get().defaultBlockState(), UPDATE_ALL);
        return true;
    }
}
