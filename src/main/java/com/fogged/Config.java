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

    public static final ModConfigSpec.IntValue FOG_DISTANCE = BUILDER
            .comment("Render distance (in blocks) of the thick fog applied while the camera is below the",
                    "breathing boundary. Lower = denser fog / shorter view, like being underwater.")
            .defineInRange("fogDistance", 24, 4, 256);

    public static final ModConfigSpec.BooleanValue FLIP_FOG = BUILDER
            .comment("Flip the murk fog to the other side of the plane. Default (false) fogs BELOW the",
                    "boundary (underwater-style); true fogs ABOVE it instead. Affects the fog only, not",
                    "the breathing boundary.")
            .define("flipFog", false);

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

    // ==== [mobs] : the fog lurker + murk suffocation of non-allowed mobs ====
    static { BUILDER.push("mobs"); }

    public static final ModConfigSpec.BooleanValue MOBS_ENABLED = BUILDER
            .comment("Master switch for the mod's mobs and mob behaviour: the fog lurker plus the murk",
                    "suffocation of non-allowed mobs. Off = no lurkers (any already in the world burrow",
                    "away) and non-allowed mobs no longer suffocate under the fog.")
            .define("mobsEnabled", true);

    public static final ModConfigSpec.BooleanValue MOB_SUFFOCATION_ENABLED = BUILDER
            .comment("Whether the murk suffocates non-allowed mobs (blocks their spawns and damages them",
                    "under the fog). Off keeps the lurker but lets any mob live under the fog. Ignored",
                    "when mobsEnabled is off.")
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

    // ---- [mobs.lurker] : the worm that haunts the murk (on/off is mobsEnabled above) ----
    static { BUILDER.push("lurker"); }

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> LURKER_SPAWN_SECONDS = BUILDER
            .comment("Random gap between lurker spawns for a player, as [minSeconds, maxSeconds]. Each",
                    "spawn waits a random time in this range (default 30..1200s = up to 20 minutes).")
            .defineList("lurkerSpawnSeconds", List.of(30, 1200), () -> 30,
                    o -> o instanceof Number n && n.intValue() >= 1 && n.intValue() <= 86400);

    public static final ModConfigSpec.IntValue LURKER_MAX_NEARBY = BUILDER
            .comment("Most lurkers that may exist within lurkerRange of a player at once. Default 1 = a",
                    "single lurker stalks each player.")
            .defineInRange("lurkerMaxNearby", 1, 0, 16);

    public static final ModConfigSpec.IntValue LURKER_RANGE = BUILDER
            .comment("Horizontal range (blocks) used to spawn, count and retire lurkers around a player.",
                    "They appear near its edge and burrow away once no under-fog player is within it.")
            .defineInRange("lurkerRange", 28, 8, 128);

    public static final ModConfigSpec.IntValue LURKER_AGGRO_RADIUS = BUILDER
            .comment("Breaking a block within this many blocks of a docile lurker disturbs it.")
            .defineInRange("lurkerAggroRadius", 12, 1, 64);

    public static final ModConfigSpec.IntValue LURKER_BLOCKS_TO_ATTACK = BUILDER
            .comment("How many blocks must be broken near a docile lurker (within lurkerAggroRadius) before",
                    "it lunges. Each break sends a one-second warning screen shake, doubling in strength per",
                    "block, so tension builds toward the attack.")
            .defineInRange("lurkerBlocksToAttack", 3, 1, 64);

    public static final ModConfigSpec.IntValue LURKER_BLOCK_WINDOW = BUILDER
            .comment("Seconds allowed between blocks for the count to keep building toward the attack. Wait",
                    "longer than this and the disturbance ebbs away one block at a time.")
            .defineInRange("lurkerBlockWindowSeconds", 15, 1, 600);

    public static final ModConfigSpec.IntValue LURKER_MIN_DEPTH = BUILDER
            .comment("A player must be at least this many blocks below the fog surface before a lurker will",
                    "spawn near them, keeping the worm to the deep murk.")
            .defineInRange("lurkerMinDepth", 30, 0, 512);

    public static final ModConfigSpec.DoubleValue LURKER_LUNGE_DAMAGE = BUILDER
            .comment("Damage a lunging lurker deals when it reaches its target. 2.0 = one heart.")
            .defineInRange("lurkerLungeDamage", 7.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue LURKER_SHAKE_RADIUS = BUILDER
            .comment("Client: within this distance of a lurker the screen shakes faintly as a warning, and",
                    "harder the closer it is (and while it lunges). 0 disables the shake.")
            .defineInRange("lurkerShakeRadius", 16.0, 0.0, 64.0);

    static { BUILDER.pop(); }   // [mobs.lurker]
    static { BUILDER.pop(); }   // [mobs]

    // ==== [flora] : fog moss + foggy grass ====
    static { BUILDER.push("flora"); }

    public static final ModConfigSpec.BooleanValue FLORA_ENABLED = BUILDER
            .comment("Master switch for the mod's flora: fog moss (with its rot and spread) and foggy",
                    "grass. Off = no new moss puddles form and existing moss/grass stops growing or",
                    "spreading.")
            .define("floraEnabled", true);

    // ---- [flora.moss] : the murk rotting vegetation under the plane ----
    static { BUILDER.push("moss"); }

    public static final ModConfigSpec.IntValue FOG_MOSS_SKIP = BUILDER
            .comment("Dead zone: the topmost blocks directly under the fog plane that the rot leaves alone.",
                    "The shallow layer right beneath the plane stays untouched; the rot acts on everything",
                    "from this offset down to the bottom of the world.")
            .defineInRange("fogMossSkip", 5, 0, 64);

    public static final ModConfigSpec.IntValue FOG_MOSS_SIZE_PER_STRENGTH = BUILDER
            .comment("Puddle size, in blocks of fog moss, produced by an event of strength 1.0. The size",
                    "scales with the event's strength (leaves 1.0, dying mob 1.25, grass/flowers 0.5).")
            .defineInRange("fogMossSizePerStrength", 8, 1, 256);

    public static final ModConfigSpec.ConfigValue<List<? extends Double>> FOG_MOSS_TEMP_BAND = BUILDER
            .comment("Biome-temperature band that picks the fog-moss variant, as [coldMax, warmMin].",
                    "At/below coldMax grows the cold 'harsh' variant; at/above warmMin the warm 'soft'",
                    "variant; between the two the plain temperate moss. Vanilla ref: snowy 0.0, taiga",
                    "0.25, plains 0.8, jungle 0.95, savanna/desert 1.2-2.0.")
            .defineList("fogMossTempBand", List.of(0.2, 0.9), () -> 0.0,
                    o -> o instanceof Number n && n.doubleValue() >= -2.0 && n.doubleValue() <= 2.0);

    static { BUILDER.pop(); }   // [flora.moss]

    // ---- [flora.grass] : the wispy tufts that grow on fog moss ----
    static { BUILDER.push("grass"); }

    public static final ModConfigSpec.DoubleValue FOGGY_GRASS_SEED_CHANCE = BUILDER
            .comment("Base chance that a freshly-placed moss block sprouts a tuft as a puddle spreads. The",
                    "actual chance is this times the local temperature density (so warm puddles start greener).")
            .defineInRange("foggyGrassSeedChance", 0.08, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue FOGGY_GRASS_GROW_CHANCE = BUILDER
            .comment("Per random-tick chance an existing tuft advances one growth stage (toward its full",
                    "height). Lower = slower growth.")
            .defineInRange("foggyGrassGrowChance", 0.35, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue FOGGY_GRASS_SPREAD_CHANCE = BUILDER
            .comment("Per random-tick chance a tuft tries to spread onto a neighbouring patch of bare moss",
                    "(only succeeds while the patch is below its temperature density cap).")
            .defineInRange("foggyGrassSpreadChance", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.IntValue FOGGY_GRASS_PROXIMITY = BUILDER
            .comment("Radius (in blocks) of the window used to measure how crowded a patch is when deciding",
                    "whether grass may spread. Larger = density is judged over a wider area.")
            .defineInRange("foggyGrassProximity", 4, 1, 16);

    public static final ModConfigSpec.ConfigValue<List<? extends Double>> FOGGY_GRASS_DENSITY = BUILDER
            .comment("Fraction of nearby moss that grows grass, as [coldBiome, warmBiome] (each 0..1).",
                    "Cold biomes (at/below the moss coldMax) use the first, warm (at/above warmMin) the",
                    "second, and between them the density is interpolated from the biome temperature.",
                    "1.0 carpets every moss block; lower leaves bare gaps.")
            .defineList("foggyGrassDensity", List.of(0.05, 0.6), () -> 0.0,
                    o -> o instanceof Number n && n.doubleValue() >= 0.0 && n.doubleValue() <= 1.0);

    static { BUILDER.pop(); }   // [flora.grass]
    static { BUILDER.pop(); }   // [flora]

    static final ModConfigSpec SPEC = BUILDER.build();

    // Strength (puddle-size multiplier) of each fog-moss event. See FOG_MOSS_SIZE_PER_STRENGTH.
    public static final double FOG_MOSS_STRENGTH_LEAVES = 1.0;
    public static final double FOG_MOSS_STRENGTH_MOB = 1.25;
    public static final double FOG_MOSS_STRENGTH_PLANT = 0.5;

    // --- combined range accessors ---
    // Each pulls one end out of a [low, high] list config, falling back to the default if it is short.

    private static double at(List<? extends Number> list, int i, double fallback) {
        return list.size() > i ? list.get(i).doubleValue() : fallback;
    }

    /** Minimum seconds between lurker spawns (first entry of lurkerSpawnSeconds). */
    public static int lurkerSpawnMinSeconds() {
        return (int) at(LURKER_SPAWN_SECONDS.get(), 0, 30);
    }

    /** Maximum seconds between lurker spawns, never below the minimum. */
    public static int lurkerSpawnMaxSeconds() {
        return Math.max(lurkerSpawnMinSeconds(), (int) at(LURKER_SPAWN_SECONDS.get(), 1, 1200));
    }

    /** Temperature at/below which fog moss grows the cold variant (first entry of fogMossTempBand). */
    public static double fogMossColdMax() {
        return at(FOG_MOSS_TEMP_BAND.get(), 0, 0.2);
    }

    /** Temperature at/above which fog moss grows the warm variant (second entry of fogMossTempBand). */
    public static double fogMossWarmMin() {
        return at(FOG_MOSS_TEMP_BAND.get(), 1, 0.9);
    }

    /** Foggy-grass density in cold biomes (first entry of foggyGrassDensity). */
    public static double foggyGrassColdDensity() {
        return at(FOGGY_GRASS_DENSITY.get(), 0, 0.05);
    }

    /** Foggy-grass density in warm biomes (second entry of foggyGrassDensity). */
    public static double foggyGrassWarmDensity() {
        return at(FOGGY_GRASS_DENSITY.get(), 1, 0.6);
    }

    // --- dynamic breathing-boundary height ---

    private static final int TICKS_PER_DAY = 24000;
    private static final int NOON_TICK = 6000; // dayTime 0 = sunrise (06:00), so noon is 6000 ticks in

    // Boundary snaps to this vertical step (blocks). Quantizing the continuous height keeps the plane
    // resting at a fixed Y between steps so it does not z-fight as the schedule/offset drift sub-block.
    private static final double HEIGHT_STEP = 0.25;

    // One-entry memo: breathHeight depends only on the level's day-time, but it is called for every
    // entity every tick (MobSuppressor) and for many blocks in the moss sweep. Caching it per
    // (level, dayTime) collapses all of a tick's calls to a single computation.
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

    // Default allow-list: only the warden belongs in the murk.
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

    // True if the given entity type may live under the fog. Unregistered types are never allowed.
    public static boolean mobAllowedUnderFog(EntityType<?> type) {
        ensureAllowedMobs();
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return allowedMobIds.contains(id);
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
