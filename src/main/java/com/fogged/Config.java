package com.fogged;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.electronwill.nightconfig.core.UnmodifiableConfig;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
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
    public static final double FOG_START_RAISE = 0.75;

    // Config values below are grouped into TOML categories with BUILDER.push/pop. Those calls sit in
    // static blocks so they run in declaration order, interleaved with the field initializers -- Java
    // runs static initializer blocks and static field initializers top-to-bottom (JLS 12.4.2).

    // ==== [items] : items / devices ====
    static { BUILDER.push("items"); }

    public static final ModConfigSpec.BooleanValue ITEMS_ENABLED = BUILDER
            .comment("Master switch for the mod's items/devices (currently the fog detector). Off = the",
                    "detector emits no redstone signal and is hidden from the creative tab.")
            .define("itemsEnabled", true);

    static { BUILDER.pop(); }

    // ==== [boundary] : underwater-breathing boundary ====
    static { BUILDER.push("boundary"); }

    public static final ModConfigSpec.ConfigValue<UnmodifiableConfig> PLANE_HEIGHT_SCHEDULE = BUILDER
            .comment("Breathing-boundary height over time, as a day -> height table. The boundary is the Y",
                    "below which the player breathes as if underwater. Height is linearly interpolated",
                    "between listed days; before the first day it holds the first height, after the last",
                    "day it holds the last. Day is the world day count (dayTime / 24000).",
                    "TOML table syntax, e.g.   planeHeightSchedule = { \"0\" = -30, \"10\" = 40, \"80\" = 100 }")
            .define("planeHeightSchedule", Config::defaultSchedule, Config::isValidSchedule);

    public static final ModConfigSpec.BooleanValue PLANE_HEIGHT_CYCLE = BUILDER
            .comment("On: loop the schedule back and forth (with default days 0/10/80: 0->10->80->10->0->...).",
                    "Off (default): hold the last height after the last day.")
            .define("planeHeightCycle", false);

    public static final ModConfigSpec.ConfigValue<List<? extends Double>> OVERDAY_OFFSET = BUILDER
            .comment("Daily height offset added on top of the scheduled height, as [minNoon, maxMidnight].",
                    "It eases from the noon minimum to the midnight maximum and back over the day.")
            .defineList("overdayOffset", List.of(0.0, 4.0), () -> 0.0, o -> o instanceof Number);

    public static final ModConfigSpec.IntValue AIR_LOSS_PER_TICK = BUILDER
            .comment("Air lost per tick (out of 300) while below the breathing boundary. Higher = drown faster.")
            .defineInRange("airLossPerTick", 1, 1, 300);

    public static final ModConfigSpec.BooleanValue DEPTH_SCALING = BUILDER
            .comment("Make the murk bite harder the deeper you go: air drains faster and nozzle-filter",
                    "breathing spheres shrink, both by depthScalingStep. Off = the same everywhere below",
                    "the boundary.")
            .define("depthScaling", true);

    public static final ModConfigSpec.ConfigValue<List<? extends Double>> DEPTH_SCALING_STEP = BUILDER
            .comment("Depth scaling as [depth, percent]: every `depth` blocks below the breathing boundary,",
                    "air loss goes up by `percent` and the nozzle-filter sphere radius goes down by it.",
                    "The steps compound (default [10, 5]: -20 blocks = air x1.05^2, radius x0.95^2), and",
                    "partial steps count, so the change is gradual rather than jumping at each step.")
            .defineList("depthScalingStep", List.of(10.0, 5.0), () -> 0.0,
                    o -> o instanceof Number n && n.doubleValue() >= 0.0);

    public static final ModConfigSpec.IntValue FOG_DISTANCE = BUILDER
            .comment("Render distance (in blocks) of the thick fog applied while the camera is below the",
                    "breathing boundary. Lower = denser fog / shorter view, like being underwater.")
            .defineInRange("fogDistance", 24, 4, 256);

    public static final ModConfigSpec.BooleanValue FLIP_FOG = BUILDER
            .comment("Flip the murk fog to the other side of the plane. Default (false) fogs BELOW the",
                    "boundary (underwater-style); true fogs ABOVE it instead. Affects the fog only, not",
                    "the breathing boundary.")
            .define("flipFog", false);

    public static final ModConfigSpec.BooleanValue SUBMERGE_WORLD = BUILDER
            .comment("The murk drowns the world below the boundary like being underwater: snuffs fire and",
                    "soul fire, freezes lava to stone, drowns torches, and wilts plants / crops / leaves.",
                    "Off leaves the world untouched under the fog.")
            .define("submergeWorld", true);

    public static final ModConfigSpec.IntValue SUBMERGE_SKIP = BUILDER
            .comment("Dead zone: the topmost blocks directly under the fog plane that the scour leaves alone.",
                    "The shallow layer right beneath the plane stays untouched; the scour acts on everything",
                    "from this offset down to the bottom of the world.")
            .defineInRange("submergeSkip", 5, 0, 64);

    static { BUILDER.pop(); }

    // ==== [plane] : separation plane (and its cold-vapour layer) ====
    static { BUILDER.push("plane"); }

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

    public static final ModConfigSpec.BooleanValue FOAM_DEBUG = BUILDER
            .comment("Debug: render the plane as raw foam data instead of the normal look.",
                    "Red = depth-proximity edge factor, Green = sampled scene depth. If Green is a flat",
                    "single colour the depth buffer isn't being read; if Red is blank there's no edge.")
            .define("foamDebug", false);

    // ---- [plane.vapor] : cold-vapour ("liquid nitrogen") layer ----
    static { BUILDER.push("vapor"); }

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
            .defineInRange("vaporStrength", 0.95, 0.0, 1.0);

    public static final ModConfigSpec.IntValue VAPOR_SHEETS = BUILDER
            .comment("Number of stacked mist sheets over the plane. Each is a grid that rises and falls",
                    "(see vaporUndulation) so the plane never looks dead flat. More sheets = thicker, more",
                    "layered mist (and a touch more cost). 0 disables the sheets.")
            .defineInRange("vaporSheets", 5, 0, 8);

    public static final ModConfigSpec.DoubleValue VAPOR_UNDULATION = BUILDER
            .comment("Maximum height (in blocks) the mist sheets rise off the plane, giving the flat plane",
                    "rolling rises and falls. 0 = flat sheets.")
            .defineInRange("vaporUndulation", 1.5, 0.0, 16.0);

    static { BUILDER.pop(); }   // [plane.vapor]
    static { BUILDER.pop(); }   // [plane]

    // ==== [mobs] : murk suffocation of non-allowed mobs ====
    static { BUILDER.push("mobs"); }

    public static final ModConfigSpec.BooleanValue MOBS_ENABLED = BUILDER
            .comment("Master switch for the mod's mob behaviour: the murk suffocation of non-allowed mobs.",
                    "Off = non-allowed mobs no longer suffocate under the fog.")
            .define("mobsEnabled", true);

    public static final ModConfigSpec.BooleanValue MOB_SUFFOCATION_ENABLED = BUILDER
            .comment("Whether the murk suffocates non-allowed mobs (blocks their spawns and damages them",
                    "under the fog). Off lets any mob live under the fog. Ignored when mobsEnabled is off.")
            .define("mobSuffocationEnabled", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_MOBS = BUILDER
            .comment("Entity-type IDs allowed to live under the fog (on the murk side of the boundary).",
                    "Anything NOT listed cannot spawn there and starts taking damage after",
                    "mobSuffocateDelaySeconds submerged. IDs may omit the 'minecraft:' namespace.",
                    "Example: allowedMobs = [\"minecraft:cod\", \"axolotl\", \"guardian\"]")
            .defineListAllowEmpty("allowedMobs", Config::defaultAllowedMobs, () -> "minecraft:cod",
                    o -> o instanceof String s && ResourceLocation.tryParse(withNamespace(s)) != null);

    public static final ModConfigSpec.IntValue MOB_SUFFOCATE_DELAY = BUILDER
            .comment("Seconds a non-allowed mob can stay under the fog before it starts taking damage.")
            .defineInRange("mobSuffocateDelaySeconds", 5, 0, 600);

    public static final ModConfigSpec.DoubleValue MOB_SUFFOCATE_DAMAGE = BUILDER
            .comment("Damage dealt to a non-allowed mob each second once it has been under the fog past",
                    "the delay. 2.0 = one heart per second.")
            .defineInRange("mobSuffocateDamage", 2.0, 0.0, 1000.0);

    static { BUILDER.pop(); }   // [mobs]

    static final ModConfigSpec SPEC = BUILDER.build();

    // --- dynamic breathing-boundary height ---

    private static final int TICKS_PER_DAY = 24000;
    private static final int NOON_TICK = 6000; // dayTime 0 = sunrise (06:00), so noon is 6000 ticks in

    // Boundary snaps to this vertical step (blocks). Quantizing the continuous height keeps the plane
    // resting at a fixed Y between steps so it does not z-fight as the schedule/offset drift sub-block.
    private static final double HEIGHT_STEP = 0.25;

    // One-entry memo: breathHeight depends only on the level's day-time, but it is called for every
    // entity every tick (MobSuppressor) and for many blocks in the under-fog scour sweep. Caching it
    // per (level, dayTime) collapses all of a tick's calls to a single computation.
    private static Level cachedHeightLevel;
    private static long cachedHeightDayTime = Long.MIN_VALUE;
    private static double cachedHeight;

    // Boundary Y at the world's current time: scheduled height for the day plus the time-of-day offset,
    // snapped to HEIGHT_STEP so the boundary moves in discrete jumps instead of continuous drift.
    public static double breathHeight(Level level) {
        long dayTime = level.getDayTime();
        if (level == cachedHeightLevel && dayTime == cachedHeightDayTime) {
            return cachedHeight;
        }
        double day = (double) dayTime / TICKS_PER_DAY;
        int timeOfDay = (int) Math.floorMod(dayTime, TICKS_PER_DAY);
        double raw = scheduledHeight(day) + overdayOffset(timeOfDay);
        double result = Math.round(raw / HEIGHT_STEP) * HEIGHT_STEP;
        cachedHeightLevel = level;
        cachedHeightDayTime = dayTime;
        cachedHeight = result;
        return result;
    }

    // True when a camera at world height y is on the fogged side of the boundary (the thick murk side).
    // Normally that is below the boundary; flipFog moves it to the side above. Shared by the fog
    // override, the separation plane and the weather suppression so they all agree on the murk side.
    public static boolean fogged(Level level, double y) {
        double fogLine = breathHeight(level) + PLANE_SURFACE_OFFSET + FOG_START_RAISE;
        return (y < fogLine) != FLIP_FOG.getAsBoolean();
    }

    // --- depth scaling ---

    // How many depthScalingStep steps deep world height y sits below the boundary. 0 at or above the
    // boundary; fractional, so the scaling below eases in instead of jumping at every step.
    private static double depthSteps(Level level, double y) {
        if (!DEPTH_SCALING.get()) {
            return 0.0;
        }
        List<? extends Double> s = DEPTH_SCALING_STEP.get();
        double step = s.size() > 0 ? s.get(0) : 0.0;
        if (step <= 0.0) {
            return 0.0;
        }
        double below = breathHeight(level) + PLANE_SURFACE_OFFSET - y;
        return below <= 0.0 ? 0.0 : below / step;
    }

    private static double depthPercent() {
        List<? extends Double> s = DEPTH_SCALING_STEP.get();
        return s.size() > 1 ? s.get(1) : 0.0;
    }

    // Air-loss multiplier at world height y: compounds +percent per step of depth.
    public static double depthAirFactor(Level level, double y) {
        double steps = depthSteps(level, y);
        return steps <= 0.0 ? 1.0 : Math.pow(1.0 + depthPercent() / 100.0, steps);
    }

    // Breathing-sphere radius multiplier at world height y: compounds -percent per step of depth, so a
    // filter far under the boundary reaches deep enough only for a stub of a sphere (or none at all).
    public static double depthRadiusFactor(Level level, double y) {
        double steps = depthSteps(level, y);
        if (steps <= 0.0) {
            return 1.0;
        }
        return Math.pow(Math.max(0.0, 1.0 - depthPercent() / 100.0), steps);
    }

    // Linear interpolation of the day -> height schedule, clamped flat outside the listed range.
    private static double scheduledHeight(double day) {
        ensureSchedule();
        double[] days = schedDays;
        double[] heights = schedHeights;
        if (days.length == 0) {
            return 0.0;
        }
        // Cycle mode: fold the day count into a triangle wave over [first, last] so the height
        // ping-pongs instead of holding flat. The folded day stays in range for the interpolation below.
        if (PLANE_HEIGHT_CYCLE.get() && days.length >= 2) {
            double lo = days[0];
            double span = days[days.length - 1] - lo;
            if (span > 0.0) {
                double period = 2.0 * span;
                double m = (day - lo) - period * Math.floor((day - lo) / period); // mod, in [0, period)
                day = lo + (m <= span ? m : period - m);                          // reflect second half
            }
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

    // --- allowed-mob set ---

    // Default allow-list: only the warden belongs in the murk (the mod's own mobs are auto-allowed by
    // namespace in mobAllowedUnderFog, so they need no listing here).
    private static List<String> defaultAllowedMobs() {
        return new ArrayList<>(List.of("minecraft:warden"));
    }

    // A bare "cod" is treated as "minecraft:cod" so the config stays terse.
    private static String withNamespace(String s) {
        s = s.trim();
        return s.indexOf(':') >= 0 ? s : "minecraft:" + s;
    }

    private static List<? extends String> cachedMobsList;
    private static Set<ResourceLocation> allowedMobIds = new HashSet<>();

    private static void ensureAllowedMobs() {
        List<? extends String> raw = ALLOWED_MOBS.get();
        if (raw == cachedMobsList) {
            return;
        }
        cachedMobsList = raw;
        Set<ResourceLocation> ids = new HashSet<>();
        for (String s : raw) {
            ResourceLocation id = ResourceLocation.tryParse(withNamespace(s));
            if (id != null) {
                ids.add(id);
            }
        }
        allowedMobIds = ids;
    }

    // True if the given entity type may live under the fog. The mod's own mobs (fogged: namespace) are
    // always allowed -- they belong in the murk and shouldn't need listing (or a config edit to survive).
    // Everything else must be on the allowedMobs list; unregistered types are never allowed.
    public static boolean mobAllowedUnderFog(EntityType<?> type) {
        ensureAllowedMobs();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        if (id == null) {
            return false;
        }
        return id.getNamespace().equals(Fogged.MODID) || allowedMobIds.contains(id);
    }

    // Whether the murk suffocates non-allowed mobs: needs both the mobs master switch and its own toggle.
    public static boolean mobSuffocationEnabled() {
        return MOBS_ENABLED.get() && MOB_SUFFOCATION_ENABLED.get();
    }

    // --- hex RGBA helpers ---

    // These are read every frame by the renderers, so the parsed result is cached and only re-parsed
    // when the underlying config string/value actually changes -- avoids per-frame hex parsing and
    // float[] garbage. The returned arrays are shared and treated as read-only by every caller.
    private static String planeColorRaw;
    private static float[] planeColorCache;

    public static float[] planeColor() {
        String s = PLANE_COLOR.get();
        if (planeColorCache == null || !s.equals(planeColorRaw)) {
            planeColorRaw = s;
            planeColorCache = parseRgba(s);
        }
        return planeColorCache;
    }

    private static String foamColorRaw;
    private static float[] foamColorCache;

    public static float[] foamColor() {
        String s = FOAM_COLOR.get();
        if (foamColorCache == null || !s.equals(foamColorRaw)) {
            foamColorRaw = s;
            foamColorCache = parseRgba(s);
        }
        return foamColorCache;
    }

    // Vapour colour = plane colour + per-channel offset (clamped), with the configured strength as alpha.
    private static float[] vaporColorCache;
    private static List<? extends Double> vaporOffsetRaw;
    private static double vaporStrengthRaw = Double.NaN;

    public static float[] vaporColor() {
        float[] plane = planeColor();
        List<? extends Double> off = VAPOR_COLOR_OFFSET.get();
        double strength = VAPOR_STRENGTH.get();
        if (vaporColorCache == null || plane != planeColorForVapor || off != vaporOffsetRaw
                || strength != vaporStrengthRaw) {
            planeColorForVapor = plane;
            vaporOffsetRaw = off;
            vaporStrengthRaw = strength;
            float dr = off.size() > 0 ? off.get(0).floatValue() : 0.0F;
            float dg = off.size() > 1 ? off.get(1).floatValue() : 0.0F;
            float db = off.size() > 2 ? off.get(2).floatValue() : 0.0F;
            vaporColorCache = new float[] {
                    clamp01(plane[0] + dr),
                    clamp01(plane[1] + dg),
                    clamp01(plane[2] + db),
                    (float) strength };
        }
        return vaporColorCache;
    }

    // The plane-colour array vaporColor was last derived from; a new array means planeColor re-parsed.
    private static float[] planeColorForVapor;

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
