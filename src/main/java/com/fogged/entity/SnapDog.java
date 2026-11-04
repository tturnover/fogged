package com.fogged.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

/**
 * Wide-jawed pack hunter, here as a test of the rig's whole range: every movement animation plus the
 * actions from its sketch ({@code attack}, {@code sleep}, {@code angry}, {@code alert}, {@code howl}).
 * Summon it with {@code /summon fogged:snap_dog} and drive it with {@code /fogged animTest}. It wanders
 * and has no natural spawns, and it doesn't hunt or bite yet.
 *
 * <p>Its reach bones give it a half-block step, so it jumps up full blocks. On a slope the body leans with the feet and the head
 * stays level ({@code body_tilt}, {@code head_level}).
 */
public class SnapDog extends RiggedMob {

    public SnapDog(EntityType<? extends SnapDog> type, Level level) {
        super(type, level, "snap_dog");
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.STEP_HEIGHT, 0.5);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    /** The jaws reach well past the hitbox. */
    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(0.6, 0.2, 0.6);
    }
}
