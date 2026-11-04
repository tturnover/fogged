package com.fogged.rig;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fogged.Fogged;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;

/**
 * What a rigged mob's model decides about how it actually moves, for the server as much as the client:
 *
 * <ul>
 * <li>Step height: the lowest that any leg's {@code reach_<id>_up} bone sits above that leg's sole in
 * the rest pose. The legs then reach as high as the body can step, and the body steps (and pathfinds)
 * as high as the legs reach.
 * <li>Turning speed: {@code max_turn} in the model's tuning file, degrees per second.
 * <li>Actions: every animation that isn't a movement one, and from {@code still_actions} in the tuning
 * file which of them it stands still for.
 * </ul>
 *
 * <p>Read at startup on both sides: the files come from the mod's own jar (a dedicated server has no
 * client resources, and gameplay shouldn't change with a resource pack), or from the live source folder
 * in development. What a model doesn't say is left to code.
 */
public final class RigGameplay {

    private static final Map<String, Float> MAX_TURN = new ConcurrentHashMap<>();
    /** Animations the rig picks by itself; any other one in a model is an action. */
    private static final Set<String> MOVEMENT = Set.of("idle", "walk", "run", "jump", "walk_back",
            "strafe_left", "strafe_right", "turn_left", "turn_right");

    private RigGameplay() {}

    /**
     * Most the mob may turn, in degrees per tick, from {@code max_turn} (degrees per second) in
     * {@code rigs/<name>.json}; 0 leaves turning to vanilla. Read once per model.
     */
    public static float maxTurnPerTick(String name) {
        return MAX_TURN.computeIfAbsent(name, n -> {
            String path = "assets/" + Fogged.MODID + "/rigs/" + n + ".json";
            try (Reader reader = open(path)) {
                if (reader == null) {
                    return 0f;
                }
                JsonObject tuning = JsonParser.parseReader(reader).getAsJsonObject();
                float perSecond = tuning.has("max_turn") ? tuning.get("max_turn").getAsFloat() : 0;
                if (perSecond > 0) {
                    Fogged.LOGGER.info("[rig] {}:{}: turns at most {} degrees a second", Fogged.MODID, n, perSecond);
                }
                return Math.max(0, perSecond) / 20f;
            } catch (Exception ex) {
                Fogged.LOGGER.error("[rig] {}:{}: could not read max_turn from {}: {}", Fogged.MODID, n, path, ex.toString());
                return 0f;
            }
        });
    }

    /** The actions in {@code fogged:<name>}'s animation file, in its order. Read fresh, so a new export shows. */
    public static List<String> actions(String name) {
        List<String> actions = new ArrayList<>();
        String path = "assets/" + Fogged.MODID + "/animations/entity/" + name + ".animation.json";
        String prefix = "animation." + name + ".";
        try (Reader reader = open(path)) {
            if (reader != null) {
                for (String key : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations").keySet()) {
                    if (key.startsWith(prefix) && !MOVEMENT.contains(key.substring(prefix.length()))) {
                        actions.add(key.substring(prefix.length()));
                    }
                }
            }
        } catch (Exception ex) {
            Fogged.LOGGER.error("[rig] {}:{}: could not read actions from {}: {}", Fogged.MODID, name, path, ex.toString());
        }
        return actions;
    }

    /** Whether {@code still_actions} in the model's tuning file lists {@code action}. */
    public static boolean standsStill(String name, String action) {
        String path = "assets/" + Fogged.MODID + "/rigs/" + name + ".json";
        try (Reader reader = open(path)) {
            if (reader != null) {
                JsonObject tuning = JsonParser.parseReader(reader).getAsJsonObject();
                if (tuning.has("still_actions")) {
                    for (JsonElement e : tuning.getAsJsonArray("still_actions")) {
                        if (e.getAsString().equals(action)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Fogged.LOGGER.error("[rig] {}:{}: could not read still_actions from {}: {}", Fogged.MODID, name, path, ex.toString());
        }
        return false;
    }

    /** Step height in blocks for the model {@code fogged:<name>}, or empty to keep the coded one. */
    public static OptionalDouble stepHeight(String name) {
        String path = "assets/" + Fogged.MODID + "/geo/entity/" + name + ".geo.json";
        try (Reader reader = open(path)) {
            if (reader == null) {
                return OptionalDouble.empty();
            }
            RigGeometry g = RigGeometry.parse(JsonParser.parseReader(reader).getAsJsonObject());
            RigLayout layout = RigLayout.of(g);
            RigPose pose = new RigPose(g.bones.length);
            pose.solve(g);
            double lowest = Double.POSITIVE_INFINITY;
            for (RigLayout.Leg leg : layout.legs) {
                if (leg.up() >= 0) {
                    lowest = Math.min(lowest, (pose.pivotOf(g, leg.up()).y - pose.pivotOf(g, leg.contact()).y) / 16.0);
                }
            }
            if (lowest == Double.POSITIVE_INFINITY) {
                return OptionalDouble.empty();
            }
            double step = Math.max(0, lowest);
            Fogged.LOGGER.info("[rig] {}:{}: step height {} blocks, from its reach_*_up bones", Fogged.MODID, name, step);
            return OptionalDouble.of(step);
        } catch (Exception ex) {
            Fogged.LOGGER.error("[rig] {}:{}: could not read step height from {}: {}", Fogged.MODID, name, path, ex.toString());
            return OptionalDouble.empty();
        }
    }

    /**
     * Puts a loaded mob's step height back to its type's current one. A mob saves its attributes with
     * the world, so without this one summoned before a model change would keep the old step height.
     * Call after the saved data is read.
     */
    @SuppressWarnings("unchecked")
    public static void restore(LivingEntity mob) {
        AttributeInstance step = mob.getAttribute(Attributes.STEP_HEIGHT);
        if (step != null) {
            step.setBaseValue(DefaultAttributes.getSupplier((EntityType<? extends LivingEntity>) mob.getType())
                    .getBaseValue(Attributes.STEP_HEIGHT));
        }
    }

    private static Reader open(String path) throws Exception {
        String source = System.getProperty("fogged.rigSource");
        if (source != null && Files.isRegularFile(Path.of(source, path))) {
            return Files.newBufferedReader(Path.of(source, path));
        }
        InputStream in = RigGameplay.class.getResourceAsStream("/" + path);
        return in == null ? null : new InputStreamReader(in, StandardCharsets.UTF_8);
    }
}
