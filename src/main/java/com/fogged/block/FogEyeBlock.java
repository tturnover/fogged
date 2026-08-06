package com.fogged.block;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * The fog eye: the head that rides the top of a {@link FogEyeStemBlock} column and drives its growth.
 *
 * <p>An eye is seeded rarely on a fresh {@link FogMoss} puddle (far more rarely than
 * {@link FoggyGrassBlock foggy grass}, and the warmer the biome the more of them). Where it started as a
 * straight port of vanilla's chorus flower (long clean segments, a few wide branches, done forking after
 * a handful of generations — a tree), it grows differently on purpose: mostly a straight climb for the
 * surface, with the odd curl along the way rather than a clean branch. Every step first rolls
 * {@link Config#FOG_EYE_CURL_CHANCE} (low, by design) to throw a short curl of arms sideways instead of
 * climbing, and each arm is a fresh eye one {@link #AGE} older that gets its own rolls going forward, so
 * the curls compound a little as a plant climbs rather than being a one-off kink. The common case is
 * simply a miss, which climbs straight up -- and a lineage that has used up its {@link #MAX_AGE}
 * generations of curling climbs straight the rest of the way regardless, which is what guarantees every
 * lineage reaches the surface.
 *
 * <p>Growth is allowed to touch what it finds, in two ways ({@code fusionRoom} in this class is the
 * shared gate for both): a curling arm that lands on bare moss far enough from every existing root
 * ({@code isRootSpotClear}) strikes a second root right there, so one plant can end up anchored to the
 * ground in several places; and any growth that lands brushing a single existing stem or eye is allowed
 * to touch it rather than being blocked, which is how two separately rooted plants growing near each
 * other end up fused into what reads as one big tangled bush. A cell can never brush more than one
 * existing part this way, and a lineage's own body can never be the thing it brushes (every cell it has
 * ever placed passed this same one-touch check on the way in, so it always keeps at least one empty cell
 * around itself) — the loop this guards against is a plant winding back onto its own body, not a plant
 * touching a neighbour, which is the point.
 *
 * <p>Every stem and eye carries a {@link FogEyeAnchorBlockEntity} recording which root it currently
 * considers itself anchored to -- real identity, not a guess from whatever happens to be touching it.
 * That is what lets {@link #armJoint} pick the correct parent on a fused cell instead of possibly
 * grabbing a neighbour plant's stem, and it is also the ground truth for surviving a cut root: when a
 * cell's own local support is gone, {@link #tryReroot} walks the connected network for the closest
 * still-living root -- any of them, this lineage's original or a later one it struck itself or even a
 * fused neighbour's, all equally good -- and re-anchors to that instead of dying. Only a pocket with no
 * living root reachable at all actually falls.
 *
 * <p>Two things are ours rather than vanilla's, and both follow from the eye chasing the fog:
 * <ul>
 *   <li><b>It never dies.</b> Where a chorus flower would go dead and inert, the eye keeps its head
 *       and climbs on. That is the whole point of the plant: the eye rides the boundary, so it also
 *       <em>sinks</em>, eating its own stem back down when the plane drops. A whole forked plant
 *       follows it down — the arms fold away as they run out of stem (see {@link #sink}) and the trunk
 *       carries on to its root.</li>
 *   <li><b>The climb always wins eventually.</b> A plant that only ever curled would stall short of a
 *       surface that can sit 60 blocks up, so the moment a curl roll misses or a lineage runs out of
 *       ages, it climbs straight up instead, as long as the structure allows it.</li>
 * </ul>
 *
 * <p>The two clocks do different jobs. Growth steps run on scheduled ticks
 * ({@link Config#FOG_EYE_GROW_TICKS}) rather than vanilla's random ticks, so a climb is paced instead
 * of streaky, and once the eye settles at the boundary it drops to a slow {@link #IDLE_TICKS}
 * heartbeat that only watches for the plane moving. The <em>pull-back</em> is the random-tick half:
 * an eye resting open ducks back down its own branch on a {@link Config#FOG_EYE_RETREAT_CHANCE} roll,
 * so it keeps the world's own rhythm and answers to randomTickSpeed like any other plant.
 */
public class FogEyeBlock extends Block implements EntityBlock {
    /** How many generations of curling a lineage gets before it only ever climbs straight. Twice
     *  vanilla's chorus-flower span (0..4) rather than that same span, so a plant stays a tangled
     *  bramble for longer before it has to make a beeline for the surface. */
    public static final int MAX_AGE = 8;
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

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new FogEyeBlockEntity(pos, state);
    }

    /**
     * Which way an eye at {@code pos} should point: UP when it is seated on stem or moss, otherwise
     * away from the single stem it hangs off. Falls back to UP, which is what a seeded head gets.
     */
    private static Direction facingFor(BlockGetter level, BlockPos pos) {
        if (isSeat(level.getBlockState(pos.below()))) {
            return Direction.UP;
        }
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (level.getBlockState(pos.relative(dir)).is(ModBlocks.FOG_EYE_STEM.get())) {
                return dir.getOpposite(); // point away from the stem, not into it
            }
        }
        return Direction.UP;
    }

    /** What an eye can sit upright on: its own stem, or any soil the flora roots in. */
    private static boolean isSeat(BlockState state) {
        return state.is(ModBlocks.FOG_EYE_STEM.get()) || FogMoss.isFloraSoil(state);
    }

    /**
     * An eye rides its own stem or sits straight on the moss root; a freshly-thrown arm instead hangs
     * off the side of the stem that threw it, so it also survives on one or two horizontal stems with
     * nothing but air around and below it -- one is the ordinary hanging arm, two is that same arm
     * fused with a neighbouring plant's stem too (see {@link #fusionRoom}, which is what allows a fused
     * spot to exist in the first place). Three or more is still refused: an arm boxed in on every side
     * is not a fusion point, it is a hole that should not have grown. Failing all of that, a last cheap
     * check: this cell's tracked root ({@link FogEyeAnchorBlockEntity}) might still be alive even though
     * nothing immediately touching it is -- the fast path only, trusting whatever root is on file rather
     * than re-walking to it; {@link #tryReroot} is what re-walks and repairs the tracked root when this
     * whole check fails, called from {@link #tick} right before destruction.
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        if (!level.getFluidState(pos).isEmpty()) {
            return false;
        }
        BlockState below = level.getBlockState(pos.below());
        if (isSeat(below)) {
            return true;
        }
        if (!below.isAir()) {
            return false;
        }
        int attached = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockState side = level.getBlockState(pos.relative(dir));
            if (side.is(ModBlocks.FOG_EYE_STEM.get())) {
                attached++;
            } else if (!side.isAir()) {
                return false;
            }
        }
        if (attached >= 1 && attached <= 2) {
            return true;
        }
        BlockPos root = rootOf(level, pos);
        return root != null && isGenuineRoot(level, root);
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
        if (!canSurvive(state, level, pos) && !tryReroot(level, pos)) {
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
        // open however high it hangs. It only waits here until a climb seats it. A head with room to
        // open but a flower too close instead waits here too -- it rechecks every idle step, so it
        // opens as soon as that neighbour closes back up.
        if (state.getValue(FACING) == Direction.UP && !state.getValue(OPEN) && isFlowerSpotClear(level, pos)) {
            level.setBlock(pos, state.setValue(OPEN, true), UPDATE_CLIENTS);
        }
        level.scheduleTick(pos, this, IDLE_TICKS);
    }

    /** Whether no other open eye sits within {@link Config#FOG_EYE_FLOWER_MIN_DISTANCE} of {@code pos}. */
    private static boolean isFlowerSpotClear(ServerLevel level, BlockPos pos) {
        int r = Config.FOG_EYE_FLOWER_MIN_DISTANCE.getAsInt();
        if (r <= 0) {
            return true;
        }
        int r2 = r * r;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if ((dx == 0 && dy == 0 && dz == 0) || dx * dx + dy * dy + dz * dz > r2) {
                        continue;
                    }
                    p.setWithOffset(pos, dx, dy, dz);
                    BlockState other = level.getBlockState(p);
                    if (other.getBlock() instanceof FogEyeBlock && other.getValue(OPEN)) {
                        return false;
                    }
                }
            }
        }
        return true;
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
        BlockPos myRoot = rootOf(level, pos);
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
            setRootAt(level, below, myRoot);
            at = below;
        }
        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, at, 0);
        return at;
    }

    /**
     * One growth step: curl first, climb only when the curl doesn't take. Every step with ages left to
     * spend rolls {@link Config#FOG_EYE_CURL_CHANCE} to throw a short spray of arms sideways instead of
     * rising, which is what makes the plant read as a wound-up bramble rather than a chorus-style trunk
     * with the odd clean branch. Whenever that roll misses -- or the lineage is out of {@link #MAX_AGE}
     * generations, or there is simply no room to curl into -- it climbs straight up instead, so the eye
     * always keeps closing on the fog surface no matter how much it wanders on the way.
     *
     * @return where the eye ended up, {@code pos} if it could not move, or null if it curled away into
     *         stem and no longer exists at {@code pos}
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

        if (age < MAX_AGE && random.nextDouble() < Config.FOG_EYE_CURL_CHANCE.get()
                && throwArms(level, pos, age, random)) {
            return null;
        }
        // Structural room to climb. fusionRoom (not the stricter allNeighborsEmpty) is what lets a
        // trunk climb into a spot brushing another plant's stem and fuse rather than being blocked --
        // see fusionRoom's own comment for why that never means looping onto its own body.
        boolean roomAbove = fusionRoom(level, above, null) && level.isEmptyBlock(pos.above(2));
        return roomAbove ? climb(level, pos, state) : pos;
    }

    // A short curl: try a random handful of random horizontal directions, sprouting an arm on each that
    // has room. If any took, this head dies into stem and the arms carry the lineage on, each one age
    // older and free to curl again on its own -- which is what makes the tangle compound as it grows
    // rather than staying a single wandering line. fusionRoom is what lets an arm brush an existing
    // stem and fuse instead of being blocked outright, and is also what bounds how tangled a single curl
    // can get. An arm that lands on bare fog moss instead of hanging in the air, far enough from every
    // existing root (isRootSpotClear), strikes a root of its own right there -- this same plant now has
    // two anchors into the ground instead of one.
    private boolean throwArms(ServerLevel level, BlockPos pos, int age, RandomSource random) {
        BlockPos myRoot = rootOf(level, pos);
        int tries = 1 + random.nextInt(3);
        boolean any = false;
        for (int i = 0; i < tries; i++) {
            Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(random);
            BlockPos arm = pos.relative(dir);
            if (!level.isEmptyBlock(arm) || !fusionRoom(level, arm, dir.getOpposite())) {
                continue;
            }
            BlockState armBelow = level.getBlockState(arm.below());
            boolean hanging = armBelow.isAir();
            boolean rooting = FogMoss.isSeedGround(armBelow) && isRootSpotClear(level, arm);
            if (hanging || rooting) {
                placeEye(level, arm, age + 1, rooting ? Direction.UP : dir, rooting ? arm : myRoot);
                any = true;
            }
        }
        if (any) {
            level.setBlock(pos, FogEyeStemBlock.getStateWithConnections(
                    level, pos, ModBlocks.FOG_EYE_STEM.get().defaultBlockState()), UPDATE_CLIENTS);
            setRootAt(level, pos, myRoot);
        }
        return any;
    }

    // Leave a stem behind and re-form one block up, keeping the lineage's age and its tracked root. The
    // stem is placed first and wires itself to what it touches; the eye landing above then updates its
    // UP connection.
    private BlockPos climb(ServerLevel level, BlockPos pos, BlockState state) {
        BlockPos root = rootOf(level, pos);
        level.setBlock(pos, FogEyeStemBlock.getStateWithConnections(
                level, pos, ModBlocks.FOG_EYE_STEM.get().defaultBlockState()), UPDATE_CLIENTS);
        setRootAt(level, pos, root);
        placeEye(level, pos.above(), state.getValue(AGE), Direction.UP, root);
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
        BlockPos myRoot = rootOf(level, pos);
        if (under.is(ModBlocks.FOG_EYE_STEM.get())) {
            level.removeBlock(pos, false);
            // As in pullBack: the head inherits the stem's spot, so it inherits how that stem was held
            // up. Landing on the base of an arm means hanging off the trunk sideways, not sitting on it.
            placeEye(level, below, state.getValue(AGE), facingFor(level, below), myRoot);
            return below;
        }
        if (FogMoss.isSeedGround(under)) {
            return pos; // the trunk, home on its root
        }
        BlockPos joint = armJoint(level, pos);
        // Read the joint's own root before it gets overwritten below -- it should keep whatever root it
        // was already carrying as a stem, not blindly inherit this arm's, though the two almost always
        // agree anyway (armJoint prefers a root-matching neighbour when one exists).
        BlockPos jointRoot = joint != null ? rootOf(level, joint) : null;
        level.removeBlock(pos, false);
        if (joint != null && lastArmOff(level, joint)) {
            // The joint is the head that branched, so it gets its own age back: one younger than the
            // arm it threw. Folding therefore unwinds the ages it spent, and a plant that retracts all
            // the way to its root is age 0 again -- free to grow up as gnarled as the first time.
            placeEye(level, joint, Math.max(0, state.getValue(AGE) - 1), facingFor(level, joint),
                    jointRoot != null ? jointRoot : myRoot);
        }
        return null;
    }

    // The stem an arm hangs from, or null if it is not hanging off one. canSurvive guarantees at most two
    // side stems now that fusion is allowed (see fusionRoom), so a fused cell can have both its true
    // parent and a neighbouring plant's stem to choose from. The tracked root (FogEyeAnchorBlockEntity)
    // is what tells them apart: a neighbour whose root matches this eye's own is the real parent, so it
    // wins whenever one is found. Only if neither side matches (root data missing, an edge case for
    // blocks that predate this system) does this fall back to the first stem found, same as before.
    @Nullable
    private static BlockPos armJoint(ServerLevel level, BlockPos pos) {
        BlockPos myRoot = rootOf(level, pos);
        BlockPos fallback = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos side = pos.relative(dir);
            if (!level.getBlockState(side).is(ModBlocks.FOG_EYE_STEM.get())) {
                continue;
            }
            if (myRoot != null && myRoot.equals(rootOf(level, side))) {
                return side; // the real parent -- same plant
            }
            if (fallback == null) {
                fallback = side;
            }
        }
        return fallback;
    }

    // Whether a joint has nothing left hanging on it and is free to take a head back: no other arm on
    // its sides, and the top of its column (which a joint always is — the head that branched here grew
    // no further up).
    private static boolean lastArmOff(ServerLevel level, BlockPos joint) {
        return allNeighborsEmpty(level, joint, null) && level.isEmptyBlock(joint.above());
    }

    // Place an eye pointing away from what it grew off, and let the world know something grew, the
    // way vanilla's chorus flower does. A fresh head is always shut; opening is the surface's business.
    // `root` is which root this eye should now be tracked against (null leaves it unset).
    private void placeEye(Level level, BlockPos pos, int age, Direction facing, @Nullable BlockPos root) {
        level.setBlock(pos, defaultBlockState().setValue(AGE, age).setValue(FACING, facing), UPDATE_CLIENTS);
        setRootAt(level, pos, root);
        level.levelEvent(LevelEvent.PARTICLES_AND_SOUND_PLANT_GROWTH, pos, 0);
    }

    // Nothing of the plant (or anything else) touching this spot sideways, ignoring the side it grew
    // from. Used only where a touch has to mean nothing is left at all (lastArmOff) -- growth itself
    // grows through fusionRoom below, which is more permissive.
    private static boolean allNeighborsEmpty(LevelReader level, BlockPos pos, @Nullable Direction except) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (dir != except && !level.isEmptyBlock(pos.relative(dir))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Room to grow into: real terrain still blocks it outright, but up to one already-there stem or
     * eye is tolerated (ignoring the side this spot grew from, same as {@link #allNeighborsEmpty}).
     * That one tolerated touch is how two separately rooted plants are allowed to grow into each other
     * and fuse into a single tangled bush. It can never be this lineage's own body: every cell a lineage
     * has ever placed was itself required to pass this same check when it was placed, so a lineage's own
     * footprint always keeps at least one empty cell between any two of its parts -- the one touch a
     * fused cell picks up can only belong to another plant.
     */
    private static boolean fusionRoom(LevelReader level, BlockPos pos, @Nullable Direction except) {
        int plantTouches = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (dir == except) {
                continue;
            }
            BlockState side = level.getBlockState(pos.relative(dir));
            if (side.is(ModBlocks.FOG_EYE_STEM.get()) || side.getBlock() instanceof FogEyeBlock) {
                plantTouches++;
            } else if (!side.isAir()) {
                return false; // real terrain still blocks growth outright
            }
        }
        return plantTouches <= 1;
    }

    /** Which root {@code pos} (a stem or eye) is currently tracked against, or null if it has none on
     *  file -- a block from before this system existed, or placed by hand/structure/command. */
    @Nullable
    static BlockPos rootOf(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof FogEyeAnchorBlockEntity be ? be.getRoot() : null;
    }

    // Points `pos`'s tracked root at `root`, if it has an anchor block entity at all and `root` isn't
    // null. A no-op otherwise, so every call site can pass through whatever root it has on hand (which
    // is sometimes legitimately null -- see the FogEyeAnchorBlockEntity javadoc) without checking first.
    private static void setRootAt(LevelAccessor level, BlockPos pos, @Nullable BlockPos root) {
        if (root != null && level.getBlockEntity(pos) instanceof FogEyeAnchorBlockEntity be) {
            be.setRoot(root);
        }
    }

    // Whether `pos` holds any part of a fog eye plant, own or foreign.
    private static boolean isPlantPart(BlockGetter level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(ModBlocks.FOG_EYE_STEM.get()) || state.getBlock() instanceof FogEyeBlock;
    }

    /** Whether {@code pos} is a plant cell genuinely resting on real ground -- a true root, not merely
     *  tracked as one. This is what {@link #tryReroot} looks for and what its cheap fast-path cousins in
     *  {@link #canSurvive} and {@link FogEyeStemBlock#canSurvive} trust without re-deriving it. */
    static boolean isGenuineRoot(BlockGetter level, BlockPos pos) {
        return isPlantPart(level, pos) && FogMoss.isSeedGround(level.getBlockState(pos.below()));
    }

    /**
     * Called from {@code tick()} right before a cell that has failed {@code canSurvive} would otherwise
     * be destroyed. Floods outward through the connected plant network (stems and eyes, this lineage's
     * own or a fused neighbour's -- fusion makes no distinction, see the class doc) breadth-first, so the
     * first genuine root it finds is the closest one reachable. Every root is equally legitimate: which
     * one this cell (or the lineage it belongs to) originally grew from is not considered, only whether
     * one is still standing and still reachable. On success every cell visited along the way -- not just
     * {@code pos} -- is re-anchored to it in one pass, since they all just lost the same footing and
     * would otherwise each trigger their own search as their own neighbour updates land. Bounded to a
     * modest budget so a huge tangle can't make a single destruction check unpredictably slow; a search
     * that exhausts its budget without finding a root is treated as failure; the cell falls.
     *
     * @return whether a living root was found and reachable, saving {@code pos} (and everything the
     *         search visited) from destruction
     */
    static boolean tryReroot(ServerLevel level, BlockPos pos) {
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(pos);
        queue.add(pos);
        BlockPos found = null;
        int budget = 512;
        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cur = queue.poll();
            if (isGenuineRoot(level, cur)) {
                found = cur;
                break;
            }
            for (Direction dir : Direction.values()) {
                BlockPos next = cur.relative(dir);
                if (!visited.contains(next) && isPlantPart(level, next)) {
                    visited.add(next);
                    queue.add(next);
                }
            }
        }
        if (found == null) {
            return false;
        }
        for (BlockPos p : visited) {
            setRootAt(level, p, found);
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

    /** Whether no existing root (a stem or eye resting on the moss it grew from) sits within
     *  {@link Config#FOG_EYE_ROOT_MIN_DISTANCE} of {@code root}, measured at root height. Roots are kept
     *  apart even though the plants they grow into are free to tangle together higher up. */
    private static boolean isRootSpotClear(ServerLevel level, BlockPos root) {
        int r = Config.FOG_EYE_ROOT_MIN_DISTANCE.getAsInt();
        if (r <= 0) {
            return true;
        }
        int r2 = r * r;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if ((dx == 0 && dz == 0) || dx * dx + dz * dz > r2) {
                    continue;
                }
                p.setWithOffset(root, dx, 0, dz);
                BlockState other = level.getBlockState(p);
                if ((other.is(ModBlocks.FOG_EYE_STEM.get()) || other.getBlock() instanceof FogEyeBlock)
                        && FogMoss.isSeedGround(level.getBlockState(p.below()))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Rarely seed an eye on the ground the flora wave is planting over, with a probability scaled by
     * biome temperature (warm puddles sprout far more). The eye has no seeding path of its own -- this
     * is only ever called from {@code FoggyGrassBlock#tryPlantFogEye}, the same wave that plants foggy
     * grass and the odd puff bush, so an eye only ever turns up somewhere that wave was already going
     * to visit (see {@link FogMoss} for how that wave gets started).
     *
     * @return the position of the seeded eye, or null if none was placed (so the caller knows whether it
     *         has a plant to fast-forward)
     */
    @Nullable
    public static BlockPos trySeed(ServerLevel level, BlockPos mossPos, RandomSource random) {
        if (!Config.FLORA_ENABLED.get()) {
            return null;
        }
        BlockPos top = mossPos.above();
        BlockState above = level.getBlockState(top);
        if ((!above.isAir() && !above.canBeReplaced()) || !level.getFluidState(top).isEmpty()) {
            return null; // occupied, or submerged in water
        }
        if (!isRootSpotClear(level, top)) {
            return null; // another root is too close -- plants may still tangle together higher up
        }
        double chance = Config.FOG_EYE_SEED_CHANCE.get()
                * FogMoss.tempLerp(level, mossPos, Config.fogEyeColdChance(), Config.fogEyeWarmChance());
        if (random.nextDouble() >= chance) {
            return null;
        }
        // onPlace arms the growth schedule from here. A fresh root anchors to itself.
        level.setBlock(top, ModBlocks.FOG_EYE.get().defaultBlockState(), UPDATE_ALL);
        setRootAt(level, top, top);
        return top;
    }

    /**
     * Fast-forward a just-seeded eye to an established plant by running its growth steps in a loop until
     * every head reaches the fog surface (and opens) or can climb no further — used when a chunk is first
     * revealed. A step budget bounds it; anything unfinished carries on via its own scheduled ticks.
     */
    public static void simulateGrowth(ServerLevel level, BlockPos seedHead) {
        if (!Config.FLORA_ENABLED.get()) {
            return;
        }
        int target = targetY(level);
        java.util.ArrayDeque<BlockPos> heads = new java.util.ArrayDeque<>();
        heads.add(seedHead);
        int budget = 4096;
        while (!heads.isEmpty() && budget-- > 0) {
            BlockPos pos = heads.poll();
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof FogEyeBlock eye) || !eye.canSurvive(state, level, pos)) {
                continue; // became stem, folded away, or cannot stand
            }
            if (pos.getY() >= target) {
                if (state.getValue(FACING) == Direction.UP && !state.getValue(OPEN)
                        && isFlowerSpotClear(level, pos)) {
                    level.setBlock(pos, state.setValue(OPEN, true), UPDATE_CLIENTS);
                }
                continue;
            }
            BlockPos next = eye.grow(level, pos, state, level.random);
            if (next == null) {
                // Branched into stem: the arms it threw carry on as new heads.
                for (Direction dir : Direction.Plane.HORIZONTAL) {
                    BlockPos arm = pos.relative(dir);
                    if (level.getBlockState(arm).getBlock() instanceof FogEyeBlock) {
                        heads.add(arm);
                    }
                }
            } else if (!next.equals(pos)) {
                heads.add(next);
            }
            // next == pos: stuck this step; leave it for its own scheduled ticks.
        }
    }
}
