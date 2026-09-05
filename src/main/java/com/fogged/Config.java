package com.fogged;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;

// Mod config, split across two specs. Gameplay (the boundary and what suffocates under it) is COMMON,
// so a server decides it; everything the client draws -- the plane, its vapour and the render debug
// views -- is CLIENT, so it stays a local preference and a server can never dictate someone's visuals.
//
// Every option is a plain scalar, a boolean, an enum or a list of strings. That is deliberate: config
// GUIs (NeoForge's own screen, Configured, and the YACL screen in YaclCompatibility) can edit those
// with a real widget, while the nested TOML tables and mixed numeric lists this used to store could
// only ever be a text box. Colours are stored as ARGB ints for the same reason -- that is what a
// colour picker binds to.
public class Config {

    private static final ModConfigSpec.Builder COMMON = new ModConfigSpec.Builder();
    private static final ModConfigSpec.Builder CLIENT = new ModConfigSpec.Builder();

    // Vertical offset of the visible surface from the breathing boundary. Lowered 0.4 below the
    // boundary; the plane, fog, foam and breathing checks all use this so they move together and the
    // fog starts exactly at the visible surface.
    public static final double PLANE_SURFACE_OFFSET = -0.38;

    // The murk fog starts applying this many blocks above the plane (not exactly at it). Internal.
    public static final double FOG_START_RAISE = 0.75;

    // ==== Constants mirrored in GLSL shaders (GLSL can't import Java constants -- keep these in sync
    // by hand whenever any one of them changes). Each entry's Java declaration links back to this list.
    //   FogPlaneRenderer.NOISE_ANCHOR (4096.0)
    //     == fog_plane.fsh  NOISE_PERIOD_BLOCKS
    //     == fog_vapor.fsh  NOISE_PERIOD_BLOCKS
    //   FogPlaneRenderer.MAX_ENTITY_HOLES (32)
    //     == fog_plane.fsh  entity-hole loop bound (`for (int i = 0; i < 32; i++)`)
    //     == fog_plane.json / fog_plane.fsh  EntityHoles[128] (== MAX_ENTITY_HOLES * 4)
    //   Config.DebugView ordinals
    //     == fog_plane.fsh  DebugView uniform (0 = off, then one branch per constant, in order)
    // WaterlineMap's cellsPerBlock resolution is NOT in this list: it's threaded to the shaders as the
    // FoamPixelsPerBlock/PixelsPerBlock uniforms every frame (see FogPlaneRenderer/FogVapor) precisely
    // so it can change at runtime (waterlineCellsPerBlock) without needing a matching shader edit.
    // ====

    /** What the plane shader draws instead of the plane, for diagnosing the render path. */
    public enum DebugView {
        /** Normal rendering. */
        OFF,
        /** Red = foam edge factor, blue = surface-spot shading. */
        FOAM,
        /** The scene-depth snapshot the soft edge samples: grey = depth, blue = nothing behind. */
        SCENE_DEPTH,
        /** The plane's own opacity: green where it is solid (writes depth), red where it is softened. */
        PLANE_OPACITY,
        /** The per-entity dissolve discs (green), as gated by which side the camera is on. */
        DISSOLVE_HOLES,
        /** The raw waterline map: red = distance to solid/entity, green = distance to plant. */
        WATERLINE_MAP
    }

    // Config values below are grouped into TOML categories with push/pop. Those calls sit in static
    // blocks so they run in declaration order, interleaved with the field initializers -- Java runs
    // static initializer blocks and static field initializers top-to-bottom (JLS 12.4.2). The two
    // builders are independent, so COMMON and CLIENT sections can interleave freely.

    // ==== common [boundary] : underwater-breathing boundary ====
    static { COMMON.push("boundary"); }

    public static final ModConfigSpec.ConfigValue<List<? extends String>> PLANE_HEIGHT_SCHEDULE = COMMON
            .comment("Breathing-boundary height over time, one \"day=height\" entry per line. The boundary",
                    "is the Y below which the player breathes as if underwater. Height is linearly",
                    "interpolated between listed days; before the first day it holds the first height,",
                    "after the last day it holds the last. Day is the world day count (dayTime / 24000).",
                    "Example: planeHeightSchedule = [\"0=-30\", \"10=40\", \"80=100\"]")
            .defineListAllowEmpty("planeHeightSchedule", Config::defaultSchedule, () -> "0=0",
                    o -> o instanceof String s && parseScheduleEntry(s) != null);

    public static final ModConfigSpec.BooleanValue PLANE_HEIGHT_CYCLE = COMMON
            .comment("On: loop the schedule back and forth (with default days 0/10/80: 0->10->80->10->0->...).",
                    "Off (default): hold the last height after the last day.")
            .define("planeHeightCycle", false);

    public static final ModConfigSpec.DoubleValue OVERDAY_OFFSET_NOON = COMMON
            .comment("Height offset added on top of the scheduled height at noon -- the low point of the",
                    "daily rise and fall.")
            .defineInRange("overdayOffsetNoon", 0.0, -256.0, 256.0);

    public static final ModConfigSpec.DoubleValue OVERDAY_OFFSET_MIDNIGHT = COMMON
            .comment("Height offset added on top of the scheduled height at midnight -- the high point.",
                    "The boundary eases between the two over the day.")
            .defineInRange("overdayOffsetMidnight", 4.0, -256.0, 256.0);

    public static final ModConfigSpec.IntValue FOG_DISTANCE = COMMON
            .comment("Render distance (in blocks) of the thick fog applied while the camera is below the",
                    "breathing boundary. Lower = denser fog / shorter view, like being underwater.")
            .defineInRange("fogDistance", 24, 4, 256);

    public static final ModConfigSpec.BooleanValue FLIP_FOG = COMMON
            .comment("Flip the murk fog to the other side of the plane. Default (false) fogs BELOW the",
                    "boundary (underwater-style); true fogs ABOVE it instead. Affects the fog only, not",
                    "the breathing boundary.")
            .define("flipFog", false);

    public static final ModConfigSpec.BooleanValue SUBMERGE_WORLD = COMMON
            .comment("The murk drowns the world below the boundary like being underwater: snuffs fire and",
                    "soul fire, freezes lava to stone, drowns torches, and wilts plants / crops / leaves.",
                    "Off leaves the world untouched under the fog.")
            .define("submergeWorld", true);

    public static final ModConfigSpec.IntValue SUBMERGE_SKIP = COMMON
            .comment("Dead zone: the topmost blocks directly under the fog plane that the scour leaves alone.",
                    "The shallow layer right beneath the plane stays untouched; the scour acts on everything",
                    "from this offset down to the bottom of the world.")
            .defineInRange("submergeSkip", 5, 0, 64);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SNUFFED_DEVICES = COMMON
            .comment("Block ids of fire-burning devices the murk snuffs out along with the loose fires: each",
                    "is unlit, its burn timer zeroed and any fuel inside it ejected, so it cannot keep",
                    "running under the fog. '*' matches any run of characters, and the 'minecraft:'",
                    "namespace may be omitted. Empty list = leave devices burning. Needs submergeWorld.",
                    "Example: snuffedDevices = [\"furnace\", \"create:lit_blaze_burner\"]")
            .defineListAllowEmpty("snuffedDevices", Config::defaultSnuffedDevices, () -> "minecraft:furnace",
                    o -> o instanceof String s && !s.isBlank());

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOURED_BLOCKS = COMMON
            .comment("Extra blocks the murk takes out under the boundary, on top of the built-in scour",
                    "(fires, lava, torches, devices, grass, farmland, leaves, plants). Each entry is a",
                    "block id or, with a leading '#', a block tag. '*' matches any run of characters in an",
                    "id, and the 'minecraft:' namespace may be omitted. They are broken without drops.",
                    "Needs submergeWorld.",
                    "Example: scouredBlocks = [\"cobweb\", \"#minecraft:banners\", \"create:*_casing\"]")
            .defineListAllowEmpty("scouredBlocks", ArrayList::new, () -> "minecraft:cobweb",
                    o -> o instanceof String s && !s.isBlank());

    public static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCK_TRANSFORMS = COMMON
            .comment("Blocks the murk turns into something else under the boundary, one \"from=to\" entry",
                    "per line. 'from' is a block id, an id with '*' wildcards, or a '#' block tag; 'to' is",
                    "a single block id, placed in its default state. Applied before the built-in scour, so",
                    "an entry here overrides what the murk would otherwise do to that block. A transform",
                    "whose result matches another rule is itself scoured on the next pass, so do not point",
                    "one at a block another rule takes. Empty list = built-in behaviour only.",
                    "Needs submergeWorld.",
                    "Example: blockTransforms = [\"minecraft:coal_ore=minecraft:stone\", \"#c:ores=stone\"]")
            .defineListAllowEmpty("blockTransforms", Config::defaultBlockTransforms,
                    () -> "minecraft:coal_ore=minecraft:stone",
                    o -> o instanceof String s && parseTransformEntry(s) != null);

    static { COMMON.pop(); }   // [boundary]

    // ==== common [suffocation] : what the murk does to the things breathing in it ====
    static { COMMON.push("suffocation"); }

    public static final ModConfigSpec.BooleanValue PLAYER_SUFFOCATION = COMMON
            .comment("Whether players drown under the breathing boundary: their air bar drains at",
                    "airLossPerTick and runs out into drowning damage. Off lets them breathe under the",
                    "fog freely, leaving only the fog itself. Mobs are unaffected either way.")
            .define("playerSuffocation", true);

    public static final ModConfigSpec.IntValue AIR_LOSS_PER_TICK = COMMON
            .comment("Air a player loses per tick (out of 300) while below the breathing boundary.",
                    "Higher = drown faster.")
            .defineInRange("airLossPerTick", 1, 1, 300);

    public static final ModConfigSpec.BooleanValue DEPTH_SCALING = COMMON
            .comment("Make the murk bite harder the deeper you go: air drains faster and nozzle-filter",
                    "breathing spheres shrink, both by depthScalingPercent. Off = the same everywhere",
                    "below the boundary.")
            .define("depthScaling", true);

    public static final ModConfigSpec.DoubleValue DEPTH_SCALING_BLOCKS = COMMON
            .comment("How many blocks below the boundary make up one depth step. 0 disables the scaling.")
            .defineInRange("depthScalingBlocks", 10.0, 0.0, 512.0);

    public static final ModConfigSpec.DoubleValue DEPTH_SCALING_PERCENT = COMMON
            .comment("Per depth step, the percent air loss goes up by and the nozzle-filter sphere radius",
                    "goes down by. The steps compound (default 10 blocks / 5%: -20 blocks = air x1.05^2,",
                    "radius x0.95^2), and partial steps count, so the change is gradual.")
            .defineInRange("depthScalingPercent", 5.0, 0.0, 100.0);

    public static final ModConfigSpec.BooleanValue MOB_SUFFOCATION = COMMON
            .comment("Whether the murk suffocates non-allowed mobs: they cannot spawn on the fogged side",
                    "and take damage once they have been under it past mobSuffocateDelaySeconds.",
                    "Off lets any mob live under the fog. Players are unaffected either way.")
            .define("mobSuffocation", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ALLOWED_MOBS = COMMON
            .comment("Entity-type IDs allowed to live under the fog (on the murk side of the boundary).",
                    "Anything NOT listed cannot spawn there and starts taking damage after",
                    "mobSuffocateDelaySeconds submerged. IDs may omit the 'minecraft:' namespace.",
                    "Example: allowedMobs = [\"minecraft:cod\", \"axolotl\", \"guardian\"]")
            .defineListAllowEmpty("allowedMobs", Config::defaultAllowedMobs, () -> "minecraft:cod",
                    o -> o instanceof String s && ResourceLocation.tryParse(withNamespace(s)) != null);

    public static final ModConfigSpec.IntValue MOB_SUFFOCATE_DELAY = COMMON
            .comment("Seconds a non-allowed mob can stay under the fog before it starts taking damage.")
            .defineInRange("mobSuffocateDelaySeconds", 5, 0, 600);

    public static final ModConfigSpec.DoubleValue MOB_SUFFOCATE_DAMAGE = COMMON
            .comment("Damage dealt to a non-allowed mob each second once it has been under the fog past",
                    "the delay. 2.0 = one heart per second.")
            .defineInRange("mobSuffocateDamage", 2.0, 0.0, 1000.0);

    static { COMMON.pop(); }   // [suffocation]

    // ==== client [plane] : separation plane (and its cold-vapour layer) ====
    static { CLIENT.push("plane"); }

    public static final ModConfigSpec.BooleanValue RENDER_PLANE = CLIENT
            .comment("Whether to render the semi-transparent separation plane at the breathing boundary.")
            .define("renderPlane", true);

    public static final ModConfigSpec.IntValue PLANE_COLOR = CLIENT
            .comment("Separation plane colour, as a packed ARGB integer (a colour picker edits this",
                    "directly). The alpha channel is ignored: the plane is always drawn opaque so it",
                    "reads as the same dense fog you see beneath it. Default is hex FF406440.")
            .defineInRange("planeColor", 0xFF406440, Integer.MIN_VALUE, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue FOAM_COLOR = CLIENT
            .comment("Foam colour, as a packed ARGB integer. Alpha scales how strongly the foam shows.",
                    "Default is hex FF70947A.")
            .defineInRange("foamColor", 0xFF70947A, Integer.MIN_VALUE, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue FOAM_WIDTH = CLIENT
            .comment("How far (in blocks) the white foam reaches from where the plane meets blocks and",
                    "entities. 0 disables the foam; larger = wider foam band around every edge.")
            .defineInRange("foamWidth", 2.25, 0.0, 8.0);

    public static final ModConfigSpec.BooleanValue SABLE_FOAM = CLIENT
            .comment("If the Sable physics mod is installed, also generate foam around its sub-levels",
                    "(ships / contraptions) where they cross the boundary. No effect without Sable.")
            .define("sableFoam", true);

    public static final ModConfigSpec.BooleanValue PLANE_SOFT_OCCLUSION = CLIENT
            .comment("EXPERIMENTAL. Soft-fade the plane and vapour against the silhouettes of blocks, mobs",
                    "and machines instead of a hard depth cut (uses a per-frame scene-depth snapshot -- see",
                    "SceneDepth). Off skips that snapshot entirely (a small perf win) and cuts every edge",
                    "hard. Experimental because it depends on reading the depth buffer of whatever",
                    "framebuffer the pipeline is drawing into, which other rendering mods and shader packs",
                    "are free to move or replace: where that fails the fade is wrong, or the whole plane is.",
                    "Turn it off first when the murk looks wrong under another rendering mod.")
            .define("planeSoftOcclusion", true);

    public static final ModConfigSpec.IntValue WATERLINE_CELLS_PER_BLOCK = CLIENT
            .comment("Sub-block resolution of the foam distance-field grid (see WaterlineMap), in cells",
                    "per block. Lower trades a coarser foam ring for a smaller grid: halving this quarters",
                    "the cost of every per-tick foam pass (recompute / chamfer / ease / upload).")
            .defineInRange("waterlineCellsPerBlock", 4, 1, 4);

    // ---- client [plane.vapor] : cold-vapour ("liquid nitrogen") layer ----
    static { CLIENT.push("vapor"); }

    public static final ModConfigSpec.BooleanValue RENDER_VAPOR = CLIENT
            .comment("Render the cold-vapour layer on top of the plane: drifting horizontal mist sheets",
                    "(overall variation, densest at grazing angles far away) plus animated vertical splash",
                    "wisps near the camera, for a liquid-nitrogen look. No effect if renderPlane is off.")
            .define("renderVapor", true);

    public static final ModConfigSpec.DoubleValue VAPOR_OFFSET_RED = CLIENT
            .comment("Red offset from the plane colour to the vapour colour (-1..1, added then clamped),",
                    "so the vapour tracks the plane but still reads distinct.")
            .defineInRange("vaporColorOffsetRed", 0.45, -1.0, 1.0);

    public static final ModConfigSpec.DoubleValue VAPOR_OFFSET_GREEN = CLIENT
            .comment("Green offset from the plane colour to the vapour colour (-1..1).")
            .defineInRange("vaporColorOffsetGreen", 0.50, -1.0, 1.0);

    public static final ModConfigSpec.DoubleValue VAPOR_OFFSET_BLUE = CLIENT
            .comment("Blue offset from the plane colour to the vapour colour (-1..1). The defaults together",
                    "lighten the plane colour toward an icy white-blue.")
            .defineInRange("vaporColorOffsetBlue", 0.55, -1.0, 1.0);

    public static final ModConfigSpec.DoubleValue VAPOR_STRENGTH = CLIENT
            .comment("Overall vapour strength (alpha). Lower for a fainter mist, 0 to hide it entirely.")
            .defineInRange("vaporStrength", 0.95, 0.0, 1.0);

    public static final ModConfigSpec.IntValue VAPOR_SHEETS = CLIENT
            .comment("Number of stacked mist sheets over the plane. Each is a grid that rises and falls",
                    "(see vaporUndulation) so the plane never looks dead flat. More sheets = thicker, more",
                    "layered mist (and a touch more cost). 0 disables the sheets.")
            .defineInRange("vaporSheets", 5, 0, 8);

    public static final ModConfigSpec.DoubleValue VAPOR_UNDULATION = CLIENT
            .comment("Maximum height (in blocks) the mist sheets rise off the plane, giving the flat plane",
                    "rolling rises and falls. 0 = flat sheets.")
            .defineInRange("vaporUndulation", 1.5, 0.0, 16.0);

    public static final ModConfigSpec.BooleanValue VAPOR_UNDERWATER = CLIENT
            .comment("Draw a second copy of the mist before the water pass, so water composites OVER it",
                    "and the layer reads as lying beneath the surface of a lake instead of floating on",
                    "top of it. The copy after the water pass is always drawn; together they keep the mist",
                    "continuous across a shoreline. Off = the single pass over water, and half the cost.")
            .define("vaporUnderwater", true);

    static { CLIENT.pop(); }   // [plane.vapor]
    static { CLIENT.pop(); }   // [plane]

    // ==== client [debug] : diagnostics for the render path ====
    static { CLIENT.push("debug"); }

    public static final ModConfigSpec.EnumValue<DebugView> DEBUG_VIEW = CLIENT
            .comment("Replace the separation plane with a raw view of one of the buffers that feed it.",
                    "OFF renders normally. FOAM shows the foam edge (red) and surface spots (blue).",
                    "SCENE_DEPTH shows the depth snapshot the soft edge samples -- flat blue means",
                    "nothing is being read. PLANE_OPACITY shows where the plane is solid (green, writes",
                    "depth) versus softened (red). DISSOLVE_HOLES shows the per-entity discs (green).",
                    "WATERLINE_MAP shows the raw foam distance field.")
            .defineEnum("debugView", DebugView.OFF);

    public static final ModConfigSpec.BooleanValue DEBUG_HUD = CLIENT
            .comment("Draw a text readout of the render state in the corner of the screen: boundary height,",
                    "which side the camera is on, the plane's fade range, whether the scene-depth snapshot",
                    "was captured, how many entity dissolve holes are active, and the foam map's size.")
            .define("debugHud", false);

    public static final ModConfigSpec.BooleanValue LOG_RENDER_COMPAT_WARNINGS = CLIENT
            .comment("Log a one-time warning when this mod detects it can't work correctly with the",
                    "current rendering setup (e.g. the scene-depth snapshot failed, or a sky/weather-",
                    "suppression hook never fired) -- see SceneDepth and LevelRendererMixin. These are",
                    "diagnostics only; turning this off does not change any fallback behaviour, only",
                    "whether it is logged.")
            .define("logRenderCompatWarnings", true);

    static { CLIENT.pop(); }   // [debug]

    static final ModConfigSpec COMMON_SPEC = COMMON.build();
    static final ModConfigSpec CLIENT_SPEC = CLIENT.build();

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

    // How many depthScalingBlocks steps deep world height y sits below the boundary. 0 at or above the
    // boundary; fractional, so the scaling below eases in instead of jumping at every step.
    private static double depthSteps(Level level, double y) {
        if (!DEPTH_SCALING.get()) {
            return 0.0;
        }
        double step = DEPTH_SCALING_BLOCKS.get();
        if (step <= 0.0) {
            return 0.0;
        }
        double below = breathHeight(level) + PLANE_SURFACE_OFFSET - y;
        return below <= 0.0 ? 0.0 : below / step;
    }

    // Air-loss multiplier at world height y: compounds +percent per step of depth.
    public static double depthAirFactor(Level level, double y) {
        double steps = depthSteps(level, y);
        return steps <= 0.0 ? 1.0 : Math.pow(1.0 + DEPTH_SCALING_PERCENT.get() / 100.0, steps);
    }

    // Breathing-sphere radius multiplier at world height y: compounds -percent per step of depth, so a
    // filter far under the boundary reaches deep enough only for a stub of a sphere (or none at all).
    public static double depthRadiusFactor(Level level, double y) {
        double steps = depthSteps(level, y);
        if (steps <= 0.0) {
            return 1.0;
        }
        return Math.pow(Math.max(0.0, 1.0 - DEPTH_SCALING_PERCENT.get() / 100.0), steps);
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

    // Offset eased from the noon value to the midnight value and back over one day.
    private static double overdayOffset(int timeOfDay) {
        double min = OVERDAY_OFFSET_NOON.get();
        double max = OVERDAY_OFFSET_MIDNIGHT.get();
        // f = 0 at noon (6000), 1 at midnight (18000); smooth cosine in between.
        double f = (1.0 - Math.cos(2.0 * Math.PI * (timeOfDay - NOON_TICK) / TICKS_PER_DAY)) / 2.0;
        return min + (max - min) * f;
    }

    private static List<String> defaultSchedule() {
        return new ArrayList<>(List.of("0=-30", "10=40", "80=100"));
    }

    // Parsed schedule, sorted ascending by day. Re-parsed when the underlying list changes.
    private static List<? extends String> cachedScheduleRaw;
    private static double[] schedDays = new double[0];
    private static double[] schedHeights = new double[0];

    private static void ensureSchedule() {
        List<? extends String> raw = PLANE_HEIGHT_SCHEDULE.get();
        if (raw == cachedScheduleRaw) {
            return;
        }
        cachedScheduleRaw = raw;
        List<double[]> entries = new ArrayList<>();
        for (String s : raw) {
            double[] parsed = parseScheduleEntry(s);
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

    // Parses one "day=height" entry into {day, height}, or null if malformed.
    private static double[] parseScheduleEntry(String entry) {
        int eq = entry.indexOf('=');
        if (eq < 0) {
            return null;
        }
        try {
            return new double[] {
                    Double.parseDouble(entry.substring(0, eq).trim()),
                    Double.parseDouble(entry.substring(eq + 1).trim()) };
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

    // Devices snuffed under the fog by default: the vanilla fire-burners plus the burners and engines
    // of the Create-family mods this pack runs with (each entry is simply ignored when its mod is absent).
    private static List<String> defaultSnuffedDevices() {
        return new ArrayList<>(List.of(
                "minecraft:furnace",
                "minecraft:blast_furnace",
                "minecraft:smoker",
                "minecraft:campfire",
                "minecraft:soul_campfire",
                "create:lit_blaze_burner",
                "aeronautics:adjustable_burner",
                "simulated:*_portable_engine"));
    }

    // Coal is what the murk eats: an ore left in the dark under the boundary comes back as the rock it
    // sat in. Ore first, so a transform to a stone that some other rule scours is the config's problem
    // and not the default's.
    private static List<String> defaultBlockTransforms() {
        return new ArrayList<>(List.of(
                "minecraft:coal_ore=minecraft:stone",
                "minecraft:deepslate_coal_ore=minecraft:deepslate"));
    }

    /**
     * Split one "from=to" transform entry. Returns {from, to} with namespaces filled in, or null if it
     * is not a usable pair -- which is also what the config validator tests, so a malformed line is
     * rejected at load rather than silently ignored later.
     */
    static String[] parseTransformEntry(String entry) {
        int eq = entry.indexOf('=');
        if (eq <= 0 || eq == entry.length() - 1) {
            return null;
        }
        String from = entry.substring(0, eq).trim();
        String to = entry.substring(eq + 1).trim();
        if (from.isEmpty() || to.isEmpty() || to.startsWith("#") || to.indexOf('*') >= 0) {
            return null; // the target has to be one concrete block
        }
        if (ResourceLocation.tryParse(withNamespace(to)) == null) {
            return null;
        }
        String fromId = from.startsWith("#") ? "#" + withNamespace(from.substring(1)) : withNamespace(from);
        if (fromId.startsWith("#") && ResourceLocation.tryParse(fromId.substring(1)) == null) {
            return null;
        }
        return new String[] { fromId, withNamespace(to) };
    }

    /**
     * Turn an id with '*' wildcards into a regex for that id shape; everything but '*' is literal.
     * Shared by every config list that matches block ids ({@link FogSnuff}, {@link ScourRules}).
     */
    static java.util.regex.Pattern idGlob(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("\\*", -1)) {
            if (sb.length() > 0) {
                sb.append(".*");
            }
            sb.append(java.util.regex.Pattern.quote(part));
        }
        return java.util.regex.Pattern.compile(sb.toString());
    }

    // A bare "cod" is treated as "minecraft:cod" so the config stays terse.
    static String withNamespace(String s) {
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

    // --- colour helpers ---

    // These are read every frame by the renderers, so the unpacked result is cached and only rebuilt
    // when the packed config value actually changes -- avoids per-frame float[] garbage. The returned
    // arrays are shared and treated as read-only by every caller.
    private static int planeColorRaw = 0;
    private static float[] planeColorCache;

    public static float[] planeColor() {
        int packed = PLANE_COLOR.getAsInt();
        if (planeColorCache == null || packed != planeColorRaw) {
            planeColorRaw = packed;
            planeColorCache = unpackArgb(packed);
        }
        return planeColorCache;
    }

    private static int foamColorRaw = 0;
    private static float[] foamColorCache;

    public static float[] foamColor() {
        int packed = FOAM_COLOR.getAsInt();
        if (foamColorCache == null || packed != foamColorRaw) {
            foamColorRaw = packed;
            foamColorCache = unpackArgb(packed);
        }
        return foamColorCache;
    }

    // Vapour colour = plane colour + per-channel offset (clamped), with the configured strength as alpha.
    private static float[] vaporColorCache;
    private static float[] planeColorForVapor;
    private static double vaporOffsetRaw = Double.NaN;
    private static double vaporStrengthRaw = Double.NaN;

    public static float[] vaporColor() {
        float[] plane = planeColor();
        double dr = VAPOR_OFFSET_RED.get();
        double dg = VAPOR_OFFSET_GREEN.get();
        double db = VAPOR_OFFSET_BLUE.get();
        double strength = VAPOR_STRENGTH.get();
        // One cheap fingerprint of the three offsets: they only ever change together, from the config.
        double offsetKey = dr * 4.0 + dg * 2.0 + db;
        if (vaporColorCache == null || plane != planeColorForVapor || offsetKey != vaporOffsetRaw
                || strength != vaporStrengthRaw) {
            planeColorForVapor = plane;
            vaporOffsetRaw = offsetKey;
            vaporStrengthRaw = strength;
            vaporColorCache = new float[] {
                    clamp01(plane[0] + (float) dr),
                    clamp01(plane[1] + (float) dg),
                    clamp01(plane[2] + (float) db),
                    (float) strength };
        }
        return vaporColorCache;
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
    }

    /** Unpacks a packed ARGB int into {@code float[]{r, g, b, a}} in 0..1. */
    static float[] unpackArgb(int packed) {
        return new float[] {
                ((packed >> 16) & 0xFF) / 255.0F,
                ((packed >> 8) & 0xFF) / 255.0F,
                (packed & 0xFF) / 255.0F,
                ((packed >>> 24) & 0xFF) / 255.0F };
    }
}
