package com.fogged.entity.part;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.LINEAR;
import static net.minecraft.client.animation.KeyframeAnimations.degreeVec;
import static net.minecraft.client.animation.KeyframeAnimations.posVec;
import static net.minecraft.client.animation.KeyframeAnimations.scaleVec;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;

/**
 * Keyframe animations for the fog lurker's head: a looping {@link #BURROW} idle (head/fins working as it
 * tunnels, with a little snout-scale pulse) and a held {@link #LUNGE} strike pose. Copied verbatim from
 * Blockbench's Mojang-animation export ({@code head.json}) -- so the sign conventions (rotation negates
 * X and Y, position negates X) are already baked in. Bone names match the head model's parts.
 */
public final class LurkerAnimations {
    private LurkerAnimations() {}

    public static final AnimationDefinition LUNGE = AnimationDefinition.Builder.withLength(0.0F)
            .addAnimation("bone2", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-32.5F, 0.0F, -40.0F), LINEAR)))
            .addAnimation("bone3", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(62.5F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("bone3", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, 1.0F, -0.1F), LINEAR)))
            .addAnimation("right", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-15.0F, 0.0F, -17.5F), LINEAR)))
            .addAnimation("left", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-30.0F, 0.0F, 42.5F), LINEAR)))
            .build();

    public static final AnimationDefinition BURROW = AnimationDefinition.Builder.withLength(1.0F).looping()
            .addAnimation("bone", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(0.0F, 2.5F, 0.0F), LINEAR)))
            .addAnimation("snout", new AnimationChannel(AnimationChannel.Targets.SCALE,
                    new Keyframe(0.0F, scaleVec(1.0F, 1.0F, 1.0F), LINEAR),
                    new Keyframe(0.125F, scaleVec(1.075F, 1.075F, 1.075F), LINEAR)))
            .addAnimation("bone2", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(0.0F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.25F, degreeVec(0.0F, 2.5F, 0.0F), LINEAR),
                    new Keyframe(0.7083F, degreeVec(0.0F, -7.5F, 0.0F), LINEAR),
                    new Keyframe(1.0F, degreeVec(0.0F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("right", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(82.893F, -7.0529F, -44.5615F), LINEAR),
                    new Keyframe(0.5F, degreeVec(97.8453F, -8.8603F, -62.1392F), LINEAR),
                    new Keyframe(1.0F, degreeVec(82.893F, -7.0529F, -44.5615F), LINEAR)))
            .addAnimation("right", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.3333F, posVec(-0.788F, -0.0019F, -0.0248F), LINEAR),
                    new Keyframe(0.5F, posVec(-0.8087F, -0.2561F, -0.0186F), LINEAR),
                    new Keyframe(0.75F, posVec(-0.8692F, 0.241F, -0.0109F), LINEAR),
                    new Keyframe(1.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("left", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(76.6251F, 7.8744F, 29.543F), LINEAR),
                    new Keyframe(0.5F, degreeVec(100.6692F, 10.0619F, 39.4225F), LINEAR),
                    new Keyframe(1.0F, degreeVec(76.6251F, 7.8744F, 29.543F), LINEAR)))
            .addAnimation("left", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.3333F, posVec(0.9715F, 0.1856F, -0.1129F), LINEAR),
                    new Keyframe(0.4583F, posVec(1.1891F, 0.0161F, -0.1664F), LINEAR),
                    new Keyframe(0.5F, posVec(1.0192F, -0.3237F, -0.1279F), LINEAR),
                    new Keyframe(0.5833F, posVec(1.0285F, -0.0204F, -0.1321F), LINEAR),
                    new Keyframe(0.75F, posVec(0.7117F, 0.076F, -0.0673F), LINEAR),
                    new Keyframe(1.0F, posVec(0.0F, 0.0F, 0.0F), LINEAR)))
            .build();
}
