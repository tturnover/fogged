package com.fogged.entity;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fogged.Fogged;
import com.fogged.mixin.MoveControlAccessor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * Circles the nearest player, for a mob carrying a circle tag in its {@code Tags} list (set when
 * summoning, or with {@code /tag}). One tag says it all: {@code fogged.circle}, then {@code _l} or
 * {@code _r} for the direction, {@code _f} to face the player, {@code _run} to go at a run,
 * {@code _jump} to hop as it goes and {@code _stay} to stay in place. {@code /fogged animTest} sets all of
 * this from chat ({@link AnimTestControl}).
 *
 * <pre>
 * /summon fogged:plodder ~ ~ ~ {Tags:["fogged.circle_l_f"]}
 * </pre>
 *
 * <ul>
 * <li>Without {@code _f} it walks the circle facing where it goes. It steers rather than pathfinds,
 * turning at a limited rate along the circle and easing back onto it when it drifts, so it moves in one
 * smooth curve; only when the way ahead is blocked or unsafe does it pathfind a stretch round it.
 * <li>With {@code _f} it keeps its face on the player and steps sideways, closing in or backing off
 * to hold the distance.
 * <li>{@code _l} / {@code _r}: which way it goes, as the mob sees it while facing the player (right is
 * counter-clockwise seen from above). Without either, each mob picks one of its own and keeps it, and a
 * walker that can't get round something turns back the other way.
 * </ul>
 *
 * <p>While the player holds a stick, it stops circling and holds where it stands relative to them:
 * the player steps left, it steps left. A facing mob keeps looking at them meanwhile. Put the stick
 * away and it circles on from where it is.
 *
 * <p>The tags are saved with the mob. Every pathfinding mob gets the goal when it joins a level and it
 * only ever starts for a tagged one, so {@code /tag @e[...] add ...} switches a mob that is already out.
 * It sits at priority {@value #PRIORITY}: above wandering and idle looking, below the attack goals most
 * hostile mobs register, so a mob with a target still goes for it.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public class CirclePlayerGoal extends Goal {

    private static final Pattern TAG = Pattern.compile("fogged\\.circle(?:_([lr]))?(_f)?(_run)?(_jump)?(_stay)?");

    /** What a circle tag asks for: a fixed direction (null lets the mob choose), facing the player, the gait. */
    record Mode(Boolean right, boolean facing, boolean run, boolean jump, boolean stay) {
        String tag() {
            return "fogged.circle" + (right == null ? "" : right ? "_r" : "_l") + (facing ? "_f" : "")
                    + (run ? "_run" : "") + (jump ? "_jump" : "") + (stay ? "_stay" : "");
        }
    }

    static final int PRIORITY = 4;

    /** Starts circling a player this close, and gives up past {@link #LOSE_RANGE}. */
    private static final double RANGE = 16;
    private static final double LOSE_RANGE = 24;
    /** A mob following a stick holds on further, so a player can lead it at a run. */
    private static final double FOLLOW_LOSE_RANGE = 48;
    private static final double WALK_SPEED = 1.0;
    /** Speed a mob tagged {@code _run} goes at, walking or side-stepping. */
    private static final double RUN_SPEED = 1.6;
    /** A mob tagged {@code _jump} hops again after this many ticks on the ground. */
    private static final int HOP_TICKS = 12;
    /** How far ahead a walker checks for something to jump onto, and the highest top a jump clears. */
    private static final double JUMP_LOOK = 0.4;
    private static final double JUMP_RISE = 1.1;
    /** Speed a follower uses to catch up once it is this many blocks out of place. */
    private static final double CATCH_UP_SPEED = 1.5;
    private static final double CATCH_UP_DISTANCE = 4;
    /** Beyond this far from its place, a follower pathfinds there instead of walking straight. */
    private static final double FOLLOW_PATH_DISTANCE = 6;
    /** A follower this close to its place stands still. */
    private static final double IN_PLACE = 0.3;
    /** Most a walker turns in a tick, in radians (about 6 degrees: a quarter turn in under a second). */
    private static final double MAX_TURN = Math.toRadians(6);
    /** How hard a walker steers back onto the circle per block it is off it (at most straight in or out). */
    private static final double RADIUS_PULL = 0.35;
    /** A detour pathfinds to the circle this far round, in blocks, and gives up after DETOUR_TICKS. */
    private static final double DETOUR_LEAD = 6;
    private static final int DETOUR_TICKS = 60;
    /** Sideways speed, as a fraction of the mob's own (vanilla's strafe would be 0.25). */
    private static final double STRAFE_SPEED = 0.6;

    private final PathfinderMob mob;
    private Player player;
    private boolean right;
    /** Walker's travel direction, as an angle in the XZ plane (atan2 of z, x). */
    private double heading;
    private int detour;
    private int stuck;
    private int failedDetours;
    /** Where it holds relative to the player while they hold a stick; null while circling. */
    private Vec3 offset;
    private int repath;
    private int hop;

    public CirclePlayerGoal(PathfinderMob mob) {
        this.mob = mob;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @SubscribeEvent
    static void onJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof PathfinderMob mob)) {
            return;
        }
        if (mob.goalSelector.getAvailableGoals().stream().noneMatch(g -> g.getGoal() instanceof CirclePlayerGoal)) {
            mob.goalSelector.addGoal(PRIORITY, new CirclePlayerGoal(mob));
        }
    }

    /** The mob's circle tag, or null if it has none. */
    static Mode mode(Set<String> tags) {
        for (String tag : tags) {
            Matcher m = TAG.matcher(tag);
            if (m.matches()) {
                return new Mode(m.group(1) == null ? null : m.group(1).equals("r"), m.group(2) != null,
                        m.group(3) != null, m.group(4) != null, m.group(5) != null);
            }
        }
        return null;
    }

    /** Swaps a mob's circle tag for the one saying {@code mode}; null leaves it with none. */
    static void setMode(PathfinderMob mob, Mode mode) {
        for (String tag : List.copyOf(mob.getTags())) {
            if (TAG.matcher(tag).matches()) {
                mob.removeTag(tag);
            }
        }
        if (mode != null) {
            mob.addTag(mode.tag());
        }
    }

    @Override
    public boolean canUse() {
        if (mode(mob.getTags()) == null) {
            return false;
        }
        player = mob.level().getNearestPlayer(mob, RANGE);
        return player != null;
    }

    @Override
    public boolean canContinueToUse() {
        double lose = offset != null ? FOLLOW_LOSE_RANGE : LOSE_RANGE;
        return mode(mob.getTags()) != null && player != null && player.isAlive() && !player.isSpectator()
                && mob.distanceToSqr(player) < lose * lose;
    }

    @Override
    public void start() {
        Mode m = mode(mob.getTags());
        right = m.right() != null ? m.right() : (mob.getUUID().getLeastSignificantBits() & 1) == 0;
        heading = Math.toRadians(mob.getYRot() + 90); // where it already faces, so it doesn't snap round
        detour = 0;
        stuck = 0;
        failedDetours = 0;
        offset = null;
    }

    @Override
    public void stop() {
        player = null;
        offset = null;
        mob.getNavigation().stop();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Mode m = mode(mob.getTags());
        if (m == null) {
            return;
        }
        if (m.right() != null) {
            right = m.right();
        }
        boolean acting = mob instanceof RiggedMob rigged && rigged.standingStill();
        if (m.jump() && !acting && mob.onGround() && --hop <= 0) {
            mob.getJumpControl().jump();
            hop = HOP_TICKS;
        }
        if (acting || m.stay()) {
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(mob.getX(), mob.getY(), mob.getZ(), 0);
            detour = 0;
            if (m.facing() && !acting) {
                facePlayer();
            }
            return;
        }
        if (player.isHolding(Items.STICK)) {
            follow(m);
            return;
        }
        if (offset != null) {
            offset = null;
            detour = 0;
            mob.getNavigation().stop();
            heading = Math.toRadians(mob.getYRot() + 90);
        }

        double radius = 4 + mob.getBbWidth() * 2;
        double dx = mob.getX() - player.getX();
        double dz = mob.getZ() - player.getZ();

        if (m.facing()) {
            // Facing the player, its own right is a step round the circle; forward/back holds the radius.
            mob.getNavigation().stop();
            facePlayer();
            float forward = (float) Math.max(-1, Math.min(1, (Math.sqrt(dx * dx + dz * dz) - radius) / 2));
            double way = right ? 1 : -1;
            jumpUp(dz * way, -dx * way);
            strafe(forward, right ? -1 : 1, STRAFE_SPEED * speed(m)); // positive strafe is to the mob's left
            return;
        }

        if (detour > 0) {
            // Pathfinding round something; steering resumes once it arrives or runs out of time.
            if (--detour == 0 || mob.getNavigation().isDone()) {
                detour = 0;
                heading = Math.toRadians(mob.getYRot() + 90);
            }
            return;
        }

        // The circle's tangent in the chosen direction, bent toward or away from the player to hold the
        // radius. Going right (as the mob sees it facing the player) is the angle round the player shrinking.
        double dist = Math.max(1e-3, Math.sqrt(dx * dx + dz * dz));
        double outX = dx / dist, outZ = dz / dist;
        double side = right ? 1 : -1;
        double tanX = outZ * side, tanZ = -outX * side;
        double pull = Math.max(-1, Math.min(1, (dist - radius) * RADIUS_PULL));
        double wanted = Math.atan2(tanZ - outZ * pull, tanX - outX * pull);
        heading += Math.max(-MAX_TURN, Math.min(MAX_TURN, Math.IEEEremainder(wanted - heading, 2 * Math.PI)));

        double hx = Math.cos(heading), hz = Math.sin(heading);
        jumpUp(hx, hz);
        stuck = mob.horizontalCollision ? stuck + 1 : 0;
        if (stuck > 10 || !safeAhead(hx, hz)) {
            stuck = 0;
            startDetour(dx, dz, radius, m);
            return;
        }
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX() + hx * 2, mob.getY(), mob.getZ() + hz * 2, speed(m));
    }

    /**
     * Holds the place it had relative to the player when the stick came out. Far off, it pathfinds
     * back; close by, it walks (or, facing the player, side-steps) straight there and slows as it arrives.
     */
    private void follow(Mode m) {
        if (offset == null) {
            offset = new Vec3(mob.getX() - player.getX(), 0, mob.getZ() - player.getZ());
            detour = 0;
            repath = 0;
        }
        double tx = player.getX() + offset.x;
        double tz = player.getZ() + offset.z;
        double wx = tx - mob.getX();
        double wz = tz - mob.getZ();
        double dist = Math.sqrt(wx * wx + wz * wz);
        double speed = dist > CATCH_UP_DISTANCE ? Math.max(CATCH_UP_SPEED, speed(m)) : speed(m);
        if (m.facing()) {
            facePlayer();
        }

        if (dist > FOLLOW_PATH_DISTANCE || dist > IN_PLACE && !safeAhead(wx / dist, wz / dist)) {
            if (--repath <= 0 || mob.getNavigation().isDone()) {
                repath = 10;
                mob.getNavigation().moveTo(tx, player.getY(), tz, speed);
            }
            return;
        }
        mob.getNavigation().stop();
        repath = 0;
        if (dist < IN_PLACE) {
            return;
        }
        double ease = Math.min(1, dist / 1.5); // slow down over the last block and a half
        jumpUp(wx, wz);
        if (m.facing()) {
            // Turn the way to its place into the mob's own forward/left, as it faces the player.
            double yaw = Math.toRadians(mob.getYRot());
            double cos = Math.cos(yaw), sin = Math.sin(yaw);
            float left = (float) ((wx * cos + wz * sin) / dist * ease);
            float forward = (float) ((wz * cos - wx * sin) / dist * ease);
            strafe(forward, left, speed);
        } else {
            mob.getMoveControl().setWantedPosition(tx, mob.getY(), tz, speed * ease);
        }
    }

    /**
     * Jumps when something ahead is too tall to step onto but low enough to land on. Pathfinding plans
     * for that and vanilla's move control jumps along a path; steering in a straight line has to ask.
     */
    private void jumpUp(double dirX, double dirZ) {
        double length = Math.sqrt(dirX * dirX + dirZ * dirZ);
        if (!mob.onGround() || length < 1e-3) {
            return;
        }
        double x = dirX / length * JUMP_LOOK, z = dirZ / length * JUMP_LOOK;
        AABB box = mob.getBoundingBox();
        if (!mob.level().noCollision(mob, box.move(x, mob.maxUpStep() + 0.01, z))
                && mob.level().noCollision(mob, box.move(0, JUMP_RISE, 0))
                && mob.level().noCollision(mob, box.move(x, JUMP_RISE, z))) {
            mob.getJumpControl().jump();
        }
    }

    private static double speed(Mode m) {
        return m.run() ? RUN_SPEED : WALK_SPEED;
    }

    private void facePlayer() {
        mob.getLookControl().setLookAt(player, 30, 30);
        mob.lookAt(player, 30, 30);
    }

    private void strafe(float forward, float left, double speed) {
        mob.getMoveControl().strafe(forward, left);
        ((MoveControlAccessor) mob.getMoveControl()).fogged$setSpeedModifier(speed);
    }

    /** Pathfinds to a point further round the circle; a free-direction mob that can't, turns back. */
    private void startDetour(double dx, double dz, double radius, Mode m) {
        double angle = Math.atan2(dz, dx) + (right ? -1 : 1) * DETOUR_LEAD / radius;
        boolean found = mob.getNavigation().moveTo(player.getX() + Math.cos(angle) * radius, mob.getY(),
                player.getZ() + Math.sin(angle) * radius, speed(m));
        if (found) {
            detour = DETOUR_TICKS;
            failedDetours = 0;
        } else if (++failedDetours >= 2 && m.right() == null) {
            right = !right;
            heading += Math.PI;
            failedDetours = 0;
        }
    }

    /** Whether the ground a step ahead is walkable, at this height or one step up or down. */
    private boolean safeAhead(double hx, double hz) {
        NodeEvaluator nodes = mob.getNavigation().getNodeEvaluator();
        if (nodes == null) {
            return true;
        }
        double reach = mob.getBbWidth() / 2 + 1;
        BlockPos ahead = BlockPos.containing(mob.getX() + hx * reach, mob.getY(), mob.getZ() + hz * reach);
        return nodes.getPathType(mob, ahead) == PathType.WALKABLE
                || nodes.getPathType(mob, ahead.above()) == PathType.WALKABLE
                || nodes.getPathType(mob, ahead.below()) == PathType.WALKABLE;
    }
}
