package com.fogged.entity.part;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.LINEAR;
import static net.minecraft.client.animation.KeyframeAnimations.degreeVec;
import static net.minecraft.client.animation.KeyframeAnimations.posVec;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;

/**
 * Keyframe animations for the fog lurker's head, transcribed from the {@code fog_lurker_head} Blockbench
 * model: a looping {@link #BURROW} idle (the head/fins working as it tunnels) and a held {@link #LUNGE}
 * pose (reared up to strike). Bone names match the head model's parts so {@code KeyframeAnimations} can
 * resolve them.
 */
public final class LurkerAnimations {
    private LurkerAnimations() {}

    public static final AnimationDefinition BURROW = AnimationDefinition.Builder.withLength(1.0F).looping()
            .addAnimation("bone", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(0.0F, -2.5F, 0.0F), LINEAR)))
            .addAnimation("bone2", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(0.0F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.25F, degreeVec(0.0F, -2.5F, 0.0F), LINEAR),
                    new Keyframe(0.70833F, degreeVec(0.0F, 7.5F, 0.0F), LINEAR),
                    new Keyframe(1.0F, degreeVec(0.0F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("right", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-82.893F, 7.0529F, -44.5615F), LINEAR),
                    new Keyframe(0.5F, degreeVec(-97.8453F, 8.8603F, -62.1392F), LINEAR),
                    new Keyframe(1.0F, degreeVec(-82.893F, 7.0529F, -44.5615F), LINEAR)))
            .addAnimation("right", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.33333F, posVec(0.788F, -0.0019F, -0.0248F), LINEAR),
                    new Keyframe(0.5F, posVec(0.8087F, -0.2561F, -0.0186F), LINEAR),
                    new Keyframe(0.75F, posVec(0.8692F, 0.241F, -0.0109F), LINEAR),
                    new Keyframe(1.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("left", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-76.6251F, -7.8744F, 29.543F), LINEAR),
                    new Keyframe(0.5F, degreeVec(-100.6692F, -10.0619F, 39.4225F), LINEAR),
                    new Keyframe(1.0F, degreeVec(-76.6251F, -7.8744F, 29.543F), LINEAR)))
            .addAnimation("left", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.33333F, posVec(-0.9715F, 0.1856F, -0.11285F), LINEAR),
                    new Keyframe(0.45833F, posVec(-1.1891F, 0.0161F, -0.16643F), LINEAR),
                    new Keyframe(0.5F, posVec(-1.0192F, -0.3237F, -0.12787F), LINEAR),
                    new Keyframe(0.58333F, posVec(-1.0285F, -0.0204F, -0.13209F), LINEAR),
                    new Keyframe(0.75F, posVec(-0.7117F, 0.076F, -0.06731F), LINEAR),
                    new Keyframe(1.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR)))
            .build();

    // All keyframes sit at t=0, so this holds a single reared-up pose for the duration of the strike.
    public static final AnimationDefinition LUNGE = AnimationDefinition.Builder.withLength(0.5F)
            .addAnimation("bone2", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(32.5F, 0.0F, -40.0F), LINEAR)))
            .addAnimation("bone3", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-62.5F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("bone3", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, 1.0F, -0.1F), LINEAR)))
            .addAnimation("right", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(15.0F, 0.0F, -17.5F), LINEAR)))
            .addAnimation("left", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(30.0F, 0.0F, 42.5F), LINEAR)))
            .build();
}
