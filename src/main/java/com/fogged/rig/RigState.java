package com.fogged.rig;

import java.util.Map;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;

/**
 * One entity's rig on the client: which animation plays and how far in, the crossfade from the pose it
 * left, and the smoothed body height and slope. Nothing here is ever sent anywhere.
 */
final class RigState {

    enum Move { IDLE, FORWARD, RUN, BACK, LEFT, RIGHT, TURN_LEFT, TURN_RIGHT }

    /** Seconds off the ground before it counts as a jump, so a step down doesn't play one. */
    private static final float AIR_DELAY = 0.1f;

    float lastTick = Float.NaN;
    float speed;
    /** Smoothed ground velocity in the model's frame (blocks/s) and body turning rate (degrees/s). */
    float forward;
    float right;
    float yawRate;
    float lastYaw = Float.NaN;
    float airTime;
    Move move = Move.IDLE;

    String current;
    float currentTime;
    float fade = 1;

    /** Smoothed world height the body sits at, and its last height above the entity's feet (blocks). */
    double bodyY = Double.NaN;
    /** What the fit last did to the pose, eased: body height and each leg's correction (pixels), and the lean. */
    float bodyShift;
    float[] legShift = new float[0];
    final Quaternionf lean = new Quaternionf();
    float bodyLift;
    /** Per leg: smoothed world height of the ground under the foot, and how far it reaches for it / counts toward the body (0..1). */
    double[] footY = new double[0];
    float[] footReach = new float[0];
    float[] footBody = new float[0];
    float slopeX;
    float slopeZ;

    /** The final pose, which the ground fit goes on to change. */
    RigPose pose;
    /** The animation's pose last frame, before the fit, and the frozen one a crossfade starts from. */
    private RigPose lastPose;
    private RigPose fadeFrom;

    void ensureLegs(int legs) {
        if (footY.length != legs) {
            footY = new double[legs];
            java.util.Arrays.fill(footY, Double.NaN);
            footReach = new float[legs];
            footBody = new float[legs];
            legShift = new float[legs];
        }
    }

    /** Seconds since the last frame, from the entity's own clock so a paused game stands still. */
    float advance(Entity entity, float partialTick) {
        float now = entity.tickCount + partialTick;
        float dt = Float.isNaN(lastTick) ? 0 : Math.max(0, Math.min(5, now - lastTick)) / 20f;
        lastTick = now;
        return dt;
    }

    /**
     * Picks the animation from how the entity moves relative to the way its body faces: forward, back
     * or sideways while moving, turning while standing, idle otherwise. A movement animation the model
     * lacks falls back to the walk (played in reverse for walking backwards). An action the mob is
     * doing ({@link RigActor}) comes first, then being in the air.
     */
    void animate(RigModel model, Entity entity, float bodyYaw, float dt) {
        int bones = model.geometry().bones.length;
        if (pose == null || pose.world.length != bones) {
            pose = new RigPose(bones); // first frame, or the model was reloaded with other bones
            lastPose = new RigPose(bones);
            fadeFrom = new RigPose(bones);
            current = null; // nothing to fade from
        }
        RigTuning t = model.tuning();
        Map<String, RigAnimation.Bound> anims = model.animations();
        float k = dt <= 0 ? 1 : 1 - (float) Math.exp(-10 * dt);

        // Ground velocity in the model's own frame (front is -Z), and how fast the body turns.
        Vector3f v = new Vector3f((float) (entity.getX() - entity.xo), 0, (float) (entity.getZ() - entity.zo)).mul(20);
        new Quaternionf().rotationY((float) Math.toRadians(bodyYaw - 180)).transform(v);
        forward += (-v.z - forward) * k;
        right += (v.x - right) * k;
        speed = (float) Math.sqrt(forward * forward + right * right);
        if (!Float.isNaN(lastYaw) && dt > 0) {
            yawRate += (Mth.wrapDegrees(bodyYaw - lastYaw) / dt - yawRate) * k;
        }
        lastYaw = bodyYaw;

        airTime = entity.onGround() ? 0 : airTime + dt;
        String action = entity instanceof RigActor actor && !actor.rigAction().isEmpty()
                ? "animation." + model.id().getPath() + "." + actor.rigAction() : null;
        boolean acting = action != null && anims.containsKey(action);
        boolean airborne = !acting && airTime > AIR_DELAY && anims.containsKey(t.jump());

        Move move = pick(t, anims.containsKey(t.run()));
        String name = acting ? action : airborne ? t.jump() : resolve(move, t, anims);
        boolean reversed = !acting && !airborne && move == Move.BACK
                && !anims.containsKey(t.walkBack()) && name.equals(t.walk());
        String key = reversed ? name + "#reversed" : name;
        if (!key.equals(current)) {
            // Fade from exactly what was showing, even mid-crossfade, so a quick change of mind can't pop.
            fade = current == null ? 1 : 0;
            fadeFrom.copyFrom(lastPose);
            current = key;
            currentTime = 0;
        }
        this.move = move;

        float playRate = 1;
        if (acting || airborne) {
            playRate = 1;
        } else if (move == Move.RUN) {
            playRate = t.runSpeed() > 0 ? clampRate(speed / t.runSpeed()) : 1;
        } else if (move == Move.TURN_LEFT || move == Move.TURN_RIGHT) {
            playRate = t.turnSpeed() > 0 ? clampRate(Math.abs(yawRate) / t.turnSpeed()) : 1;
        } else if (move != Move.IDLE && t.walkSpeed() > 0) {
            playRate = clampRate(speed / t.walkSpeed());
        }
        currentTime += dt * playRate * (reversed ? -1 : 1);
        fade = t.blendTime() <= 0 ? 1 : Math.min(1, fade + dt / t.blendTime());

        pose.reset();
        RigAnimation.Bound anim = anims.get(strip(current));
        if (anim != null) {
            anim.sample(currentTime, pose);
        }
        if (fade < 1) {
            pose.blendFrom(fadeFrom, fade * fade * (3 - 2 * fade));
        }
        lastPose.copyFrom(pose);
    }

    /** Which way it is moving, with a little hysteresis so it doesn't flicker between two. */
    private Move pick(RigTuning t, boolean canRun) {
        if (speed > t.walkThreshold()) {
            boolean sideways = move == Move.LEFT || move == Move.RIGHT;
            // Stay sideways until clearly not, and stay forward/back until clearly sideways.
            boolean side = sideways ? Math.abs(right) * 1.25f > Math.abs(forward) : Math.abs(right) > Math.abs(forward) * 1.25f;
            if (side) {
                return right > 0 ? Move.RIGHT : Move.LEFT;
            }
            if (forward < 0) {
                return Move.BACK;
            }
            boolean run = canRun && t.runThreshold() > 0 && speed > t.runThreshold() * (move == Move.RUN ? 0.85f : 1);
            return run ? Move.RUN : Move.FORWARD;
        }
        boolean turning = move == Move.TURN_LEFT || move == Move.TURN_RIGHT;
        if (Math.abs(yawRate) > t.turnThreshold() * (turning ? 0.6f : 1)) {
            return yawRate > 0 ? Move.TURN_RIGHT : Move.TURN_LEFT; // body yaw grows turning right
        }
        return Move.IDLE;
    }

    /** The animation to play for a move: its own if the model has it, else the walk, else idle. */
    private static String resolve(Move move, RigTuning t, Map<String, RigAnimation.Bound> anims) {
        String own = switch (move) {
            case IDLE -> t.idle();
            case FORWARD -> t.walk();
            case RUN -> t.run();
            case BACK -> t.walkBack();
            case LEFT -> t.strafeLeft();
            case RIGHT -> t.strafeRight();
            case TURN_LEFT -> t.turnLeft();
            case TURN_RIGHT -> t.turnRight();
        };
        if (anims.containsKey(own) || move == Move.IDLE) {
            return own;
        }
        return anims.containsKey(t.walk()) ? t.walk() : t.idle();
    }

    private static String strip(String key) {
        return key.endsWith("#reversed") ? key.substring(0, key.length() - "#reversed".length()) : key;
    }

    private static float clampRate(float rate) {
        return Math.max(0.25f, Math.min(4, rate));
    }
}
