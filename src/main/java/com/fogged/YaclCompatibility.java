package com.fogged;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.ListOption;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.OptionGroup;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.ColorControllerBuilder;
import dev.isxander.yacl3.api.controller.DoubleSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.EnumControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import dev.isxander.yacl3.api.controller.TickBoxControllerBuilder;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;

// The YACL config screen, isolated so nothing else in the mod ever names a YACL class. YACL is an
// optional runtime mod: FoggedClient checks it is loaded before touching this class at all, so with
// YACL absent none of these types are ever resolved and the NeoForge screen is used instead.
//
// Every option here binds straight to the ModConfigSpec values in Config -- the specs stay the single
// source of truth, so the TOML files, NeoForge's screen and this one can never disagree. Names and
// tooltips reuse the same fogged.configuration.* translation keys NeoForge's screen derives, so both
// screens are translated by one set of lang entries.
final class YaclCompatibility {

    private static final String KEY = "fogged.configuration.";

    private YaclCompatibility() {
    }

    // Null if YACL is present but its API has moved on: the caller falls back to NeoForge's screen
    // rather than taking the whole config UI down with it.
    static Screen screen(Screen parent) {
        try {
            return build(parent);
        } catch (RuntimeException | LinkageError e) {
            Fogged.LOGGER.warn("Fogged: could not build the YACL config screen; using NeoForge's instead", e);
            return null;
        }
    }

    private static Screen build(Screen parent) {
        return YetAnotherConfigLib.createBuilder()
                .title(Component.translatable("fogged.configuration.title"))
                .category(boundary())
                .category(suffocation())
                .category(pillars())
                .category(plane())
                .category(debug())
                .save(() -> {
                    Config.COMMON_SPEC.save();
                    Config.CLIENT_SPEC.save();
                })
                .build()
                .generateScreen(parent);
    }

    private static ConfigCategory boundary() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable(KEY + "boundary"))
                .tooltip(Component.translatable(KEY + "boundary.tooltip"))
                .group(strings("planeHeightSchedule", Config.PLANE_HEIGHT_SCHEDULE))
                .option(bool("planeHeightCycle", Config.PLANE_HEIGHT_CYCLE))
                .option(dbl("overdayOffsetNoon", Config.OVERDAY_OFFSET_NOON, -64.0, 64.0, 0.25))
                .option(dbl("overdayOffsetMidnight", Config.OVERDAY_OFFSET_MIDNIGHT, -64.0, 64.0, 0.25))
                .option(integer("fogDistance", Config.FOG_DISTANCE, 4, 256, 1))
                .option(dbl("fogStartRaise", Config.FOG_START_RAISE, -8.0, 8.0, 0.05))
                .option(bool("flipFog", Config.FLIP_FOG))
                .option(bool("enableWorldChanges", Config.ENABLE_WORLD_CHANGES))
                .option(integer("worldChangeSkip", Config.WORLD_CHANGE_SKIP, 0, 64, 1))
                .option(integer("worldChangeDelaySeconds", Config.WORLD_CHANGE_DELAY, 0, 60, 1))
                .option(dbl("murkThirstScale", Config.MURK_THIRST_SCALE, 0.0, 1.0, 0.05))
                .option(dbl("murkColdness", Config.MURK_COLDNESS, 0.0, 1.0, 0.05))
                // The three switches together, under one heading, and then the three lists they govern
                // in the same order. They cannot be interleaved with their lists: a category renders
                // every loose option BEFORE any of its groups, and a list IS a group.
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable(KEY + "group.murkActions"))
                        .description(describe("group.murkActions"))
                        .option(bool("enableExtinguish", Config.ENABLE_EXTINGUISH))
                        .option(bool("enableScour", Config.ENABLE_SCOUR))
                        .option(bool("scourAllPlants", Config.SCOUR_ALL_PLANTS))
                        .option(bool("enableTransforms", Config.ENABLE_TRANSFORMS))
                        .build())
                .group(strings("snuffedDevices", Config.SNUFFED_DEVICES))
                .group(strings("scouredBlocks", Config.SCOURED_BLOCKS))
                .group(strings("transforms", Config.TRANSFORMS))
                .build();
    }

    private static ConfigCategory suffocation() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable(KEY + "suffocation"))
                .tooltip(Component.translatable(KEY + "suffocation.tooltip"))
                .option(bool("playerSuffocation", Config.PLAYER_SUFFOCATION))
                .option(integer("airLossPerTick", Config.AIR_LOSS_PER_TICK, 1, 300, 1))
                .option(bool("depthScaling", Config.DEPTH_SCALING))
                .option(dbl("depthScalingBlocks", Config.DEPTH_SCALING_BLOCKS, 0.0, 128.0, 1.0))
                .option(dbl("depthScalingPercent", Config.DEPTH_SCALING_PERCENT, 0.0, 100.0, 0.5))
                .option(bool("mobSuffocation", Config.MOB_SUFFOCATION))
                .option(bool("mobSpawnsInBreathingSpheres", Config.MOB_SPAWNS_IN_SPHERES))
                .option(bool("mobEscape", Config.MOB_ESCAPE))
                .option(integer("mobSuffocateDelaySeconds", Config.MOB_SUFFOCATE_DELAY, 0, 600, 1))
                .option(dbl("mobSuffocateDamage", Config.MOB_SUFFOCATE_DAMAGE, 0.0, 40.0, 0.5))
                .group(strings("allowedMobs", Config.ALLOWED_MOBS))
                .build();
    }

    private static ConfigCategory plane() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable(KEY + "plane"))
                .tooltip(Component.translatable(KEY + "plane.tooltip"))
                .option(bool("renderPlane", Config.RENDER_PLANE))
                // Plane alpha is ignored by the renderer (it always draws opaque), so don't offer it.
                .option(color("planeColor", Config.PLANE_COLOR, false))
                .option(color("foamColor", Config.FOAM_COLOR, true))
                .option(dbl("foamWidth", Config.FOAM_WIDTH, 0.0, 8.0, 0.25))
                .option(dbl("foamReach", Config.FOAM_REACH, 0.0, 4.0, 0.25))
                .option(bool("sableFoam", Config.SABLE_FOAM))
                .option(bool("planeSoftOcclusion", Config.PLANE_SOFT_OCCLUSION))
                .option(dbl("murkDarkness", Config.MURK_DARKNESS, 0.0, 1.0, 0.05))
                .option(integer("waterlineCellsPerBlock", Config.WATERLINE_CELLS_PER_BLOCK, 1, 4, 1))
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable(KEY + "dither"))
                        .description(describe("dither"))
                        .option(bool("planeNearDither", Config.PLANE_NEAR_DITHER))
                        .option(dbl("nearDitherStart", Config.NEAR_DITHER_START, 0.0, 32.0, 0.25))
                        .option(dbl("nearDitherEnd", Config.NEAR_DITHER_END, 0.0, 64.0, 0.25))
                        .option(dbl("nearDitherMinVisibility", Config.NEAR_DITHER_MIN_VISIBILITY, 0.0, 1.0, 0.05))
                        .option(integer("ditherPixelSize", Config.DITHER_PIXEL_SIZE, 1, 8, 1))
                        .option(bool("stepDither", Config.STEP_DITHER))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable(KEY + "vapor"))
                        .description(describe("vapor"))
                        .option(dbl("vaporColorOffsetRed", Config.VAPOR_OFFSET_RED, -1.0, 1.0, 0.05))
                        .option(dbl("vaporColorOffsetGreen", Config.VAPOR_OFFSET_GREEN, -1.0, 1.0, 0.05))
                        .option(dbl("vaporColorOffsetBlue", Config.VAPOR_OFFSET_BLUE, -1.0, 1.0, 0.05))
                        .option(dbl("vaporStrength", Config.VAPOR_STRENGTH, 0.0, 1.0, 0.05))
                        .option(integer("vaporSheets", Config.VAPOR_SHEETS, 0, 8, 1))
                        .option(dbl("vaporUndulation", Config.VAPOR_UNDULATION, 0.0, 16.0, 0.25))
                        .option(bool("vaporUnderside", Config.VAPOR_UNDERSIDE))
                        .option(bool("vaporUnderwater", Config.VAPOR_UNDERWATER))
                        .build())
                .build();
    }

    private static ConfigCategory pillars() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable(KEY + "pillars"))
                .tooltip(Component.translatable(KEY + "pillars.tooltip"))
                .option(bool("generatePillars", Config.GENERATE_PILLARS))
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable(KEY + "layout"))
                        .description(describe("layout"))
                        .option(integer("pillarGroupSpacing", Config.PILLAR_GROUP_SPACING, 64, 60000, 500))
                        .option(bool("pillarSpawnGroup", Config.PILLAR_SPAWN_GROUP))
                        .option(dbl("pillarRidgeChance", Config.PILLAR_RIDGE_CHANCE, 0.0, 1.0, 0.05))
                        .option(integer("pillarRidgeLengthMin", Config.PILLAR_RIDGE_LENGTH_MIN, 32, 2000, 20))
                        .option(integer("pillarRidgeLengthMax", Config.PILLAR_RIDGE_LENGTH_MAX, 32, 2000, 20))
                        .option(integer("pillarIsleRadius", Config.PILLAR_ISLE_RADIUS, 16, 500, 5))
                        .option(dbl("pillarDensity", Config.PILLAR_DENSITY, 0.0, 1.0, 0.05))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable(KEY + "shape"))
                        .description(describe("shape"))
                        .option(text("pillarBlock", Config.PILLAR_BLOCK))
                        .option(dbl("pillarRadiusMin", Config.PILLAR_RADIUS_MIN, 1.0, 24.0, 0.5))
                        .option(dbl("pillarRadiusMax", Config.PILLAR_RADIUS_MAX, 1.0, 24.0, 0.5))
                        .option(integer("pillarTopMin", Config.PILLAR_TOP_MIN, -128, 128, 1))
                        .option(integer("pillarTopMax", Config.PILLAR_TOP_MAX, 0, 128, 1))
                        .option(dbl("pillarTilt", Config.PILLAR_TILT, 0.0, 20.0, 0.5))
                        .option(integer("pillarLedges", Config.PILLAR_LEDGES, 0, 12, 1))
                        .build())
                .group(OptionGroup.createBuilder()
                        .name(Component.translatable(KEY + "surface"))
                        .description(describe("surface"))
                        .option(bool("pillarStoneBands", Config.PILLAR_STONE_BANDS))
                        .option(integer("pillarBaseErosion", Config.PILLAR_BASE_EROSION, 0, 16, 1))
                        .option(bool("pillarClimate", Config.PILLAR_CLIMATE))
                        .option(bool("pillarGreenery", Config.PILLAR_GREENERY))
                        .option(integer("pillarGreeneryDepth", Config.PILLAR_GREENERY_DEPTH, 0, 384, 4))
                        .build())
                .build();
    }

    private static ConfigCategory debug() {
        return ConfigCategory.createBuilder()
                .name(Component.translatable(KEY + "debug"))
                .tooltip(Component.translatable(KEY + "debug.tooltip"))
                .option(Option.<Config.DebugView>createBuilder()
                        .name(Component.translatable(KEY + "debugView"))
                        .description(describe("debugView"))
                        .binding(Config.DEBUG_VIEW.getDefault(), Config.DEBUG_VIEW, Config.DEBUG_VIEW::set)
                        .controller(opt -> EnumControllerBuilder.create(opt).enumClass(Config.DebugView.class))
                        .build())
                .option(bool("debugHud", Config.DEBUG_HUD))
                .option(bool("logRenderCompatWarnings", Config.LOG_RENDER_COMPAT_WARNINGS))
                .build();
    }

    // --- option builders -------------------------------------------------------------------------

    private static Option<Boolean> bool(String key, ModConfigSpec.BooleanValue value) {
        return Option.<Boolean>createBuilder()
                .name(Component.translatable(KEY + key))
                .description(describe(key))
                .binding(value.getDefault(), value, value::set)
                .controller(TickBoxControllerBuilder::create)
                .build();
    }

    private static Option<String> text(String key, ModConfigSpec.ConfigValue<String> value) {
        return Option.<String>createBuilder()
                .name(Component.translatable(KEY + key))
                .description(describe(key))
                .binding(value.getDefault(), value, value::set)
                .controller(StringControllerBuilder::create)
                .build();
    }

    private static Option<Integer> integer(String key, ModConfigSpec.IntValue value, int min, int max, int step) {
        return Option.<Integer>createBuilder()
                .name(Component.translatable(KEY + key))
                .description(describe(key))
                .binding(value.getDefault(), value, value::set)
                .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(min, max).step(step))
                .build();
    }

    private static Option<Double> dbl(String key, ModConfigSpec.DoubleValue value,
                                      double min, double max, double step) {
        return Option.<Double>createBuilder()
                .name(Component.translatable(KEY + key))
                .description(describe(key))
                .binding(value.getDefault(), value, value::set)
                .controller(opt -> DoubleSliderControllerBuilder.create(opt).range(min, max).step(step))
                .build();
    }

    // Packed ARGB int <-> java.awt.Color, which is what YACL's picker binds to. allowAlpha(false) hides
    // the alpha slider for colours whose alpha the renderer ignores; the stored alpha is left untouched.
    private static Option<Color> color(String key, ModConfigSpec.IntValue value, boolean alpha) {
        Supplier<Color> get = () -> new Color(value.getAsInt(), true);
        Consumer<Color> set = c -> value.set(c.getRGB());
        return Option.<Color>createBuilder()
                .name(Component.translatable(KEY + key))
                .description(describe(key))
                .binding(new Color(value.getDefault(), true), get, set)
                .controller(opt -> ColorControllerBuilder.create(opt).allowAlpha(alpha))
                .build();
    }

    // ListOption is itself a group, so these are added with .group(...) rather than .option(...).
    // ConfigValue<List<? extends String>> is copied in and out: YACL hands back its own mutable list.
    // Long lists start folded: transforms alone runs to a dozen entries and more in a pack, and a
    // screen that opens on a column of text fields buries every option under it. Anything short enough
    // to read at a glance stays open.
    private static final int COLLAPSE_FROM = 5;

    private static ListOption<String> strings(String key, ModConfigSpec.ConfigValue<List<? extends String>> value) {
        return ListOption.<String>createBuilder()
                .collapsed(value.get().size() >= COLLAPSE_FROM)
                .name(Component.translatable(KEY + key))
                .description(describe(key))
                .binding(new ArrayList<>(value.getDefault()),
                        () -> new ArrayList<>(value.get()),
                        list -> value.set(new ArrayList<>(list)))
                .controller(StringControllerBuilder::create)
                .initial("")
                .build();
    }

    private static OptionDescription describe(String key) {
        return OptionDescription.of(Component.translatable(KEY + key + ".tooltip"));
    }
}
