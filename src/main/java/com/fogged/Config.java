package com.fogged;

import java.util.ArrayList;
import java.util.List;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;

// Mod config. Demonstrates how to use Neo's config APIs.
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // Vertical offset of the visible surface from the breathing boundary. Lowered 0.4 below the
    // boundary; the plane, fog, foam and breathing checks all use this so they move together and the
    // fog starts exactly at the visible surface.
    public static final double PLANE_SURFACE_OFFSET = -0.38;

    // The murk fog starts applying this many blocks above the plane (not exactly at it). Internal.
    public static final double FOG_START_RAISE = 0.5;

    // ---- Underwater-breathing boundary config ----

    public static final ModConfigSpec.ConfigValue<UnmodifiableConfig> PLANE_HEIGHT_SCHEDULE = BUILDER
            .comment("Breathing-boundary height over time, as a day -> height table. The boundary is the Y",
                    "below which the player breathes as if underwater. Height is linearly interpolated",
                    "between listed days; before the first day it holds the first height, after the last",
                    "day it holds the last. Day is the world day count (dayTime / 24000).",
                    "TOML table syntax, e.g.   planeHeightSchedule = { \"0\" = -30, \"10\" = 40, \"80\" = 100 }")
            .define("planeHeightSchedule", Config::defaultSchedule, Config::isValidSchedule);

    public static final ModConfigSpec.ConfigValue<List<? extends Double>> OVERDAY_OFFSET = BUILDER
            .comment("Daily height offset added on top of the scheduled height, as [minNoon, maxMidnight].",
                    "It eases from the noon minimum to the midnight maximum and back over the day.")
            .defineList("overdayOffset", List.of(0.0, 4.0), () -> 0.0, o -> o instanceof Number);

    public static final ModConfigSpec.IntValue AIR_LOSS_PER_TICK = BUILDER
            .comment("Air lost per tick (out of 300) while below the breathing boundary. Higher = drown faster.")
            .defineInRange("airLossPerTick", 1, 1, 300);

    public static final ModConfigSpec.IntValue FOG_DISTANCE = BUILDER
            .comment("Render distance (in blocks) of the thick fog applied while the camera is below the",
                    "breathing boundary. Lower = denser fog / shorter view, like being underwater.")
            .defineInRange("fogDistance", 24, 4, 256);

    public static final ModConfigSpec.BooleanValue FLIP_FOG = BUILDER
            .comment("Flip the murk fog to the other side of the plane. Default (false) fogs BELOW the",
                    "boundary (underwater-style); true fogs ABOVE it instead. Affects the fog only, not",
                    "the breathing boundary.")
            .define("flipFog", false);

    public static final ModConfigSpec.BooleanValue RENDER_PLANE = BUILDER
            .comment("Whether to render the semi-transparent separation plane at the breathing boundary.")
            .define("renderPlane", true);

    public static final ModConfigSpec.ConfigValue<String> PLANE_COLOR = BUILDER
            .comment("Separation plane colour as hex RGBA (RRGGBBAA). Alpha 0 = invisible, FF = opaque.",
                    "Keep alpha high so the plane reads as the same dense fog you see beneath it.")
            .define("planeColor", "406440FF");

    public static final ModConfigSpec.ConfigValue<String> FOAM_COLOR = BUILDER
            .comment("Foam base colour as hex RGBA (RRGGBBAA). Alpha scales how strongly the foam shows.")
            .define("foamColor", "70947aFF");

    public static final ModConfigSpec.DoubleValue FOAM_WIDTH = BUILDER
            .comment("How far (in blocks) the white foam reaches from where the plane meets blocks and",
                    "entities. 0 disables the foam; larger = wider foam band around every edge.")
            .defineInRange("foamWidth", 2.25, 0.0, 8.0);

    public static final ModConfigSpec.BooleanValue SABLE_FOAM = BUILDER
            .comment("If the Sable physics mod is installed, also generate foam around its sub-levels",
                    "(ships / contraptions) where they cross the boundary. No effect without Sable.")
            .define("sableFoam", true);

    // ---- Cold-vapour ("liquid nitrogen") layer config ----

    public static final ModConfigSpec.BooleanValue RENDER_VAPOR = BUILDER
            .comment("Render the cold-vapour layer on top of the plane: drifting horizontal mist sheets",
                    "(overall variation, densest at grazing angles far away) plus animated vertical splash",
                    "wisps near the camera, for a liquid-nitrogen look. No effect if renderPlane is off.")
            .define("renderVapor", true);

    public static final ModConfigSpec.ConfigValue<List<? extends Double>> VAPOR_COLOR_OFFSET = BUILDER
            .comment("Cold-vapour colour is derived from planeColor plus this per-channel offset [dR, dG, dB]",
                    "(each -1..1, added then clamped), so the vapour tracks the plane but reads distinct.",
                    "The default lightens it toward an icy white-blue.")
            .defineList("vaporColorOffset", List.of(0.45, 0.50, 0.55),
                    () -> 0.0, o -> o instanceof Number n && n.doubleValue() >= -1.0 && n.doubleValue() <= 1.0);

    public static final ModConfigSpec.DoubleValue VAPOR_STRENGTH = BUILDER
            .comment("Overall vapour strength (alpha). Lower for a fainter mist, 0 to hide it entirely.")
            .defineInRange("vaporStrength", 0.85, 0.0, 1.0);

    public static final ModConfigSpec.IntValue VAPOR_SHEETS = BUILDER
            .comment("Number of stacked mist sheets over the plane. Each is a grid that rises and falls",
                    "(see vaporUndulation) so the plane never looks dead flat. More sheets = thicker, more",
                    "layered mist (and a touch more cost). 0 disables the sheets.")
            .defineInRange("vaporSheets", 3, 0, 8);

    public static final ModConfigSpec.DoubleValue VAPOR_UNDULATION = BUILDER
            .comment("Maximum height (in blocks) the mist sheets rise off the plane, giving the flat plane",
                    "rolling rises and falls. 0 = flat sheets.")
            .defineInRange("vaporUndulation", 1.5, 0.0, 16.0);

    public static final ModConfigSpec.BooleanValue FOAM_DEBUG = BUILDER
            .comment("Debug: render the plane as raw foam data instead of the normal look.",
                    "Red = depth-proximity edge factor, Green = sampled scene depth. If Green is a flat",
                    "single colour the depth buffer isn't being read; if Red is blank there's no edge.")
            .define("foamDebug", false);

    static final ModConfigSpec SPEC = BUILDER.build();

    // --- dynamic breathing-boundary height ---

    private static final int TICKS_PER_DAY = 24000;
    private static final int NOON_TICK = 6000; // dayTime 0 = sunrise (06:00), so noon is 6000 ticks in

    // Boundary snaps to this vertical step (blocks). Quantizing the continuous height keeps the plane
    // resting at a fixed Y between steps so it does not z-fight as the schedule/offset drift sub-block.
    private static final double HEIGHT_STEP = 0.25;

    // Boundary Y at the world's current time: scheduled height for the day plus the time-of-day offset,
    // snapped to HEIGHT_STEP so the boundary moves in discrete jumps instead of continuous drift.
    public static double breathHeight(Level level) {
        long dayTime = level.getDayTime();
        double day = (double) dayTime / TICKS_PER_DAY;
        int timeOfDay = (int) Math.floorMod(dayTime, TICKS_PER_DAY);
        double raw = scheduledHeight(day) + overdayOffset(timeOfDay);
        return Math.round(raw / HEIGHT_STEP) * HEIGHT_STEP;
    }

    // Linear interpolation of the day -> height schedule, clamped flat outside the listed range.
    private static double scheduledHeight(double day) {
        ensureSchedule();
        double[] days = schedDays;
        double[] heights = schedHeights;
        if (days.length == 0) {
            return 0.0;
        }
        if (day <= days[0]) {
            return heights[0];
        }
        int last = days.length - 1;
        if (day >= days[last]) {
            return heights[last];
        }
        for (int i = 0; i < last; i++) {
            if (day <= days[i + 1]) {
                double t = (day - days[i]) / (days[i + 1] - days[i]);
                return heights[i] + t * (heights[i + 1] - heights[i]);
            }
        }
        return heights[last];
    }

    // Offset eased from the noon minimum to the midnight maximum and back over one day.
    private static double overdayOffset(int timeOfDay) {
        List<? extends Double> o = OVERDAY_OFFSET.get();
        double min = o.size() > 0 ? o.get(0) : 0.0;
        double max = o.size() > 1 ? o.get(1) : min;
        // f = 0 at noon (6000), 1 at midnight (18000); smooth cosine in between.
        double f = (1.0 - Math.cos(2.0 * Math.PI * (timeOfDay - NOON_TICK) / TICKS_PER_DAY)) / 2.0;
        return min + (max - min) * f;
    }

    // Default day -> height table, written as an inline TOML table on first run.
    private static UnmodifiableConfig defaultSchedule() {
        com.electronwill.nightconfig.core.Config c = com.electronwill.nightconfig.core.Config.inMemory();
        c.set("0", -30);
        c.set("10", 40);
        c.set("80", 100);
        return c;
    }

    private static boolean isValidSchedule(Object o) {
        if (!(o instanceof UnmodifiableConfig cfg)) {
            return false;
        }
        for (UnmodifiableConfig.Entry e : cfg.entrySet()) {
            if (parseEntry(e.getKey(), e.getValue()) == null) {
                return false;
            }
        }
        return true;
    }

    // Parsed schedule, sorted ascending by day. Re-parsed when the underlying config table changes.
    private static UnmodifiableConfig cachedRaw;
    private static double[] schedDays = new double[0];
    private static double[] schedHeights = new double[0];

    private static void ensureSchedule() {
        UnmodifiableConfig raw = PLANE_HEIGHT_SCHEDULE.get();
        if (raw == cachedRaw) {
            return;
        }
        cachedRaw = raw;
        List<double[]> entries = new ArrayList<>();
        for (UnmodifiableConfig.Entry e : raw.entrySet()) {
            double[] parsed = parseEntry(e.getKey(), e.getValue());
            if (parsed != null) {
                entries.add(parsed);
            }
        }
        entries.sort((a, b) -> Double.compare(a[0], b[0]));
        schedDays = new double[entries.size()];
        schedHeights = new double[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            schedDays[i] = entries.get(i)[0];
            schedHeights[i] = entries.get(i)[1];
        }
    }

    // Parses one "day -> height" table entry into {day, height}, or null if malformed. Day comes from
    // the key string, height may be stored as a number or a quoted string.
    private static double[] parseEntry(String key, Object value) {
        try {
            double day = Double.parseDouble(key.trim());
            double height = value instanceof Number n
                    ? n.doubleValue()
                    : Double.parseDouble(String.valueOf(value).trim());
            return new double[] { day, height };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // --- hex RGBA helpers ---

    public static float[] planeColor() {
        return parseRgba(PLANE_COLOR.get());
    }

    public static float[] foamColor() {
        return parseRgba(FOAM_COLOR.get());
    }

    // Vapour colour = plane colour + per-channel offset (clamped), with the configured strength as alpha.
    public static float[] vaporColor() {
        float[] plane = planeColor();
        List<? extends Double> off = VAPOR_COLOR_OFFSET.get();
        float dr = off.size() > 0 ? off.get(0).floatValue() : 0.0F;
        float dg = off.size() > 1 ? off.get(1).floatValue() : 0.0F;
        float db = off.size() > 2 ? off.get(2).floatValue() : 0.0F;
        return new float[] {
                clamp01(plane[0] + dr),
                clamp01(plane[1] + dg),
                clamp01(plane[2] + db),
                VAPOR_STRENGTH.get().floatValue() };
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
    }

    // Parses "RRGGBB" or "RRGGBBAA" (with an optional leading '#') into float[]{r, g, b, a} in 0..1.
    // Falls back to opaque white on malformed input so a typo never crashes rendering.
    private static float[] parseRgba(String s) {
        s = s.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        try {
            long v = Long.parseLong(s, 16);
            if (s.length() <= 6) { // RGB only -> fully opaque
                int rgb = (int) v;
                return new float[] {
                        ((rgb >> 16) & 0xFF) / 255.0F,
                        ((rgb >> 8) & 0xFF) / 255.0F,
                        (rgb & 0xFF) / 255.0F,
                        1.0F };
            }
            return new float[] {
                    ((v >> 24) & 0xFF) / 255.0F,
                    ((v >> 16) & 0xFF) / 255.0F,
                    ((v >> 8) & 0xFF) / 255.0F,
                    (v & 0xFF) / 255.0F };
        } catch (NumberFormatException e) {
            return new float[] { 1.0F, 1.0F, 1.0F, 1.0F };
        }
    }
}
