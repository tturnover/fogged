package com.fogged.entity;

import com.fogged.rig.RigGameplay;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.Level;

/**
 * A mob drawn by the rig, whose model also decides how it moves (see {@link RigGameplay}): its step height
 * comes from its legs' reach bones, and its turning speed from {@code max_turn} in its tuning file.
 *
 * <p>Turning is capped on the body and on the yaw every goal and the move control set, so a big creature
 * swings round slowly wherever the turn comes from: walking a path, circling, or standing. While walking
 * it also slows down until it faces where it is going, so a slow turner makes a wide, tidy arc rather
 * than orbiting the point it is trying to reach. The rig sees the slow turn and plays its turn animation.
 */
public abstract class RiggedMob extends PathfinderMob {

    /** Most it turns in a tick, in degrees; 0 leaves turning to vanilla. */
    private final float maxTurn;

    protected RiggedMob(EntityType<? extends RiggedMob> type, Level level, String model) {
        super(type, level);
        maxTurn = RigGameplay.maxTurnPerTick(model);
        if (maxTurn > 0) {
            moveControl = new TurnLimitedMoveControl(this, maxTurn);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (maxTurn > 0) {
            // The body follows the yaw (or the head, standing still); hold it to the same rate.
            yBodyRot = yBodyRotO + Mth.clamp(Mth.wrapDegrees(yBodyRot - yBodyRotO), -maxTurn, maxTurn);
        }
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        RigGameplay.restore(this); // the model's step height, not the one saved with the world
    }

    /**
     * Vanilla's move control, with the yaw held to {@code maxTurn} a tick from where it started the tick
     * (which also catches a goal that set it directly, like looking at a target), and moving slowed by how
     * far it still faces off its way.
     */
    static final class TurnLimitedMoveControl extends MoveControl {
        private final float maxTurn;

        TurnLimitedMoveControl(Mob mob, float maxTurn) {
            super(mob);
            this.maxTurn = maxTurn;
        }

        @Override
        public void tick() {
            clampYaw();
            boolean moving = operation == Operation.MOVE_TO;
            double dx = wantedX - mob.getX();
            double dz = wantedZ - mob.getZ();
            super.tick();
            clampYaw();
            if (moving && dx * dx + dz * dz > 1e-6) {
                float wanted = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90;
                float off = Math.abs(Mth.wrapDegrees(wanted - mob.getYRot()));
                mob.setSpeed(mob.getSpeed() * Math.max(0.15f, Mth.cos(Math.min(90, off) * Mth.DEG_TO_RAD)));
            }
        }

        private void clampYaw() {
            float turned = Mth.wrapDegrees(mob.getYRot() - mob.yRotO);
            if (Math.abs(turned) > maxTurn) {
                mob.setYRot(mob.yRotO + Math.copySign(maxTurn, turned));
            }
        }
    }
}
