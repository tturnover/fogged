package com.fogged.rig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix3f;
import org.joml.Vector3f;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * A model exported from Blockbench as "Bedrock Entity" geometry, turned back into Blockbench's own
 * space: Y up, pixels, the entity's front at -Z.
 *
 * <p>The Bedrock file mirrors X: Blockbench negates every X position and the X/Y rotations on export,
 * and stores a cube by its corner at the far X side. This undoes exactly that, so a model looks in
 * game the way it looks in the editor. Box UV is laid out the way Blockbench lays it out (sizes
 * floored, the same mirror rule), and a per-face UV keeps Blockbench's orientation: Bedrock flips the
 * up and down faces, which the parser flips back.
 *
 * <p>Every cube is baked into quads at load, its own rotation included, so a bone at draw time is just
 * a matrix and a list of quads.
 */
public final class RigGeometry {

    public final int textureWidth;
    public final int textureHeight;
    /** Parents always come before their children, so one pass in order resolves the hierarchy. */
    public final Bone[] bones;
    private final Map<String, Integer> index;

    public static final class Bone {
        public final String name;
        public final int parent;
        /** Rotation point, in pixels. */
        public final Vector3f pivot;
        /** Rest rotation, degrees, applied Z then Y then X like Blockbench. */
        public final Vector3f rotation;
        public final Quad[] quads;

        Bone(String name, int parent, Vector3f pivot, Vector3f rotation, Quad[] quads) {
            this.name = name;
            this.parent = parent;
            this.pivot = pivot;
            this.rotation = rotation;
            this.quads = quads;
        }
    }

    /**
     * Four corners in the order they are drawn (top-left, bottom-left, bottom-right, top-right as seen
     * from outside), UVs already divided by the texture size.
     */
    public record Quad(float[] positions, float[] uvs, Vector3f normal) {}

    private RigGeometry(int textureWidth, int textureHeight, Bone[] bones) {
        this.textureWidth = textureWidth;
        this.textureHeight = textureHeight;
        this.bones = bones;
        this.index = new HashMap<>();
        for (int i = 0; i < bones.length; i++) {
            index.put(bones[i].name, i);
        }
    }

    /** Bone index by name, or -1. */
    public int bone(String name) {
        Integer i = index.get(name);
        return i == null ? -1 : i;
    }

    public boolean descendsFrom(int bone, int ancestor) {
        for (int b = bones[bone].parent; b >= 0; b = bones[b].parent) {
            if (b == ancestor) {
                return true;
            }
        }
        return false;
    }

    // --- Parsing --------------------------------------------------------------

    public static RigGeometry parse(JsonObject file) {
        JsonArray geometries = file.getAsJsonArray("minecraft:geometry");
        if (geometries == null || geometries.isEmpty()) {
            throw new IllegalArgumentException("no minecraft:geometry (export as Bedrock Entity)");
        }
        JsonObject geometry = geometries.get(0).getAsJsonObject();
        JsonObject description = geometry.getAsJsonObject("description");
        int texW = description != null && description.has("texture_width") ? description.get("texture_width").getAsInt() : 64;
        int texH = description != null && description.has("texture_height") ? description.get("texture_height").getAsInt() : 64;

        List<JsonObject> raw = new ArrayList<>();
        if (geometry.has("bones")) {
            for (JsonElement e : geometry.getAsJsonArray("bones")) {
                raw.add(e.getAsJsonObject());
            }
        }

        // Order parents first; Blockbench already writes them that way, but a hand-edited file may not.
        List<JsonObject> ordered = new ArrayList<>();
        Map<String, Integer> placed = new HashMap<>();
        boolean progress = true;
        while (ordered.size() < raw.size() && progress) {
            progress = false;
            for (JsonObject b : raw) {
                String name = b.get("name").getAsString();
                if (placed.containsKey(name)) {
                    continue;
                }
                String parent = b.has("parent") ? b.get("parent").getAsString() : null;
                if (parent == null || placed.containsKey(parent)) {
                    placed.put(name, ordered.size());
                    ordered.add(b);
                    progress = true;
                }
            }
        }
        if (ordered.size() < raw.size()) {
            throw new IllegalArgumentException("bone with a missing parent, or a parent loop");
        }

        Bone[] bones = new Bone[ordered.size()];
        for (int i = 0; i < bones.length; i++) {
            JsonObject b = ordered.get(i);
            String parent = b.has("parent") ? b.get("parent").getAsString() : null;
            Vector3f pivot = vec(b, "pivot", 0);
            pivot.x = -pivot.x;
            Vector3f rotation = vec(b, "rotation", 0);
            rotation.x = -rotation.x;
            rotation.y = -rotation.y;
            boolean mirror = b.has("mirror") && b.get("mirror").getAsBoolean();
            float inflate = b.has("inflate") ? b.get("inflate").getAsFloat() : 0;

            List<Quad> quads = new ArrayList<>();
            if (b.has("cubes")) {
                for (JsonElement c : b.getAsJsonArray("cubes")) {
                    cube(c.getAsJsonObject(), mirror, inflate, texW, texH, quads);
                }
            }
            bones[i] = new Bone(b.get("name").getAsString(), parent == null ? -1 : placed.get(parent),
                    pivot, rotation, quads.toArray(new Quad[0]));
        }
        return new RigGeometry(texW, texH, bones);
    }

    private static final String[] FACES = {"north", "south", "west", "east", "up", "down"};

    private static void cube(JsonObject c, boolean boneMirror, float boneInflate, int texW, int texH, List<Quad> out) {
        Vector3f origin = vec(c, "origin", 0);
        Vector3f size = vec(c, "size", 0);
        float inflate = c.has("inflate") ? c.get("inflate").getAsFloat() : boneInflate;
        boolean mirror = c.has("mirror") ? c.get("mirror").getAsBoolean() : boneMirror;

        Vector3f from = new Vector3f(-(origin.x + size.x), origin.y, origin.z);
        Vector3f to = new Vector3f(from).add(size);
        from.sub(inflate, inflate, inflate);
        to.add(inflate, inflate, inflate);

        // A cube's own rotation is baked into its corners now; it never animates.
        Matrix3f rot = null;
        Vector3f cubePivot = null;
        if (c.has("rotation")) {
            Vector3f r = vec(c, "rotation", 0);
            r.x = -r.x;
            r.y = -r.y;
            if (r.x != 0 || r.y != 0 || r.z != 0) {
                rot = new Matrix3f().rotateZYX((float) Math.toRadians(r.z), (float) Math.toRadians(r.y),
                        (float) Math.toRadians(r.x));
                cubePivot = vec(c, "pivot", 0);
                cubePivot.x = -cubePivot.x;
            }
        }

        Map<String, float[]> rects = new HashMap<>();
        Map<String, Integer> rotations = new HashMap<>();
        JsonElement uv = c.get("uv");
        if (uv != null && uv.isJsonArray()) {
            boxUv(uv.getAsJsonArray().get(0).getAsFloat(), uv.getAsJsonArray().get(1).getAsFloat(), size, mirror, rects);
        } else if (uv != null && uv.isJsonObject()) {
            JsonObject faces = uv.getAsJsonObject();
            for (String face : FACES) {
                if (!faces.has(face)) {
                    continue;
                }
                JsonObject f = faces.getAsJsonObject(face);
                if (!f.has("uv_size")) {
                    continue; // Blockbench would auto-map it; nothing here to go on
                }
                float u = f.getAsJsonArray("uv").get(0).getAsFloat();
                float v = f.getAsJsonArray("uv").get(1).getAsFloat();
                float us = f.getAsJsonArray("uv_size").get(0).getAsFloat();
                float vs = f.getAsJsonArray("uv_size").get(1).getAsFloat();
                float[] rect = {u, v, u + us, v + vs};
                if (face.equals("up") || face.equals("down")) {
                    rect = new float[] {rect[2], rect[3], rect[0], rect[1]};
                }
                rects.put(face, rect);
                if (f.has("uv_rotation")) {
                    rotations.put(face, f.get("uv_rotation").getAsInt());
                }
            }
        }

        float fx = from.x, fy = from.y, fz = from.z, tx = to.x, ty = to.y, tz = to.z;
        for (String face : FACES) {
            float[] rect = rects.get(face);
            if (rect == null) {
                continue;
            }
            // Corners as top-left, top-right, bottom-left, bottom-right, seen from outside.
            float[][] corners;
            Vector3f normal;
            switch (face) {
                case "north" -> { corners = new float[][] {{tx, ty, fz}, {fx, ty, fz}, {tx, fy, fz}, {fx, fy, fz}}; normal = new Vector3f(0, 0, -1); }
                case "south" -> { corners = new float[][] {{fx, ty, tz}, {tx, ty, tz}, {fx, fy, tz}, {tx, fy, tz}}; normal = new Vector3f(0, 0, 1); }
                case "west" -> { corners = new float[][] {{fx, ty, fz}, {fx, ty, tz}, {fx, fy, fz}, {fx, fy, tz}}; normal = new Vector3f(-1, 0, 0); }
                case "east" -> { corners = new float[][] {{tx, ty, tz}, {tx, ty, fz}, {tx, fy, tz}, {tx, fy, fz}}; normal = new Vector3f(1, 0, 0); }
                case "up" -> { corners = new float[][] {{fx, ty, fz}, {tx, ty, fz}, {fx, ty, tz}, {tx, ty, tz}}; normal = new Vector3f(0, 1, 0); }
                default -> { corners = new float[][] {{fx, fy, tz}, {tx, fy, tz}, {fx, fy, fz}, {tx, fy, fz}}; normal = new Vector3f(0, -1, 0); }
            }
            if (isDegenerate(corners)) {
                continue;
            }

            float[][] uvs = {{rect[0], rect[1]}, {rect[2], rect[1]}, {rect[0], rect[3]}, {rect[2], rect[3]}};
            for (int r = rotations.getOrDefault(face, 0); r > 0; r -= 90) {
                float[] a = uvs[0];
                uvs[0] = uvs[2];
                uvs[2] = uvs[3];
                uvs[3] = uvs[1];
                uvs[1] = a;
            }

            if (rot != null) {
                for (float[] p : corners) {
                    Vector3f v = new Vector3f(p[0], p[1], p[2]).sub(cubePivot);
                    rot.transform(v).add(cubePivot);
                    p[0] = v.x;
                    p[1] = v.y;
                    p[2] = v.z;
                }
                rot.transform(normal);
            }

            // Draw order: top-left, bottom-left, bottom-right, top-right.
            int[] order = {0, 2, 3, 1};
            float[] positions = new float[12];
            float[] texture = new float[8];
            for (int k = 0; k < 4; k++) {
                int o = order[k];
                positions[k * 3] = corners[o][0];
                positions[k * 3 + 1] = corners[o][1];
                positions[k * 3 + 2] = corners[o][2];
                texture[k * 2] = uvs[o][0] / texW;
                texture[k * 2 + 1] = uvs[o][1] / texH;
            }
            out.add(new Quad(positions, texture, normal));
        }
    }

    /** Blockbench's box UV layout, sizes floored the way it floors them. */
    private static void boxUv(float u, float v, Vector3f size, boolean mirror, Map<String, float[]> out) {
        float w = (float) Math.floor(size.x + 1e-7);
        float h = (float) Math.floor(size.y + 1e-7);
        float d = (float) Math.floor(size.z + 1e-7);
        float[] east = {u, v + d, u + d, v + d + h};
        float[] west = {u + d + w, v + d, u + 2 * d + w, v + d + h};
        out.put("up", new float[] {u + d + w, v + d, u + d, v});
        out.put("down", new float[] {u + d + 2 * w, v, u + d + w, v + d});
        out.put("south", new float[] {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h});
        out.put("north", new float[] {u + d, v + d, u + d + w, v + d + h});
        out.put("east", east);
        out.put("west", west);
        if (mirror) {
            for (float[] r : out.values()) {
                float x = r[0];
                r[0] = r[2];
                r[2] = x;
            }
            out.put("east", west);
            out.put("west", east);
        }
    }

    private static boolean isDegenerate(float[][] c) {
        Vector3f a = new Vector3f(c[1][0] - c[0][0], c[1][1] - c[0][1], c[1][2] - c[0][2]);
        Vector3f b = new Vector3f(c[2][0] - c[0][0], c[2][1] - c[0][1], c[2][2] - c[0][2]);
        return a.cross(b).lengthSquared() < 1e-10f;
    }

    static Vector3f vec(JsonObject o, String key, float fallback) {
        if (!o.has(key)) {
            return new Vector3f(fallback);
        }
        JsonArray a = o.getAsJsonArray(key);
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
}
