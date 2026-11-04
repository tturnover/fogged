package com.fogged.rig;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a model's bone names say it has. Naming is the whole contract between Blockbench and the rig:
 *
 * <ul>
 * <li>{@code root}: takes the body height. Without one, every top-level bone takes it.
 * <li>{@code leg_<id>} and {@code contact_<id>}: a pillar leg, vanilla style, one bone. The contact is an
 * empty bone at the sole inside it. The pillar slides up or down to meet the ground, at most
 * {@code max_step} either way, so its top should sit inside the body for that much slide both ways.
 * <li>{@code leg_<id>_upper}, {@code leg_<id>_lower}, {@code contact_<id>}: a jointed leg. The contact
 * sits under the lower bone, which sits under the upper one; the knee bends to meet the ground.
 * <li>{@code reach_<id>_up}, {@code reach_<id>_down}: optional empty bones beside a leg (not inside
 * it), at the highest and lowest the sole may be moved to meet the ground. Either can be left out.
 * <li>{@code *_tilt}: leans with the slope under the planted feet. No such bone, no tilt.
 * <li>{@code *_level}: inside a tilt bone, stays level while it leans, keeping its own animation.
 * Legs inside a tilt bone do this by themselves.
 * <li>{@code ground}: an empty control bone; its animated scale fades the whole ground fit (1, the
 * default, is full; 0 is off). A {@code contact_<id>}'s own scale picks that foot's mode: 0 off,
 * 1 (the default) fitted and kept out of blocks, 2 fitted only. Both are keyframes like any other, so
 * they can change for part of an animation and blend.
 * </ul>
 *
 * Anything that looks like part of a leg but doesn't make a whole one is reported, not guessed at.
 */
public final class RigLayout {

    /**
     * One leg: {@code upper} is the pillar itself when {@code lower} is -1. {@code up} and {@code down}
     * are its reach bones, or -1.
     */
    public record Leg(String id, int upper, int lower, int contact, int up, int down) {
        public boolean pillar() {
            return lower < 0;
        }
    }

    private static final Pattern UPPER = Pattern.compile("leg_(.+)_upper");
    private static final Pattern PILLAR = Pattern.compile("leg_(.+)");

    public final Leg[] legs;
    /** Bones that take the body height: {@code root}, or every top-level bone. */
    public final int[] bodyBones;
    public final int[] tiltBones;
    public final int[] levelBones;
    /** The {@code ground} control bone, or -1. */
    public final int ground;
    public final List<String> problems;

    private RigLayout(Leg[] legs, int[] bodyBones, int[] tiltBones, int[] levelBones, int ground, List<String> problems) {
        this.legs = legs;
        this.bodyBones = bodyBones;
        this.tiltBones = tiltBones;
        this.levelBones = levelBones;
        this.ground = ground;
        this.problems = problems;
    }

    /** Adds a leg's reach bones; one inside the leg would move with it and limit nothing, so it is refused. */
    private static Leg withReach(RigGeometry g, Leg leg, List<String> problems) {
        int[] reach = new int[2];
        String[] names = {"reach_" + leg.id() + "_up", "reach_" + leg.id() + "_down"};
        for (int k = 0; k < 2; k++) {
            int bone = g.bone(names[k]);
            if (bone >= 0 && (bone == leg.upper() || g.descendsFrom(bone, leg.upper()))) {
                problems.add(names[k] + " is inside its leg, so it moves with it; put it beside the leg (in root or the body)");
                bone = -1;
            }
            reach[k] = bone;
        }
        return new Leg(leg.id(), leg.upper(), leg.lower(), leg.contact(), reach[0], reach[1]);
    }

    public static RigLayout of(RigGeometry g) {
        List<String> problems = new ArrayList<>();
        List<Leg> legs = new ArrayList<>();
        List<Integer> tilt = new ArrayList<>();
        List<Integer> level = new ArrayList<>();
        for (int i = 0; i < g.bones.length; i++) {
            String name = g.bones[i].name;
            if (name.endsWith("_level")) {
                level.add(i);
            }
            if (name.endsWith("_tilt")) {
                tilt.add(i);
            }
            if (!name.endsWith("_upper") && !name.endsWith("_lower")) {
                Matcher p = PILLAR.matcher(name);
                if (p.matches()) {
                    String id = p.group(1);
                    int contact = g.bone("contact_" + id);
                    if (contact < 0) {
                        problems.add("leg " + id + " has no contact_" + id);
                    } else if (!g.descendsFrom(contact, i)) {
                        problems.add("contact_" + id + " is not inside leg_" + id);
                    } else {
                        legs.add(withReach(g, new Leg(id, i, -1, contact, -1, -1), problems));
                    }
                }
                continue;
            }
            Matcher m = UPPER.matcher(name);
            if (!m.matches()) {
                continue;
            }
            String id = m.group(1);
            int lower = g.bone("leg_" + id + "_lower");
            int contact = g.bone("contact_" + id);
            if (lower < 0 || contact < 0) {
                problems.add("leg " + id + " has no " + (lower < 0 ? "leg_" + id + "_lower" : "contact_" + id));
            } else if (!g.descendsFrom(lower, i)) {
                problems.add("leg_" + id + "_lower is not inside leg_" + id + "_upper");
            } else if (!g.descendsFrom(contact, lower)) {
                problems.add("contact_" + id + " is not inside leg_" + id + "_lower");
            } else {
                legs.add(withReach(g, new Leg(id, i, lower, contact, -1, -1), problems));
            }
        }
        for (int i = 0; i < g.bones.length; i++) {
            String name = g.bones[i].name;
            int bone = i;
            if (name.startsWith("contact_") && legs.stream().noneMatch(l -> l.contact == bone)) {
                problems.add(name + " belongs to no leg");
            }
            if (name.startsWith("reach_") && legs.stream().noneMatch(l -> name.equals("reach_" + l.id + "_up") || name.equals("reach_" + l.id + "_down"))) {
                problems.add(name + " belongs to no leg (reach_<id>_up / reach_<id>_down)");
            }
        }

        int ground = g.bone("ground");
        if (ground >= 0) {
            boolean children = false;
            for (RigGeometry.Bone b : g.bones) {
                children |= b.parent == ground;
            }
            if (g.bones[ground].quads.length > 0 || children) {
                problems.add("ground is a control bone: its scale switches the ground fit, so it should hold nothing");
            }
        }

        int root = g.bone("root");
        int[] body;
        if (root >= 0) {
            body = new int[] {root};
        } else {
            List<Integer> top = new ArrayList<>();
            for (int i = 0; i < g.bones.length; i++) {
                if (g.bones[i].parent < 0) {
                    top.add(i);
                }
            }
            body = top.stream().mapToInt(Integer::intValue).toArray();
        }
        // A leg hangs from a leaning body but doesn't lean with it: only its animation angles it.
        for (Leg leg : legs) {
            if (tilt.stream().anyMatch(t -> g.descendsFrom(leg.upper, t))) {
                level.add(leg.upper);
            }
        }
        return new RigLayout(legs.toArray(new Leg[0]), body, tilt.stream().mapToInt(Integer::intValue).toArray(),
                level.stream().mapToInt(Integer::intValue).toArray(), ground, problems);
    }
}
