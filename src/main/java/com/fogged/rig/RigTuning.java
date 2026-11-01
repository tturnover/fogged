package com.fogged.rig;

import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

/**
 * The numbers behind a rig, from {@code assets/<ns>/rigs/<entity>.json}. Every field is optional and
 * the file itself can be missing; it reloads with the rest of the resources (F3+T).
 *
 * <pre>
 * texture          texture, default &lt;ns&gt;:textures/entity/&lt;entity&gt;.png
 * idle / walk      animation names, default animation.&lt;entity&gt;.idle / .walk
 * walk_back, strafe_left, strafe_right, turn_left, turn_right
 *                  the other movement animations, default animation.&lt;entity&gt;.&lt;key&gt;. All optional:
 *                  without walk_back the walk plays in reverse, without the others the walk plays
 * turn_speed       degrees per second the turn animations were made for; they play faster or slower
 *                  with the real turning rate. 0 plays them at their own pace.
 * turn_threshold   degrees per second of turning on the spot above which it plays a turn
 * max_turn         degrees per second the mob itself may turn at most (0, the default, is vanilla's
 *                  quick turning). Read by the game, not the renderer: see RigGameplay
 * walk_speed       blocks per second the walk cycle was animated for; the walk then plays faster or
 *                  slower with the real speed so feet don't slide. 0 plays it at its own pace.
 * walk_threshold   blocks per second above which it walks rather than idles
 * blend_time       seconds to crossfade between animations
 * max_step         blocks a foot may be raised or lowered to meet the ground and still move the
 *                  body; default is the entity's own step height, so legs reach what the body can
 *                  step onto
 * reach_down       how much further down a foot over a drop looks for ground, in blocks; it
 *                  stretches toward it (as far as the leg goes) without pulling the body down.
 *                  Only for legs without a reach_&lt;id&gt;_down bone, which sets their depth instead
 * body_smoothing   how fast the body follows the feet (per second; higher is snappier)
 * foot_smoothing   how fast a foot follows the ground under it, e.g. over a block edge
 * tilt_smoothing   the same for bones named *_tilt
 * plant_low        a contact this many pixels or less above its animation's floor is fully planted
 * plant_high       ... and from this height up it is in the air and left to the animation
 * shadow_radius    blocks
 * </pre>
 */
public record RigTuning(
        ResourceLocation texture,
        String idle,
        String walk,
        String walkBack,
        String strafeLeft,
        String strafeRight,
        String turnLeft,
        String turnRight,
        float walkSpeed,
        float walkThreshold,
        float turnSpeed,
        float turnThreshold,
        float blendTime,
        float maxStep,
        float reachDown,
        float bodySmoothing,
        float footSmoothing,
        float tiltSmoothing,
        float plantLow,
        float plantHigh,
        float shadowRadius) {

    public static RigTuning parse(ResourceLocation id, JsonObject o) {
        String name = id.getPath();
        return new RigTuning(
                o != null && o.has("texture") ? ResourceLocation.parse(o.get("texture").getAsString())
                        : ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "textures/entity/" + name + ".png"),
                string(o, "idle", "animation." + name + ".idle"),
                string(o, "walk", "animation." + name + ".walk"),
                string(o, "walk_back", "animation." + name + ".walk_back"),
                string(o, "strafe_left", "animation." + name + ".strafe_left"),
                string(o, "strafe_right", "animation." + name + ".strafe_right"),
                string(o, "turn_left", "animation." + name + ".turn_left"),
                string(o, "turn_right", "animation." + name + ".turn_right"),
                number(o, "walk_speed", 0),
                number(o, "walk_threshold", 0.3f),
                number(o, "turn_speed", 0),
                number(o, "turn_threshold", 25),
                number(o, "blend_time", 0.25f),
                number(o, "max_step", -1),
                number(o, "reach_down", 3),
                number(o, "body_smoothing", 8),
                number(o, "foot_smoothing", 12),
                number(o, "tilt_smoothing", 6),
                number(o, "plant_low", 0.5f),
                number(o, "plant_high", 3),
                number(o, "shadow_radius", 0.8f));
    }

    RigTuning withTexture(ResourceLocation texture) {
        return new RigTuning(texture, idle, walk, walkBack, strafeLeft, strafeRight, turnLeft, turnRight,
                walkSpeed, walkThreshold, turnSpeed, turnThreshold, blendTime, maxStep, reachDown,
                bodySmoothing, footSmoothing, tiltSmoothing, plantLow, plantHigh, shadowRadius);
    }

    private static String string(JsonObject o, String key, String fallback) {
        return o != null && o.has(key) ? o.get(key).getAsString() : fallback;
    }

    private static float number(JsonObject o, String key, float fallback) {
        return o != null && o.has(key) ? o.get(key).getAsFloat() : fallback;
    }
}
