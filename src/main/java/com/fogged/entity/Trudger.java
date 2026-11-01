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
 * Sauropod-scale rig test: four pillar legs about four blocks to the hip, a long neck and tail, and a
 * slow walk that always keeps three feet down. It exists to see the ground fit at a big creature's
 * scale (summon it with {@code /summon fogged:trudger}) and has no natural spawns.
 *
 * <p>Its pillars end at the hip inside a 44 px body, which hides a slide of 32 px up and 12 px down. Its
 * model's reach bones say exactly that, and the 32 px up makes its step height 2 blocks (see
 * {@code RigGameplay}); the value here is only the fallback for a model without them.
 *
 * <p>The hitbox covers the body only; neck and tail reach well past it, so the culling box is widened
 * to keep them drawn when the body itself is off screen.
 */
public class Trudger extends RiggedMob {

    public Trudger(EntityType<? extends Trudger> type, Level level) {
        super(type, level, "trudger");
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 120)
                .add(Attributes.MOVEMENT_SPEED, 0.14)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0)
                .add(Attributes.STEP_HEIGHT, 2.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 1.0));
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 12));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        return getBoundingBox().inflate(10, 3, 10);
    }
}
