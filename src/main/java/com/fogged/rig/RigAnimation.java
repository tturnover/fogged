package com.fogged.rig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.joml.Vector3f;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * One animation from a Blockbench "Bedrock Entity" animation file.
 *
 * <p>Values are turned back into Blockbench's space the same way {@link RigGeometry} is (X position and
 * X/Y rotation negated). Keyframes play the way the editor plays them: linear between two keyframes,
 * Catmull-Rom when either end is smooth, and a step is the file's own pre/post pair. Bezier keyframes
 * arrive already baked into linear ones by Blockbench's exporter.
 *
 * <p>Molang expressions are not evaluated: a value that is not a plain number reads as zero (one for
 * scale), and the loader reports which animation used one.
 */
public final class RigAnimation {

    public enum Loop { ONCE, LOOP, HOLD }

    public static final int ROTATION = 0;
    public static final int POSITION = 1;
    public static final int SCALE = 2;
    private static final String[] CHANNELS = {"rotation", "position", "scale"};

    public final String name;
    public final float length;
    public final Loop loop;
    /** Bone name to its three channels (rotation, position, scale); a missing channel is null. */
    private final Map<String, Track[]> tracks;
    /** Bones with a keyframe that held a Molang expression (read as rest instead). */
    public final Set<String> usedMolang;

    private RigAnimation(String name, float length, Loop loop, Map<String, Track[]> tracks, Set<String> usedMolang) {
        this.name = name;
        this.length = length;
        this.loop = loop;
        this.tracks = tracks;
        this.usedMolang = usedMolang;
    }

    record Key(float time, Vector3f pre, Vector3f post, boolean smooth, boolean single) {}

    static final class Track {
        final Key[] keys;

        Track(Key[] keys) {
            this.keys = keys;
        }

        void sample(float t, Vector3f out) {
            Key[] k = keys;
            if (t <= k[0].time) {
                out.set(k[0].pre);
                return;
            }
            int last = k.length - 1;
            if (t >= k[last].time) {
                out.set(k[last].post);
                return;
            }
            int i = 0;
            while (k[i + 1].time <= t) {
                i++;
            }
            Key a = k[i];
            Key b = k[i + 1];
            float alpha = (t - a.time) / (b.time - a.time);
            if (a.smooth || b.smooth) {
                // Blockbench's spline: a neighbour only counts when the inner key has no pre/post split.
                Vector3f p0 = i > 0 && a.single ? k[i - 1].post : a.post;
                Vector3f p3 = i + 2 <= last && b.single ? k[i + 2].pre : b.pre;
                out.set(catmullRom(alpha, p0.x, a.post.x, b.pre.x, p3.x),
                        catmullRom(alpha, p0.y, a.post.y, b.pre.y, p3.y),
                        catmullRom(alpha, p0.z, a.post.z, b.pre.z, p3.z));
            } else {
                a.post.lerp(b.pre, alpha, out);
            }
        }

        private static float catmullRom(float t, float p0, float p1, float p2, float p3) {
            float v0 = (p2 - p0) * 0.5f;
            float v1 = (p3 - p1) * 0.5f;
            float t2 = t * t;
            float t3 = t * t2;
            return (2 * p1 - 2 * p2 + v0 + v1) * t3 + (-3 * p1 + 3 * p2 - 2 * v0 - v1) * t2 + v0 * t + p1;
        }
    }

    /** Animations bound to a model's bone indices, so sampling never looks a name up. */
    public static final class Bound {
        public final RigAnimation animation;
        private final Track[][] byBone;

        Bound(RigAnimation animation, Track[][] byBone) {
            this.animation = animation;
            this.byBone = byBone;
        }

        /** Writes this animation at {@code time} seconds into {@code pose} (rest is zero/zero/one). */
        public void sample(float time, RigPose pose) {
            RigAnimation a = animation;
            if (a.length > 0) {
                // A loop played backwards counts down through negative time, so wrap both ways.
                time = a.loop == Loop.LOOP ? ((time % a.length) + a.length) % a.length : Math.max(0, Math.min(time, a.length));
            }
            Vector3f v = new Vector3f();
            for (int bone = 0; bone < byBone.length; bone++) {
                Track[] t = byBone[bone];
                if (t == null) {
                    continue;
                }
                if (t[ROTATION] != null) {
                    t[ROTATION].sample(time, v);
                    pose.setRotation(bone, v);
                }
                if (t[POSITION] != null) {
                    t[POSITION].sample(time, v);
                    pose.setPosition(bone, v);
                }
                if (t[SCALE] != null) {
                    t[SCALE].sample(time, v);
                    pose.setScale(bone, v);
                }
            }
        }

        public boolean finished(float time) {
            return animation.loop == Loop.ONCE && time >= animation.length;
        }
    }

    /** Binds to {@code geometry}; bone names it does not have go into {@code missing}. */
    public Bound bind(RigGeometry geometry, Set<String> missing) {
        Track[][] byBone = new Track[geometry.bones.length][];
        for (Map.Entry<String, Track[]> e : tracks.entrySet()) {
            int bone = geometry.bone(e.getKey());
            if (bone < 0) {
                missing.add(e.getKey());
            } else {
                byBone[bone] = e.getValue();
            }
        }
        return new Bound(this, byBone);
    }

    // --- Parsing --------------------------------------------------------------

    public static Map<String, RigAnimation> parseFile(JsonObject file) {
        Map<String, RigAnimation> out = new LinkedHashMap<>();
        JsonObject animations = file.getAsJsonObject("animations");
        if (animations == null) {
            return out;
        }
        for (Map.Entry<String, JsonElement> e : animations.entrySet()) {
            out.put(e.getKey(), parse(e.getKey(), e.getValue().getAsJsonObject()));
        }
        return out;
    }

    private static RigAnimation parse(String name, JsonObject a) {
        Loop loop = Loop.ONCE;
        JsonElement l = a.get("loop");
        if (l != null && l.isJsonPrimitive()) {
            if (l.getAsJsonPrimitive().isBoolean()) {
                loop = l.getAsBoolean() ? Loop.LOOP : Loop.ONCE;
            } else if ("hold_on_last_frame".equals(l.getAsString())) {
                loop = Loop.HOLD;
            }
        }

        Set<String> molang = new TreeSet<>();
        Map<String, Track[]> tracks = new HashMap<>();
        float longest = 0;
        JsonObject bones = a.getAsJsonObject("bones");
        if (bones != null) {
            for (Map.Entry<String, JsonElement> b : bones.entrySet()) {
                JsonObject channels = b.getValue().getAsJsonObject();
                Track[] t = new Track[3];
                for (int c = 0; c < 3; c++) {
                    if (channels.has(CHANNELS[c])) {
                        t[c] = track(channels.get(CHANNELS[c]), c, b.getKey(), molang);
                        longest = Math.max(longest, t[c].keys[t[c].keys.length - 1].time);
                    }
                }
                tracks.put(b.getKey(), t);
            }
        }
        float length = a.has("animation_length") ? a.get("animation_length").getAsFloat() : longest;
        return new RigAnimation(name, length, loop, tracks, molang);
    }

    private static Track track(JsonElement e, int channel, String bone, Set<String> molang) {
        List<Key> keys = new ArrayList<>();
        if (e.isJsonObject() && !isKeyObject(e.getAsJsonObject())) {
            for (Map.Entry<String, JsonElement> k : e.getAsJsonObject().entrySet()) {
                keys.add(key(Float.parseFloat(k.getKey()), k.getValue(), channel, bone, molang));
            }
            keys.sort((x, y) -> Float.compare(x.time, y.time));
        } else {
            keys.add(key(0, e, channel, bone, molang));
        }
        return new Track(keys.toArray(new Key[0]));
    }

    private static boolean isKeyObject(JsonObject o) {
        return o.has("pre") || o.has("post") || o.has("lerp_mode");
    }

    private static Key key(float time, JsonElement e, int channel, String bone, Set<String> molang) {
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            Vector3f pre = o.has("pre") ? value(o.get("pre"), channel, bone, molang) : null;
            Vector3f post = o.has("post") ? value(o.get("post"), channel, bone, molang) : null;
            if (pre == null) {
                pre = post;
            }
            if (post == null) {
                post = pre;
            }
            boolean smooth = o.has("lerp_mode") && "catmullrom".equals(o.get("lerp_mode").getAsString());
            return new Key(time, pre, post, smooth, !o.has("pre") || !o.has("post"));
        }
        Vector3f v = value(e, channel, bone, molang);
        return new Key(time, v, v, false, true);
    }

    private static Vector3f value(JsonElement e, int channel, String bone, Set<String> molang) {
        float rest = channel == SCALE ? 1 : 0;
        Vector3f v;
        if (e.isJsonArray()) {
            v = new Vector3f(number(e.getAsJsonArray().get(0), rest, bone, molang),
                    number(e.getAsJsonArray().get(1), rest, bone, molang),
                    number(e.getAsJsonArray().get(2), rest, bone, molang));
        } else {
            v = new Vector3f(number(e, rest, bone, molang));
        }
        if (channel == ROTATION) {
            v.x = -v.x;
            v.y = -v.y;
        } else if (channel == POSITION) {
            v.x = -v.x;
        }
        return v;
    }

    private static float number(JsonElement e, float fallback, String bone, Set<String> molang) {
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
            return e.getAsFloat();
        }
        try {
            return Float.parseFloat(e.getAsString().trim());
        } catch (RuntimeException ex) {
            molang.add(bone);
            return fallback;
        }
    }
}
