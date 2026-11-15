package com.fogged;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * The fog walker's worn models, read straight from the Blockbench files under
 * {@code assets/fogged/models/armor/} (Generic Model, per-face UV), so an edit in Blockbench shows after
 * F3+T. Two of them: {@code fog_diving_set_worn} with the helmet on, {@code fog_diving_set_headless} with
 * another helmet in the head slot and the suit's own hanging at the side.
 *
 * <p>What the file says, and how it is read:
 * <ul>
 * <li>Each top-level group rides the player's part of the same name ({@code head}, {@code body},
 * {@code right_arm}, {@code left_arm}, {@code right_leg}, {@code left_leg}), turning about that group's
 * origin; anything under it goes along. A group not exported (the grey {@code player_reference}) is left out.
 * <li>Cubes named {@code armor_*} are the plain chestplate the vanilla armor layer already draws from the
 * same texture, so they are skipped here.
 * <li>A face on a texture whose name starts with {@code filter} is drawn in the fitted filter's own block
 * texture (the file's {@code filter.png} is cyan wool, standing in for it), and only while one is fitted;
 * every other textured face is on the armor layer, always. A face with no texture is not drawn.
 * </ul>
 */
public final class FogWalkerModels extends SimplePreparableReloadListener<Map<String, FogWalkerModels.Model>> {

    public static final FogWalkerModels INSTANCE = new FogWalkerModels();

    public static final String WORN = "fog_diving_set_worn";
    public static final String HEADLESS = "fog_diving_set_headless";

    public enum Part { HEAD, BODY, RIGHT_ARM, LEFT_ARM, RIGHT_LEG, LEFT_LEG }

    /**
     * One face: four corners in the player part's own model space, in pixels about its pivot (the way a
     * ModelPart's cubes sit), in draw order; UVs as fractions of its texture; and the normal.
     */
    public record Quad(float[] positions, float[] uvs, Vector3f normal, boolean filterTexture) {}

    public record Model(Map<Part, Quad[]> parts) {}

    private volatile Map<String, Model> models = Map.of();

    private FogWalkerModels() {}

    public static Model get(String name) {
        return INSTANCE.models.get(name);
    }

    @Override
    protected Map<String, Model> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<String, Model> out = new HashMap<>();
        for (String name : List.of(WORN, HEADLESS)) {
            ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "models/armor/" + name + ".bbmodel");
            var resource = manager.getResource(loc);
            if (resource.isEmpty()) {
                Fogged.LOGGER.warn("[fog walker] missing model {}", loc);
                continue;
            }
            try (Reader reader = resource.get().openAsReader()) {
                out.put(name, parse(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (IOException | RuntimeException e) {
                Fogged.LOGGER.error("[fog walker] could not read {}: {}", loc, e.toString());
            }
        }
        return out;
    }

    @Override
    protected void apply(Map<String, Model> prepared, ResourceManager manager, ProfilerFiller profiler) {
        models = Map.copyOf(prepared);
    }

    // --- Parsing --------------------------------------------------------------

    private record Texture(boolean filter, float width, float height) {}

    static Model parse(JsonObject file) {
        List<Texture> textures = new ArrayList<>();
        if (file.has("textures")) {
            for (JsonElement t : file.getAsJsonArray("textures")) {
                JsonObject o = t.getAsJsonObject();
                String name = o.has("name") ? o.get("name").getAsString() : "";
                float w = num(o, "uv_width", num(o, "width", 16));
                float h = num(o, "uv_height", num(o, "height", 16));
                textures.add(new Texture(name.startsWith("filter"), w, h));
            }
        }
        Map<String, JsonObject> elements = new HashMap<>();
        for (JsonElement e : file.getAsJsonArray("elements")) {
            JsonObject o = e.getAsJsonObject();
            elements.put(o.get("uuid").getAsString(), o);
        }
        // Blockbench 4.10+ keeps a group's own data in "groups", the outliner only nesting their ids.
        Map<String, JsonObject> groups = new HashMap<>();
        if (file.has("groups")) {
            for (JsonElement g : file.getAsJsonArray("groups")) {
                JsonObject o = g.getAsJsonObject();
                groups.put(o.get("uuid").getAsString(), o);
            }
        }

        Map<Part, List<Quad>> parts = new EnumMap<>(Part.class);
        for (JsonElement top : file.getAsJsonArray("outliner")) {
            if (!top.isJsonObject()) {
                continue; // a loose cube with no group rides nothing
            }
            JsonObject node = top.getAsJsonObject();
            JsonObject group = groupData(node, groups);
            if (!exported(group)) {
                continue;
            }
            Part part;
            try {
                part = Part.valueOf(group.get("name").getAsString().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                Fogged.LOGGER.warn("[fog walker] group '{}' names no player part; left out", group.get("name"));
                continue;
            }
            Vector3f origin = vec(group, "origin");
            // The part's pivot in the player's model space: Blockbench's x and y flipped, y from the feet.
            Vector3f pivot = new Vector3f(-origin.x, 24 - origin.y, origin.z);
            walk(node, groups, elements, textures, new Matrix4f(), pivot,
                    parts.computeIfAbsent(part, p -> new ArrayList<>()));
        }
        Map<Part, Quad[]> baked = new EnumMap<>(Part.class);
        parts.forEach((p, quads) -> baked.put(p, quads.toArray(new Quad[0])));
        return new Model(baked);
    }

    private static JsonObject groupData(JsonObject node, Map<String, JsonObject> groups) {
        JsonObject g = node.has("uuid") ? groups.get(node.get("uuid").getAsString()) : null;
        return g != null ? g : node; // an older file keeps it inline
    }

    private static boolean exported(JsonObject group) {
        return !group.has("export") || group.get("export").getAsBoolean();
    }

    // A group's own turn, about its origin, then everything in it.
    private static void walk(JsonObject node, Map<String, JsonObject> groups, Map<String, JsonObject> elements,
            List<Texture> textures, Matrix4f parent, Vector3f pivot, List<Quad> out) {
        JsonObject group = groupData(node, groups);
        Matrix4f m = new Matrix4f(parent).mul(turn(group));
        for (JsonElement child : node.getAsJsonArray("children")) {
            if (child.isJsonObject()) {
                JsonObject sub = child.getAsJsonObject();
                if (exported(groupData(sub, groups))) {
                    walk(sub, groups, elements, textures, m, pivot, out);
                }
                continue;
            }
            JsonObject cube = elements.get(child.getAsString());
            if (cube != null && (!cube.has("type") || cube.get("type").getAsString().equals("cube"))) {
                cube(cube, textures, m, pivot, out);
            }
        }
    }

    // Rotation about the object's origin, degrees, Z then Y then X like Blockbench.
    private static Matrix4f turn(JsonObject o) {
        Vector3f r = vec(o, "rotation");
        if (r.x == 0 && r.y == 0 && r.z == 0) {
            return new Matrix4f();
        }
        Vector3f origin = vec(o, "origin");
        return new Matrix4f().translate(origin)
                .rotateZYX((float) Math.toRadians(r.z), (float) Math.toRadians(r.y), (float) Math.toRadians(r.x))
                .translate(-origin.x, -origin.y, -origin.z);
    }

    private static final String[] FACES = {"north", "south", "west", "east", "up", "down"};

    private static void cube(JsonObject c, List<Texture> textures, Matrix4f group, Vector3f pivot, List<Quad> out) {
        String name = c.has("name") ? c.get("name").getAsString() : "";
        if (name.startsWith("armor_")) {
            return; // the vanilla armor layer draws these
        }
        float inflate = num(c, "inflate", 0);
        Vector3f from = vec(c, "from").sub(inflate, inflate, inflate);
        Vector3f to = vec(c, "to").add(inflate, inflate, inflate);
        Matrix4f m = new Matrix4f(group).mul(turn(c));
        float fx = from.x, fy = from.y, fz = from.z, tx = to.x, ty = to.y, tz = to.z;

        JsonObject faces = c.getAsJsonObject("faces");
        for (String face : FACES) {
            if (faces == null || !faces.has(face)) {
                continue;
            }
            JsonObject f = faces.getAsJsonObject(face);
            if (!f.has("texture") || f.get("texture").isJsonNull()) {
                continue; // no texture: Blockbench draws nothing there
            }
            int ti = f.get("texture").getAsInt();
            if (ti < 0 || ti >= textures.size()) {
                continue;
            }
            Texture tex = textures.get(ti);
            JsonArray uv = f.getAsJsonArray("uv");
            float[] rect = {uv.get(0).getAsFloat(), uv.get(1).getAsFloat(), uv.get(2).getAsFloat(), uv.get(3).getAsFloat()};

            // Corners as top-left, top-right, bottom-left, bottom-right, seen from outside (Blockbench's space:
            // Y up, the front at -Z).
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
            float[][] uvs = {{rect[0], rect[1]}, {rect[2], rect[1]}, {rect[0], rect[3]}, {rect[2], rect[3]}};
            for (int r = f.has("rotation") ? f.get("rotation").getAsInt() : 0; r > 0; r -= 90) {
                float[] a = uvs[0];
                uvs[0] = uvs[2];
                uvs[2] = uvs[3];
                uvs[3] = uvs[1];
                uvs[1] = a;
            }

            // Draw order: top-left, bottom-left, bottom-right, top-right. Into the player part's space:
            // Blockbench's x and y flipped (a half turn about Z, so faces keep their winding), about the pivot.
            int[] order = {0, 2, 3, 1};
            float[] positions = new float[12];
            float[] texture = new float[8];
            for (int k = 0; k < 4; k++) {
                float[] p = corners[order[k]];
                Vector4f v = m.transform(new Vector4f(p[0], p[1], p[2], 1));
                positions[k * 3] = -v.x - pivot.x;
                positions[k * 3 + 1] = 24 - v.y - pivot.y;
                positions[k * 3 + 2] = v.z - pivot.z;
                texture[k * 2] = uvs[order[k]][0] / tex.width();
                texture[k * 2 + 1] = uvs[order[k]][1] / tex.height();
            }
            m.transformDirection(normal).normalize();
            out.add(new Quad(positions, texture, new Vector3f(-normal.x, -normal.y, normal.z), tex.filter()));
        }
    }

    private static float num(JsonObject o, String key, float fallback) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsFloat() : fallback;
    }

    private static Vector3f vec(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return new Vector3f();
        }
        JsonArray a = o.getAsJsonArray(key);
        return new Vector3f(a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat());
    }
}
