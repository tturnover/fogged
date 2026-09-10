package com.fogged;

import java.util.EnumSet;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

// Makes a mob caught in the murk run for air instead of standing there drowning: first for the nearest
// nozzle-filter breathing sphere, and failing that away from the murk, towards the breathing boundary.
//
// Deliberately an ordinary vanilla Goal on the mob's own goalSelector, driving the mob's own
// PathNavigation, so it works for modded mobs (whatever navigator, speed or malus they use) and loses
// to nothing: other mods' goals, brains and overrides keep behaving as they do. Nothing is teleported
// or nudged directly. MobSuppressor attaches one to every PathfinderMob that joins a level; the goal
// itself decides, tick by tick, whether the mod is even switched on.
public class FogEscapeGoal extends Goal {

    // How far out a drowning mob will notice a breathing sphere, in blocks. Beyond this it just runs
    // for the boundary.
    private static final double SPHERE_SEARCH = 48.0;

    // Search box for the boundary run, in blocks -- the same order as vanilla panic, just taller so a
    // leg of the run clears a cave ceiling's worth of terrain.
    private static final int SEARCH_RADIUS = 10;
    private static final int SEARCH_HEIGHT = 7;

    // Candidate positions sampled per attempt; the one furthest towards the boundary wins. More samples
    // = a straighter run, at the cost of pathfinder probes for every suffocating mob.
    private static final int SEARCH_SAMPLES = 6;

    // Ticks between escape searches. Both halves of canUse() are expensive -- a full createPath, or a
    // handful of random-position probes -- and without a gate every suffocating mob pays that every
    // tick, which is what stalls a server with a herd stuck in the murk. A mob that found nowhere to go
    // waits the long interval before probing again; one that is running waits only the short one, so
    // the legs of an escape chain together instead of visibly pausing between them.
    private static final int STUCK_INTERVAL = 20;
    private static final int LEG_INTERVAL = 4;

    private final PathfinderMob mob;
    private final double speedModifier;
    private int nextSearchTick;
    private double posX;
    private double posY;
    private double posZ;

    public FogEscapeGoal(PathfinderMob mob, double speedModifier) {
        this.mob = mob;
        this.speedModifier = speedModifier;
        // Stagger the first search so mobs that spawned together do not all probe on the same tick.
        this.nextSearchTick = mob.getRandom().nextInt(STUCK_INTERVAL);
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        if (!drowning() || mob.tickCount < nextSearchTick) {
            return false;
        }
        Vec3 sphere = BreatheSpheres.nearestCenter(mob.level(), mob.getEyePosition(), SPHERE_SEARCH);
        boolean found = (sphere != null && aimAt(sphere)) || towardsBoundary();
        nextSearchTick = mob.tickCount + (found ? LEG_INTERVAL : STUCK_INTERVAL);
        return found;
    }

    @Override
    public boolean canContinueToUse() {
        // Stop the moment the mob can breathe again -- inside a sphere or back over the boundary -- so
        // its normal goals take over there rather than seeing the escape run to its end.
        return drowning() && !mob.getNavigation().isDone();
    }

    @Override
    public void start() {
        mob.getNavigation().moveTo(posX, posY, posZ, speedModifier);
    }

    @Override
    public void stop() {
        mob.getNavigation().stop();
    }

    // The same test MobSuppressor damages on, so a mob starts running exactly when the murk starts on it.
    private boolean drowning() {
        Level level = mob.level();
        return Config.MOB_SUFFOCATION.get()
                && Config.MOB_ESCAPE.get()
                && Config.fogged(level, mob.getEyeY())
                && !Config.mobAllowedUnderFog(mob.getType())
                && !BreatheSpheres.isBreathable(level, mob.getEyePosition());
    }

    // Head straight for a shelter if the mob can actually path there; otherwise take a reachable step in
    // its direction, which is how vanilla's flee/follow goals cross ground they cannot path across whole.
    private boolean aimAt(Vec3 target) {
        Path path = mob.getNavigation().createPath(BlockPos.containing(target), 0);
        if (path != null && path.canReach()) {
            posX = target.x;
            posY = target.y;
            posZ = target.z;
            return true;
        }
        Vec3 towards = DefaultRandomPos.getPosTowards(mob, SEARCH_RADIUS, SEARCH_HEIGHT, target, Math.PI / 2.0);
        if (towards == null) {
            return false;
        }
        posX = towards.x;
        posY = towards.y;
        posZ = towards.z;
        return true;
    }

    // No sphere in reach: run for the breathing boundary. That is normally above the mob, but flipFog
    // puts the murk on the upper side instead, and then the way out is down -- so the direction comes
    // from the config rather than being baked in as a climb.
    //
    // Vanilla's random-position sampling already respects the mob's navigator, so keeping the sample
    // furthest that way walks land mobs out a leg at a time while swimmers and fliers go straight --
    // without this mod having to know how any of them move.
    private boolean towardsBoundary() {
        boolean up = !Config.FLIP_FOG.getAsBoolean();
        Vec3 best = null;
        for (int i = 0; i < SEARCH_SAMPLES; i++) {
            Vec3 candidate = DefaultRandomPos.getPos(mob, SEARCH_RADIUS, SEARCH_HEIGHT);
            if (candidate == null || !leavesMurk(candidate.y, up)) {
                continue;
            }
            if (best == null || (up ? candidate.y > best.y : candidate.y < best.y)) {
                best = candidate;
            }
        }
        if (best == null) {
            return false;   // boxed in on the murk side: nothing to run to, so leave the mob to its other goals
        }
        posX = best.x;
        posY = best.y;
        posZ = best.z;
        return true;
    }

    private boolean leavesMurk(double y, boolean up) {
        return up ? y > mob.getY() + 0.5 : y < mob.getY() - 0.5;
    }
}
