package com.fogged.rig;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Fits an animated pose to the ground under it. Animations are authored on a flat floor at height 0;
 * this measures how far the real ground under each planted foot is from that floor and splits the
 * difference:
 *
 * <ol>
 * <li>The body takes the average: the planted feet's offsets, weighted by how planted each is, set the
 * height of the body bones. Bones named {@code *_tilt} also lean with the plane through them.
 * <li>Each leg takes what is left. A jointed leg bends its upper and lower bones, in the plane the
 * animation already bends them in; a pillar leg slides straight up or down.
 * </ol>
 *
 * A foot in the air (its contact above {@code plant_high}) is left to the animation, and between
 * {@code plant_low} and {@code plant_high} it fades in, so a foot lands and lifts without a pop.
 *
 * <p>Only feet whose ground is within {@code max_step} count toward the body: a foot over a drop
 * still reaches down for whatever is below (as far as the leg goes, straight if it has to, looking
 * {@code reach_down} further), but it doesn't pull the body over the edge after it. Ground taller than
 * {@code max_step} is left to the animation.
 */
final class GroundRig {

    /** A jump in ground or body height bigger than this (blocks) is a teleport, taken at once. */
    private static final double SNAP = 4;

    private GroundRig() {}

    static void apply(RigModel model, RigPose pose, RigState state, Entity entity, Vec3 origin, float bodyYaw, float dt) {
        RigLayout layout = model.layout();
        RigGeometry g = model.geometry();
        RigTuning t = model.tuning();
        RigLayout.Leg[] legs = layout.legs;
        if (legs.length == 0) {
            return;
        }
        float maxStep = t.maxStep() >= 0 ? t.maxStep() : entity.maxUpStep();
        Quaternionf toWorld = new Quaternionf().rotationY((float) Math.toRadians(180 - bodyYaw));

        pose.solve(g);
        // How much of the fit the animation wants right now: the `ground` bone's scale for all of it.
        float strength = layout.ground >= 0 ? clamp01(pose.scale[layout.ground * 3]) : 1;

        // 1. Where each contact is in the animation, how planted it is, and the ground under it.
        // A foot reaches for its ground with `weight`; only ground within a step also moves the body.
        // Heights are smoothed as world heights, not relative to the entity, so the entity's own
        // near-instant step up a block never shows, and a foot crossing a block edge eases over.
        state.ensureLegs(legs.length);
        float footRate = rate(t.footSmoothing(), dt);
        Vector3f[] contact = new Vector3f[legs.length];
        float[] weight = new float[legs.length];
        float[] bodyWeight = new float[legs.length];
        boolean[] found = new boolean[legs.length]; // real ground under it, not just the end of the search
        float[] ground = new float[legs.length]; // pixels, relative to the animation's floor
        float weightSum = 0;
        float heightSum = 0;
        float depth = maxStep + Math.max(0, t.reachDown());
        for (int i = 0; i < legs.length; i++) {
            Vector3f c = pose.pivotOf(g, legs[i].contact());
            contact[i] = c;
            float plant = 1 - clamp01((c.y - t.plantLow()) / Math.max(1e-3f, t.plantHigh() - t.plantLow()));
            plant *= clamp01(pose.scale[legs[i].contact() * 3]); // a contact's own scale switches its foot

            // Probed every frame, lifted feet too, so a foot comes down already knowing the ground. A leg
            // with a reach_<id>_down bone looks as deep as that bone plus a step, the most the body may
            // lower to let it reach.
            Vector3f wp = toWorld.transform(new Vector3f(c.x, 0, c.z).div(16));
            float legDepth = legs[i].down() >= 0 ? Math.max(0, -pose.pivotOf(g, legs[i].down()).y / 16) + maxStep : depth;
            double h = groundAt(entity, origin.x + wp.x, origin.y, origin.z + wp.z, maxStep, legDepth);
            boolean reachable = !Double.isNaN(h); // NaN: inside something taller than a step
            found[i] = reachable && h > -legDepth + 1e-3;
            boolean underBody = reachable && h >= -maxStep; // deeper is a drop: reach, but don't follow
            if (reachable) {
                double y = origin.y + h;
                double last = state.footY[i];
                state.footY[i] = Double.isNaN(last) || Math.abs(y - last) > SNAP ? y : last + (y - last) * footRate;
            }
            state.footReach[i] += ((reachable ? 1 : 0) - state.footReach[i]) * footRate;
            state.footBody[i] += ((underBody ? 1 : 0) - state.footBody[i]) * footRate;
            if (Double.isNaN(state.footY[i])) {
                continue; // never seen ground: left to the animation
            }

            ground[i] = (float) (state.footY[i] - origin.y) * 16;
            weight[i] = plant * state.footReach[i];
            bodyWeight[i] = plant * state.footBody[i];
            weightSum += bodyWeight[i];
            heightSum += bodyWeight[i] * ground[i];
        }

        // 2. The body eases toward the weighted average of the feet within a step, as a world height too,
        // but never so high that a planted foot can't reach down to its ground, nor so low that one can't
        // lift enough for its own: a resting leg folds up to let a floating one down. Where both can't
        // hold, putting the floating foot down wins. With no foot down it keeps its last height above
        // the entity, so a fall carries it along.
        float lift = weightSum > 1e-3f ? heightSum / weightSum : state.bodyLift * 16;
        float lowest = Float.NEGATIVE_INFINITY, highest = Float.POSITIVE_INFINITY;
        for (int i = 0; i < legs.length; i++) {
            if (weight[i] < 0.5f || !found[i]) {
                continue; // lifted, or nothing under it (a cliff edge: reach down, but don't crouch for it)
            }
            float[] reach = reach(g, pose, legs[i], contact[i], maxStep);
            lowest = Math.max(lowest, ground[i] - reach[0]);
            highest = Math.min(highest, ground[i] + reach[1]);
        }
        lift = lowest <= highest ? Math.max(lowest, Math.min(highest, lift)) : highest;
        state.bodyLift = lift / 16;
        double bodyTarget = origin.y + state.bodyLift;
        state.bodyY = Double.isNaN(state.bodyY) || Math.abs(bodyTarget - state.bodyY) > SNAP ? bodyTarget
                : state.bodyY + (bodyTarget - state.bodyY) * rate(t.bodySmoothing(), dt);
        float body = (float) Math.max(-depth, Math.min(maxStep, state.bodyY - origin.y)) * 16 * strength;
        for (int bone : layout.bodyBones) {
            pose.addPositionY(bone, body);
        }

        if (layout.tiltBones.length > 0) {
            tilt(legs, contact, bodyWeight, ground, state, t, dt);
            pose.solve(g);
            Vector3f normal = new Vector3f(-state.slopeX, 1, -state.slopeZ).normalize();
            Quaternionf lean = new Quaternionf().slerp(new Quaternionf().rotationTo(new Vector3f(0, 1, 0), normal), strength);
            for (int bone : layout.tiltBones) {
                pose.rotateInModelSpace(g, bone, lean);
                pose.solve(g);
            }
        }
        pose.solve(g);

        // 3. Each planted leg bends to put its contact on the ground.
        for (int i = 0; i < legs.length; i++) {
            if (weight[i] * strength <= 0) {
                continue;
            }
            RigLayout.Leg leg = legs[i];
            Vector3f now = pose.pivotOf(g, leg.contact());
            Vector3f target = new Vector3f(now.x, contact[i].y + ground[i], now.z);
            now.lerp(target, weight[i] * strength, target);
            // The leg's reach bones, where it has them, bound how high and low the sole may go. A pillar
            // without one never slides further than a step, so one modelled to hide that much never gaps.
            float high = leg.up() >= 0 ? pose.pivotOf(g, leg.up()).y : leg.pillar() ? now.y + maxStep * 16 : Float.POSITIVE_INFINITY;
            float low = leg.down() >= 0 ? pose.pivotOf(g, leg.down()).y : leg.pillar() ? now.y - maxStep * 16 : Float.NEGATIVE_INFINITY;
            target.y = Math.max(Math.min(low, high), Math.min(high, target.y));
            if (leg.pillar()) {
                pose.moveInModelSpace(g, leg.upper(), new Vector3f(0, target.y - now.y, 0));
                pose.solve(g);
            } else {
                reach(g, pose, leg, target);
            }
        }
    }

    /**
     * How far a leg's sole may move up and down from where the animation has it, in pixels: its reach
     * bones where it has them; otherwise a step for a pillar, and for a jointed leg as far as it
     * straightens (it can always fold up). Measured before the body moves, which carries both alike.
     */
    private static float[] reach(RigGeometry g, RigPose pose, RigLayout.Leg leg, Vector3f contact, float maxStep) {
        float up, down;
        if (leg.pillar()) {
            up = down = maxStep * 16;
        } else {
            float length = pose.pivotOf(g, leg.upper()).distance(pose.pivotOf(g, leg.lower()))
                    + pose.pivotOf(g, leg.lower()).distance(contact);
            up = Float.POSITIVE_INFINITY;
            down = Math.max(0, length - pose.pivotOf(g, leg.upper()).distance(contact));
        }
        if (leg.up() >= 0) {
            up = pose.pivotOf(g, leg.up()).y - contact.y;
        }
        if (leg.down() >= 0) {
            down = contact.y - pose.pivotOf(g, leg.down()).y;
        }
        return new float[] {up, down};
    }

    /**
     * Two-bone reach: turns the upper and lower bone, each about its own pivot and only in the plane
     * the leg already bends in, so the contact lands on {@code target} (or as near as the leg allows).
     */
    private static void reach(RigGeometry g, RigPose pose, RigLayout.Leg leg, Vector3f target) {
        Vector3f hip = pose.pivotOf(g, leg.upper());
        Vector3f knee = pose.pivotOf(g, leg.lower());
        Vector3f foot = pose.pivotOf(g, leg.contact());

        Vector3f upper = new Vector3f(knee).sub(hip);
        Vector3f lower = new Vector3f(foot).sub(knee);
        float a = upper.length();
        float b = lower.length();
        Vector3f toTarget = new Vector3f(target).sub(hip);
        float d = toTarget.length();
        if (a < 1e-4f || b < 1e-4f || d < 1e-4f) {
            return;
        }
        d = Math.max(Math.abs(a - b) + 1e-3f, Math.min(a + b - 1e-3f, d));
        Vector3f dir = toTarget.normalize();

        // The bend plane. A dead-straight leg has none, so it folds its knee toward the model's front.
        Vector3f axis = new Vector3f(upper).cross(new Vector3f(foot).sub(hip));
        if (axis.lengthSquared() < 1e-6f) {
            axis.set(-1, 0, 0);
        }
        axis.normalize();

        float cosHip = (a * a + d * d - b * b) / (2 * a * d);
        float hipAngle = (float) Math.acos(Math.max(-1, Math.min(1, cosHip)));
        Vector3f newUpper = new Quaternionf().rotationAxis(-hipAngle, axis).transform(new Vector3f(dir)).mul(a);

        pose.rotateInModelSpace(g, leg.upper(), new Quaternionf().rotationTo(upper.normalize(), new Vector3f(newUpper).normalize()));
        pose.solve(g);

        Vector3f newKnee = pose.pivotOf(g, leg.lower());
        Vector3f newFoot = pose.pivotOf(g, leg.contact());
        Vector3f reachFoot = new Vector3f(dir).mul(d).add(hip);
        pose.rotateInModelSpace(g, leg.lower(), new Quaternionf().rotationTo(
                newFoot.sub(newKnee).normalize(), reachFoot.sub(newKnee).normalize()));
        pose.solve(g);
    }

    /** Weighted least-squares plane through the planted feet, smoothed into the state's slopes. */
    private static void tilt(RigLayout.Leg[] legs, Vector3f[] contact, float[] weight, float[] ground,
            RigState state, RigTuning t, float dt) {
        double sw = 0, sx = 0, sz = 0, sxx = 0, szz = 0, sxz = 0, sh = 0, sxh = 0, szh = 0;
        int planted = 0;
        for (int i = 0; i < legs.length; i++) {
            double w = weight[i];
            if (w <= 0) {
                continue;
            }
            planted++;
            double x = contact[i].x, z = contact[i].z, h = ground[i];
            sw += w; sx += w * x; sz += w * z; sxx += w * x * x; szz += w * z * z; sxz += w * x * z;
            sh += w * h; sxh += w * x * h; szh += w * z * h;
        }
        float slopeX = 0, slopeZ = 0;
        if (planted >= 3) {
            // Centre the sums, then solve the 2x2 normal equations for the two slopes.
            double mx = sx / sw, mz = sz / sw, mh = sh / sw;
            double cxx = sxx / sw - mx * mx, czz = szz / sw - mz * mz, cxz = sxz / sw - mx * mz;
            double cxh = sxh / sw - mx * mh, czh = szh / sw - mz * mh;
            double det = cxx * czz - cxz * cxz;
            if (Math.abs(det) > 1e-3) {
                slopeX = (float) Math.max(-1, Math.min(1, (cxh * czz - czh * cxz) / det));
                slopeZ = (float) Math.max(-1, Math.min(1, (czh * cxx - cxh * cxz) / det));
            }
        }
        float k = rate(t.tiltSmoothing(), dt);
        state.slopeX += (slopeX - state.slopeX) * k;
        state.slopeZ += (slopeZ - state.slopeZ) * k;
    }

    /**
     * Height of the ground under a point, in blocks relative to {@code y}: up to {@code maxStep} above,
     * down to {@code depth} below, and {@code -depth} when nothing is there (a foot over a drop reaches
     * all the way down). NaN when the point is inside a block taller than a step.
     */
    private static double groundAt(Entity entity, double x, double y, double z, float maxStep, float depth) {
        Vec3 from = new Vec3(x, y + maxStep + 0.01, z);
        Vec3 to = new Vec3(x, y - depth - 0.01, z);
        BlockHitResult hit = entity.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity));
        if (hit.isInside()) {
            return Double.NaN;
        }
        if (hit.getType() == HitResult.Type.MISS) {
            return -depth;
        }
        return Math.max(-depth, Math.min(maxStep, hit.getLocation().y - y));
    }

    private static float rate(float perSecond, float dt) {
        return perSecond <= 0 ? 1 : 1 - (float) Math.exp(-perSecond * dt);
    }

    private static float clamp01(float v) {
        return Math.max(0, Math.min(1, v));
    }
}
