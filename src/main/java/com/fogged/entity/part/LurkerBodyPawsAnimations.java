package com.fogged.entity.part;

import static net.minecraft.client.animation.AnimationChannel.Interpolations.LINEAR;
import static net.minecraft.client.animation.KeyframeAnimations.degreeVec;
import static net.minecraft.client.animation.KeyframeAnimations.posVec;

import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;

/**
 * Keyframe animations for the paw'd body segment: a looping {@link #BURROW} idle (the paws paddling as it
 * tunnels) and a faster {@link #LUNGE} paddle during the strike. Copied verbatim from Blockbench's
 * Mojang-animation export ({@code body.json}). Bone names match the model's parts.
 */
public final class LurkerBodyPawsAnimations {
    private LurkerBodyPawsAnimations() {}

    public static final AnimationDefinition LUNGE = AnimationDefinition.Builder.withLength(0.2917F).looping()
            .addAnimation("paw_rigth", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(82.5F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.0833F, degreeVec(102.5F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.2917F, degreeVec(82.5F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("paw_rigth", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, -1.0F, 0.0F), LINEAR)))
            .addAnimation("paw_rigth2", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(87.5F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.1667F, degreeVec(97.5F, 0.0F, 0.0F), LINEAR),
                    new Keyframe(0.2917F, degreeVec(87.5F, 0.0F, 0.0F), LINEAR)))
            .addAnimation("paw_rigth2", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, -1.0F, 0.0F), LINEAR)))
            .build();

    public static final AnimationDefinition BURROW = AnimationDefinition.Builder.withLength(0.7917F).looping()
            .addAnimation("paw_rigth", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-63.0105F, -11.9205F, 53.8817F), LINEAR),
                    new Keyframe(0.2083F, degreeVec(-75.5973F, 9.5059F, 76.2768F), LINEAR),
                    new Keyframe(0.4167F, degreeVec(-70.53F, 23.61F, 91.02F), LINEAR),
                    new Keyframe(0.4583F, degreeVec(-68.8445F, 28.3137F, 95.9347F), LINEAR),
                    new Keyframe(0.7917F, degreeVec(-63.0105F, -11.9205F, 53.8817F), LINEAR)))
            .addAnimation("paw_rigth", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, -1.0F, 0.0F), LINEAR),
                    new Keyframe(0.4167F, posVec(0.0F, -2.9F, 0.0F), LINEAR),
                    new Keyframe(0.7917F, posVec(0.0F, -1.0F, 0.0F), LINEAR)))
            .addAnimation("paw_rigth2", new AnimationChannel(AnimationChannel.Targets.ROTATION,
                    new Keyframe(0.0F, degreeVec(-63.0105F, 11.9205F, -53.8817F), LINEAR),
                    new Keyframe(0.2083F, degreeVec(-75.5973F, -9.5059F, -76.2768F), LINEAR),
                    new Keyframe(0.4167F, degreeVec(-70.53F, -23.61F, -91.02F), LINEAR),
                    new Keyframe(0.4583F, degreeVec(-68.8445F, -28.3137F, -95.9347F), LINEAR),
                    new Keyframe(0.7917F, degreeVec(-63.0105F, 11.9205F, -53.8817F), LINEAR)))
            .addAnimation("paw_rigth2", new AnimationChannel(AnimationChannel.Targets.POSITION,
                    new Keyframe(0.0F, posVec(0.0F, -1.0F, 0.0F), LINEAR),
                    new Keyframe(0.4167F, posVec(0.0F, -2.9F, 0.0F), LINEAR),
                    new Keyframe(0.7917F, posVec(0.0F, -1.0F, 0.0F), LINEAR)))
            .build();
}
