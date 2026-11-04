package com.fogged.rig;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Per-bone animation values on top of the rest pose, plus the world-of-the-model matrices they produce.
 *
 * <p>Rotation (degrees) and position (pixels) add to the rest pose and scale multiplies it, the way
 * Blockbench applies keyframes. {@link #extra} is where the rig puts its corrections: a rotation
 * applied at the bone's pivot before its own, so the ground fit never has to turn back into Euler
 * angles.
 */
public final class RigPose {

    final float[] rotation;
    final float[] position;
    final float[] scale;
    final Quaternionf[] extra;
    /** Model-space matrix per bone, filled by {@link #solve}. Pixels, entity feet at the origin. */
    public final Matrix4f[] world;

    public RigPose(int bones) {
        rotation = new float[bones * 3];
        position = new float[bones * 3];
        scale = new float[bones * 3];
        extra = new Quaternionf[bones];
        world = new Matrix4f[bones];
        for (int i = 0; i < bones; i++) {
            extra[i] = new Quaternionf();
            world[i] = new Matrix4f();
        }
        reset();
    }

    public void reset() {
        java.util.Arrays.fill(rotation, 0);
        java.util.Arrays.fill(position, 0);
        java.util.Arrays.fill(scale, 1);
        for (Quaternionf q : extra) {
            q.identity();
        }
    }

    void setRotation(int bone, Vector3f v) {
        rotation[bone * 3] = v.x;
        rotation[bone * 3 + 1] = v.y;
        rotation[bone * 3 + 2] = v.z;
    }

    void setPosition(int bone, Vector3f v) {
        position[bone * 3] = v.x;
        position[bone * 3 + 1] = v.y;
        position[bone * 3 + 2] = v.z;
    }

    void setScale(int bone, Vector3f v) {
        scale[bone * 3] = v.x;
        scale[bone * 3 + 1] = v.y;
        scale[bone * 3 + 2] = v.z;
    }

    /** Moves a bone by {@code delta} pixels in model space. Needs {@link #world} current for its parent. */
    void moveInModelSpace(RigGeometry geometry, int bone, Vector3f delta) {
        int parent = geometry.bones[bone].parent;
        Vector3f local = new Vector3f(delta);
        if (parent >= 0) {
            world[parent].getNormalizedRotation(new Quaternionf()).conjugate().transform(local);
        }
        position[bone * 3] += local.x;
        position[bone * 3 + 1] += local.y;
        position[bone * 3 + 2] += local.z;
    }

    public void addPositionY(int bone, float pixels) {
        position[bone * 3 + 1] += pixels;
    }

    /** Takes {@code from}'s animated values. */
    void copyFrom(RigPose from) {
        System.arraycopy(from.rotation, 0, rotation, 0, rotation.length);
        System.arraycopy(from.position, 0, position, 0, position.length);
        System.arraycopy(from.scale, 0, scale, 0, scale.length);
    }

    /** Blends from {@code from} to this pose by {@code t} (0 is {@code from}, 1 keeps this). */
    void blendFrom(RigPose from, float t) {
        for (int i = 0; i < rotation.length; i++) {
            rotation[i] = from.rotation[i] + (rotation[i] - from.rotation[i]) * t;
            position[i] = from.position[i] + (position[i] - from.position[i]) * t;
            scale[i] = from.scale[i] + (scale[i] - from.scale[i]) * t;
        }
    }

    /**
     * Turns {@code worldDelta} (a rotation in model space, about the bone's pivot) into this bone's
     * {@link #extra}. Needs {@link #world} to be current for the bone's parent.
     */
    void rotateInModelSpace(RigGeometry geometry, int bone, Quaternionf worldDelta) {
        int parent = geometry.bones[bone].parent;
        Quaternionf parentRot = parent < 0 ? new Quaternionf() : world[parent].getNormalizedRotation(new Quaternionf());
        Quaternionf local = new Quaternionf(parentRot).conjugate().mul(worldDelta).mul(parentRot);
        extra[bone].premul(local);
    }

    /** Forward kinematics: fills {@link #world} from the rest pose plus this pose. */
    public void solve(RigGeometry geometry) {
        RigGeometry.Bone[] bones = geometry.bones;
        for (int i = 0; i < bones.length; i++) {
            RigGeometry.Bone b = bones[i];
            Matrix4f m = world[i];
            if (b.parent >= 0) {
                m.set(world[b.parent]);
            } else {
                m.identity();
            }
            int j = i * 3;
            m.translate(b.pivot.x + position[j], b.pivot.y + position[j + 1], b.pivot.z + position[j + 2])
                    .rotate(extra[i])
                    .rotateZYX((float) Math.toRadians(b.rotation.z + rotation[j + 2]),
                            (float) Math.toRadians(b.rotation.y + rotation[j + 1]),
                            (float) Math.toRadians(b.rotation.x + rotation[j]))
                    .scale(scale[j], scale[j + 1], scale[j + 2])
                    .translate(-b.pivot.x, -b.pivot.y, -b.pivot.z);
        }
    }

    /** A bone's pivot in model space, after {@link #solve}. */
    public Vector3f pivotOf(RigGeometry geometry, int bone) {
        return world[bone].transformPosition(new Vector3f(geometry.bones[bone].pivot));
    }
}
