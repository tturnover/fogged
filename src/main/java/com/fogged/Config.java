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


    // ==== Constants mirrored in GLSL shaders (GLSL can't import Java constants -- keep these in sync
    // by hand whenever any one of them changes). Each entry's Java declaration links back to this list.
    //   FogPlaneRenderer.NOISE_ANCHOR (4096.0)
    //     == fog_plane.fsh  NOISE_PERIOD_BLOCKS
    //     == fog_vapor.fsh  NOISE_PERIOD_BLOCKS
    //     == noise_field.fsh  NOISE_PERIOD_BLOCKS
    //     == IrisShaderPatcher's NOISE_PERIOD_BLOCKS header line
    //   FogPlaneRenderer.MAX_ENTITY_HOLES (32)
    //     == fog_plane.fsh  entity-hole loop bound (`for (int i = 0; i < 32; i++)`)
    //     == fog_plane.json / fog_plane.fsh  EntityHoles[128] (== MAX_ENTITY_HOLES * 4)
    //     == fogged_iris.glsl  fogged_Holes[32] / its loop bound
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
    static {
        COMMON.comment("Where the boundary sits, how the murk behaves at it, and what it does to the",
                "world underneath. Everything here is shared by the server and its clients: it is",
                "gameplay, not looks.");
        COMMON.push("boundary");
    }

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

    public static final ModConfigSpec.DoubleValue FOG_START_RAISE = COMMON
            .comment("Where the murk starts, measured from the visible plane in blocks: positive begins it",
                    "that far ABOVE the surface, 0 exactly at it, negative that far under it. This is the",
                    "line everything asks about which side of the boundary something is on -- the fog, the",
                    "plane, the weather suppression and mob suffocation all move together with it. The",
                    "breathing boundary itself does not move; that is the plane's own height.")
            .defineInRange("fogStartRaise", 0.15, -8.0, 8.0);

    public static final ModConfigSpec.BooleanValue FLIP_FOG = COMMON
            .comment("Flip the murk fog to the other side of the plane. Default (false) fogs BELOW the",
                    "boundary (underwater-style); true fogs ABOVE it instead. Affects the fog only, not",
                    "the breathing boundary.")
            .define("flipFog", false);

    public static final ModConfigSpec.BooleanValue ENABLE_WORLD_CHANGES = COMMON
            .comment("Whether the murk works on the world at all under the boundary. This is the master",
                    "switch over the three below it -- enableExtinguish, enableScour and",
                    "enableTransforms -- and over dropped stacks turning, so off leaves the world exactly",
                    "as it was built: nothing put out, nothing taken, nothing turned into anything else.",
                    "The fog, the plane, the foam and what the murk does to breathing are not affected;",
                    "this is only about blocks and items.",
                    "(Was submergeWorld, which named the feeling rather than the switch.)")
            .define("enableWorldChanges", true);

    public static final ModConfigSpec.IntValue WORLD_CHANGE_SKIP = COMMON
            .comment("Dead zone: the topmost blocks directly under the boundary the murk leaves alone. The",
                    "shallow layer right beneath the plane stays as it was; everything from this offset",
                    "down to the bottom of the world is worked on. (Was submergeSkip.)")
            .defineInRange("worldChangeSkip", 5, 0, 64);

    public static final ModConfigSpec.BooleanValue ENABLE_EXTINGUISH = COMMON
            .comment("The murk puts fire out under the boundary: loose fire and soul fire vanish, lava",
                    "freezes to obsidian or cobble with a fizz, torches are knocked down, and every device",
                    "in the list below is unlit, its burn timer zeroed and its fuel ejected. Off leaves all",
                    "of it burning. Needs enableWorldChanges.")
            .define("enableExtinguish", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SNUFFED_DEVICES = COMMON
            .comment("Block ids of fire-burning devices the murk snuffs out along with the loose fires: each",
                    "is unlit, its burn timer zeroed and any fuel inside it ejected, so it cannot keep",
                    "running under the fog. '*' matches any run of characters, and the 'minecraft:'",
                    "namespace may be omitted. Empty list = leave devices burning. Needs enableWorldChanges.",
                    "Example: snuffedDevices = [\"furnace\", \"create:lit_blaze_burner\"]")
            .defineListAllowEmpty("snuffedDevices", Config::defaultSnuffedDevices, () -> "minecraft:furnace",
                    o -> o instanceof String s && !s.isBlank());

    public static final ModConfigSpec.BooleanValue ENABLE_SCOUR = COMMON
            .comment("The murk takes things out under the boundary: everything in the list below, broken",
                    "without drops, and farmland, which reverts to dirt and takes whatever was planted on",
                    "it. Off leaves them all standing. Needs enableWorldChanges.")
            .define("enableScour", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> SCOURED_BLOCKS = COMMON
            .comment("Blocks the murk takes out under the boundary, broken without drops. The vegetation",
                    "it wilts lives here rather than in code, so a pack can spare a plant or wilt one of",
                    "its own; fires, lava, torches, devices and farmland are still built in.",
                    "Each entry is a block id or, with a leading '#', a block tag. '*' matches any run of",
                    "characters in an id, and the 'minecraft:' namespace may be omitted.",
                    "Needs enableWorldChanges.",
                    "Example: scouredBlocks = [\"cobweb\", \"#minecraft:banners\", \"create:*_casing\"]")
            .defineListAllowEmpty("scouredBlocks", Config::defaultScouredBlocks, () -> "minecraft:cobweb",
                    o -> o instanceof String s && !s.isBlank());

    public static final ModConfigSpec.BooleanValue ENABLE_TRANSFORMS = COMMON
            .comment("Master switch for the transforms list below: off and the murk changes nothing into",
                    "anything -- no copper weathering, no coal ore going back to stone, no dropped stack",
                    "turning over. The list is left as it is, so this can be flipped back without losing",
                    "it, and JEI stops listing the conversions while it is off. What the murk takes",
                    "outright (scouredBlocks) and what it snuffs are not affected. Needs enableWorldChanges.")
            .define("enableTransforms", true);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> TRANSFORMS = COMMON
            .comment("What the murk turns things into under the boundary, one entry per line. One list for",
                    "everything: blocks it finds placed, and stacks dropped in it.",
                    "",
                    "  [count] from = [count] to [@depth] [!silent]",
                    "",
                    "FROM: a block id, an item id, an id with '*' wildcards, or a '#' tag -- or several of",
                    "  those separated by commas, which is one line for a whole family. Every other part of",
                    "  the line applies to the whole of it: one count, one depth, one !silent, covering each",
                    "  id in the list. Whatever it names",
                    "  is converted -- placed blocks if it names a block, dropped stacks if it names an",
                    "  item, both if it names both. The 'minecraft:' namespace may be left off.",
                    "TO: one id, no wildcards and no tag. Name a BLOCK and a placed block is replaced by it,",
                    "  keeping every property the two share (a stair its facing, a door its hinge). Name an",
                    "  ITEM that is not a block and the placed block breaks and drops it instead.",
                    "COUNTS: a number before either id. \"4 diamond=2 dirt\" takes four out of a dropped",
                    "  stack and leaves two, over and over, while at least four are left; a remainder stays",
                    "  as it was. Both default to 1, and neither may exceed 64.",
                    "  Counts are about STACKS. An entry that asks for several of something, or gives back",
                    "  several of a block, does not touch placed blocks at all -- you cannot take four of",
                    "  a block that is standing there, nor put three in the space it occupies, and the",
                    "  murk is not going to break it to make the numbers work. What is standing is left to",
                    "  the rules below. The one exception is a count on an ITEM target: that is how many",
                    "  drop when the block breaks, which is what such an entry asks for anyway.",
                    "@N: only convert at least N blocks BELOW the surface. Measured from the surface, so it",
                    "  moves with the boundary rather than sitting at a fixed Y. Default 0, anywhere the",
                    "  scour reaches; at most 512.",
                    "!silent: keep this one out of JEI. Everything else is listed there under \"Murk",
                    "  Conversion\", with any depth it asks for drawn on the picture.",
                    "",
                    "Examples:",
                    "  \"minecraft:coal_ore=minecraft:stone !silent\"   ore back to the rock it sat in",
                    "  \"copper_block=oxidized_copper\"                 weathered right through, listed in JEI",
                    "  \"moss_block=coarse_dirt @50\"                   only fifty blocks down or deeper",
                    "  \"4 minecraft:diamond=2 minecraft:dirt\"         four diamonds become two dirt",
                    "  \"#minecraft:leaves=1 minecraft:stick !silent\"  every leaf breaks into one stick",
                    "  \"create:*_casing=minecraft:mud @20\"            wildcards match a whole family",
                    "  \"copper_block,exposed_copper=oxidized_copper\"  so do commas, for what globs cannot",
                    "",
                    "Applied before the built-in scour, so an entry here overrides what the murk would",
                    "otherwise do to that block. A transform whose result matches another rule is itself",
                    "converted on the next pass, so do not point one at something another rule takes.",
                    "Needs enableWorldChanges.")
            .defineListAllowEmpty("transforms", Config::defaultTransforms,
                    () -> "minecraft:coal_ore=minecraft:stone !silent",
                    o -> o instanceof String s && parseTransform(s) != null);

    static { COMMON.pop(); }   // [boundary]

    // ==== common [suffocation] : what the murk does to the things breathing in it ====
    static {
        COMMON.comment("What the murk does to the things breathing in it -- players and mobs -- and how",
                "much worse that gets with depth.");
        COMMON.push("suffocation");
    }

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
                    "breathing spheres shrink, by depthScalingPercent for every depthScalingBlocks below",
                    "the boundary. Off = the same everywhere below it.")
            .define("depthScaling", true);

    public static final ModConfigSpec.DoubleValue DEPTH_SCALING_BLOCKS = COMMON
            .comment("How many blocks below the boundary count as one step of depth. Each step makes the",
                    "murk bite harder by depthScalingPercent: air drains faster and nozzle-filter",
                    "breathing spheres shrink. Smaller = the depth tells on you sooner.",
                    "No effect with depthScaling off.")
            .defineInRange("depthScalingBlocks", 5.0, 0.0, 512.0);

    public static final ModConfigSpec.DoubleValue DEPTH_SCALING_PERCENT = COMMON
            .comment("Per depth step, the percent air loss goes up by and the nozzle-filter sphere radius",
                    "goes down by. The steps compound (default 5 blocks / 5%: -10 blocks = air x1.05^2,",
                    "radius x0.95^2), and partial steps count, so the change is gradual.")
            .defineInRange("depthScalingPercent", 5.0, 0.0, 100.0);

    public static final ModConfigSpec.BooleanValue MOB_SUFFOCATION = COMMON
            .comment("Whether the murk suffocates non-allowed mobs: they cannot spawn on the fogged side",
                    "and take damage once they have been under it past mobSuffocateDelaySeconds.",
                    "Off lets any mob live under the fog. Players are unaffected either way.")
            .define("mobSuffocation", true);

    public static final ModConfigSpec.BooleanValue MOB_SPAWNS_IN_SPHERES = COMMON
            .comment("Whether nozzle-filter breathing spheres are exempt from the spawn ban above: on,",
                    "a mob may spawn inside a sphere under the fog like anywhere above the boundary, and",
                    "only starts suffocating once it leaves. Off keeps the ban over the whole fogged side.",
                    "No effect with mobSuffocation off.")
            .define("mobSpawnsInBreathingSpheres", true);

    public static final ModConfigSpec.BooleanValue MOB_ESCAPE = COMMON
            .comment("Whether a non-allowed mob caught under the fog runs for air: it heads for the nearest",
                    "nozzle-filter breathing sphere, or climbs towards the boundary when none is near.",
                    "This is an ordinary AI goal driving the mob's own pathfinding, so modded mobs handle it",
                    "with their own movement. Off = mobs stay put and drown where they stand.",
                    "No effect with mobSuffocation off.")
            .define("mobEscape", true);

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

    // ==== common [pillars] : karst towers standing out of the murk ====
    static {
        COMMON.comment("Stone karst towers -- the Ha Long Bay kind -- raised from bedrock up to around the",
                "murk's high-water mark, in scattered groups. Worldgen, so changing any of this only",
                "affects chunks generated afterwards; it never reshapes ground you have already seen.");
        COMMON.push("pillars");
    }

    public static final ModConfigSpec.BooleanValue GENERATE_PILLARS = COMMON
            .comment("Whether pillar groups are generated at all. Off leaves worldgen untouched.")
            .define("generatePillars", true);

    static {
        COMMON.comment("Where the groups stand and how often you meet them.");
        COMMON.push("layout");
    }

    public static final ModConfigSpec.IntValue PILLAR_GROUP_SPACING = COMMON
            .comment("Average distance between one group and the next, in blocks. This is the single",
                    "setting that decides how often you meet them at all, and it is meant to be large:",
                    "a group is a landmark you come across, and at close spacing they stop being a find",
                    "and become the scenery.",
                    "Note how it scales. Groups sit on a grid of this size, so how often you run into one",
                    "as you travel goes with the SQUARE of it: doubling this makes them four times as",
                    "hard to come across, not twice.",
                    "Crests are allowed to run through one another where they happen to meet -- a pair",
                    "that crosses is worth stumbling on -- which is the other reason to keep this well",
                    "above pillarRidgeLengthMax, so that it stays a rarity rather than the rule.")
            .defineInRange("pillarGroupSpacing", 1250, 64, 60000);

    public static final ModConfigSpec.BooleanValue PILLAR_SPAWN_GROUP = COMMON
            .comment("Always put one group within a hundred blocks of the world origin, whatever the",
                    "spacing is set to. At the default spacing a new world can easily start thousands of",
                    "blocks from the nearest group, and these are most of what the mod has to show.",
                    "Measured from x=0 z=0, not from the spawn point the game later picks: worldgen has",
                    "to give the same answer every time it is asked about a chunk, and the spawn point is",
                    "not settled until some of the world has already been generated. The two are near",
                    "enough each other that it makes no practical difference.")
            .define("pillarSpawnGroup", true);

    public static final ModConfigSpec.DoubleValue PILLAR_RIDGE_CHANCE = COMMON
            .comment("Share of groups that come out as a RIDGE rather than as a cluster of isles, 0 to 1.",
                    "A ridge strings its towers along one line, close enough to run together into a wall",
                    "you can follow; a cluster scatters them over open ground as separate sea stacks.")
            .defineInRange("pillarRidgeChance", 0.33, 0.0, 1.0);

    public static final ModConfigSpec.IntValue PILLAR_RIDGE_LENGTH_MIN = COMMON
            .comment("Shortest a ridge may run, in blocks, end to end.")
            .defineInRange("pillarRidgeLengthMin", 400, 32, 20000);

    public static final ModConfigSpec.IntValue PILLAR_RIDGE_LENGTH_MAX = COMMON
            .comment("Longest a ridge may run, in blocks. Clamped up to the minimum if set below it.",
                    "A ridge is free to be longer than the spacing between groups -- it is laid out from",
                    "its own midpoint rather than inside a patch -- so a long one will run past its",
                    "neighbours, which is what makes it read as a coastline rather than a clump.")
            .defineInRange("pillarRidgeLengthMax", 1000, 32, 20000);

    public static final ModConfigSpec.IntValue PILLAR_ISLE_RADIUS = COMMON
            .comment("How far from its middle a cluster of isles scatters its towers, in blocks.")
            .defineInRange("pillarIsleRadius", 80, 16, 2000);

    public static final ModConfigSpec.DoubleValue PILLAR_DENSITY = COMMON
            .comment("How thickly a CLUSTER packs its isles, 0 to 1 -- the chance that any one spot in it",
                    "raises a tower. Ridges ignore this: a crest is continuous by definition, and only",
                    "notches itself here and there.")
            .defineInRange("pillarDensity", 0.45, 0.0, 1.0);

    static { COMMON.pop(); }   // [pillars.layout]

    static {
        COMMON.comment("What one tower is built out of, and the range of builds a group draws from.");
        COMMON.push("shape");
    }

    public static final ModConfigSpec.ConfigValue<String> PILLAR_BLOCK = COMMON
            .comment("Block the towers are built from, by id. The 'minecraft:' namespace may be omitted.",
                    "Falls back to stone if the id names nothing.")
            .define("pillarBlock", "stone");

    public static final ModConfigSpec.DoubleValue PILLAR_RADIUS_MIN = COMMON
            .comment("Thinnest tower, as a radius in blocks.")
            .defineInRange("pillarRadiusMin", 3.0, 1.0, 24.0);

    public static final ModConfigSpec.DoubleValue PILLAR_RADIUS_MAX = COMMON
            .comment("Thickest tower, as a radius in blocks. Clamped up to the minimum if set below it.",
                    "The span from the minimum is what gives a group its range of builds: at the defaults",
                    "the slenderest tower is a sea stack and the boldest is a headland twice the girth.")
            .defineInRange("pillarRadiusMax", 14.0, 1.0, 24.0);

    public static final ModConfigSpec.IntValue PILLAR_TOP_VARIANCE = COMMON
            .comment("How far a tower's top may fall either side of the murk's high-water mark, in blocks.",
                    "0 tops every tower off at exactly that height; the default lets them break the",
                    "surface or stop short of it by up to this much, which is what makes a group read as",
                    "islands rather than a fence.")
            .defineInRange("pillarTopVariance", 20, 0, 128);

    public static final ModConfigSpec.DoubleValue PILLAR_TILT = COMMON
            .comment("Greatest lean off vertical, in degrees. Each tower leans a random amount up to this,",
                    "in a random direction. 0 stands them all straight up.")
            .defineInRange("pillarTilt", 5.0, 0.0, 20.0);

    public static final ModConfigSpec.IntValue PILLAR_LEDGES = COMMON
            .comment("Most flat shelves a tower may carry on its flanks. Each is a level bench a few",
                    "blocks thick jutting from one side, which is where the greenery below gets a footing;",
                    "0 leaves the flanks sheer.")
            .defineInRange("pillarLedges", 4, 0, 12);

    static { COMMON.pop(); }   // [pillars.shape]

    static {
        COMMON.comment("Everything laid over the bare rock: its bedding, the scree at its foot, and what grows",
                "or freezes on it.");
        COMMON.push("surface");
    }

    public static final ModConfigSpec.BooleanValue PILLAR_STONE_BANDS = COMMON
            .comment("Band the towers with the vanilla stone variants -- granite, diorite, andesite,",
                    "tuff, deepslate -- following the same strata the flanks step on, so the rock reads",
                    "as bedded layers rather than one grey mass. The beds roll and dip across a tower",
                    "and thin out part-way over it, so they read as rock rather than as painted stripes.",
                    "Ores are not touched here at all: ordinary world generation runs through the towers",
                    "at a later step and seeds them exactly as it seeds any other stone.",
                    "Only applies when pillarBlock is left at stone; any other choice is used as given.")
            .define("pillarStoneBands", true);

    public static final ModConfigSpec.IntValue PILLAR_BASE_EROSION = COMMON
            .comment("How far a tower's debris skirt spreads where it meets the ground, in blocks.",
                    "Without one a tower is a shape stamped through the landscape, meeting it at a hard",
                    "vertical seam that reads as a build rather than as rock; the skirt is the scree that",
                    "would have come off it, piled where it fell so the two run into each other.",
                    "It beds on the sea floor, not on the water or the ice above it, so a tower standing",
                    "out of a lake or a frozen ocean gets its skirt down where the rock is.",
                    "0 leaves the seam bare.")
            .defineInRange("pillarBaseErosion", 6, 0, 16);

    public static final ModConfigSpec.BooleanValue PILLAR_CLIMATE = COMMON
            .comment("Dress each tower to the climate it stands in, instead of giving them all the same",
                    "temperate grass: snow over the level ground and icicles hung from the undercut where",
                    "it is cold, leaves and trailing vines down the flanks with jungle bushes on the flats",
                    "where it is warm and wet. The rock itself is the same everywhere either way.",
                    "Warm needs rainfall as well as heat, so a desert -- the hottest biome there is --",
                    "stays bare rather than being hung with vines.")
            .define("pillarClimate", true);

    public static final ModConfigSpec.BooleanValue PILLAR_GREENERY = COMMON
            .comment("Grass the level ground on a tower -- its cap and its shelves -- and scatter foliage",
                    "over it. Sheer faces are always left as bare stone. Off leaves the whole tower bare.")
            .define("pillarGreenery", true);

    public static final ModConfigSpec.IntValue PILLAR_GREENERY_DEPTH = COMMON
            .comment("How far below the murk's high-water mark greenery still grows, in blocks. Deeper",
                    "shelves are left bare: the murk scours flora under the boundary anyway, so growing it",
                    "down there only to have it stripped is wasted worldgen.")
            .defineInRange("pillarGreeneryDepth", 24, 0, 384);

    static { COMMON.pop(); }   // [pillars.surface]

    static { COMMON.pop(); }   // [pillars]

    // ==== client [plane] : separation plane (and its cold-vapour layer) ====
    static {
        CLIENT.comment("The murk's visible surface: its colour, the foam along its waterline, and how it",
                "meets the blocks that cross it. Client-side, so a server cannot dictate any of it.");
        CLIENT.push("plane");
    }

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
                    "SceneDepth), open a soft disc around every entity crossing the boundary so it is",
                    "not sliced by the surface. Water counts like a block here: a fall or a current",
                    "crossing the boundary dissolves the surface around it too. Off skips the snapshot",
                    "and the per-frame entity scan both (a small perf win) and cuts every edge hard.",
                    "Experimental because it depends on reading the depth buffer of whatever",
                    "framebuffer the pipeline is drawing into, which other rendering mods and shader packs",
                    "are free to move or replace: where that fails the fade is wrong, or the whole plane is.",
                    "Off by default for that reason -- turn it on to soften the edges, and turn it back",
                    "off first when the murk looks wrong under another rendering mod.")
            .define("planeSoftOcclusion", false);

    public static final ModConfigSpec.DoubleValue MURK_DARKNESS = CLIENT
            .comment("The surface's shadow, 0 to 1: how much darker the world under the boundary is drawn,",
                    "by how deep under it each thing lies (full a few blocks down), seen from either side,",
                    "so sunlit ground and a shader pack's sunlight do not stay bright under the murk.",
                    "0 leaves the lighting alone.")
            .defineInRange("murkDarkness", 0.35, 0.0, 1.0);

    public static final ModConfigSpec.IntValue WATERLINE_CELLS_PER_BLOCK = CLIENT
            .comment("Sub-block resolution of the foam distance-field grid (see WaterlineMap), in cells",
                    "per block. Lower trades a coarser foam ring for a smaller grid: halving this quarters",
                    "the cost of every per-tick foam pass (recompute / chamfer / ease / upload).")
            .defineInRange("waterlineCellsPerBlock", 4, 1, 4);

    // ---- client [plane.dither] : the screen-door dither ----
    static {
        CLIENT.comment("The pixel dither: how the plane thins out around the camera, and the grain of every",
                "dithered edge. Under an Iris shader pack this is also how the whole murk is drawn.");
        CLIENT.push("dither");
    }

    public static final ModConfigSpec.BooleanValue PLANE_NEAR_DITHER = CLIENT
            .comment("Dissolve the plane into a pixel dither as the camera comes close to it, so the",
                    "surface opens up around the eye instead of snapping in as a hard sheet the moment",
                    "the head crosses it. The same near-camera dither Block Dithering gives blocks.")
            .define("planeNearDither", true);

    public static final ModConfigSpec.DoubleValue NEAR_DITHER_START = CLIENT
            .comment("Distance from the camera (in blocks) inside which the plane is thinned all the way",
                    "down to nearDitherMinVisibility.")
            .defineInRange("nearDitherStart", 0.5, 0.0, 32.0);

    public static final ModConfigSpec.DoubleValue NEAR_DITHER_END = CLIENT
            .comment("Distance from the camera (in blocks) beyond which the plane is solid again. Clamped",
                    "up to nearDitherStart if set below it.")
            .defineInRange("nearDitherEnd", 4.0, 0.0, 64.0);

    public static final ModConfigSpec.DoubleValue NEAR_DITHER_MIN_VISIBILITY = CLIENT
            .comment("How much of the plane is left right at the camera, 0 to 1: 0 opens it fully, 1",
                    "never thins it at all.")
            .defineInRange("nearDitherMinVisibility", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.IntValue DITHER_PIXEL_SIZE = CLIENT
            .comment("Screen pixels per dither cell. 1 is a fine screen-door; larger is chunkier and",
                    "closer to the blocky look of the foam.")
            .defineInRange("ditherPixelSize", 2, 1, 8);

    static { CLIENT.pop(); }   // [plane.dither]

    // ---- client [plane.vapor] : cold-vapour ("liquid nitrogen") layer ----
    static {
        CLIENT.comment("The cold-vapour layer: stacked mist sheets over the surface, terraced by a noise",
                "field so the plane never reads as dead flat.");
        CLIENT.push("vapor");
    }

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
                    "layered mist (and a touch more cost).",
                    "0 is how the mist is turned off: nothing is drawn and nothing is computed for it.")
            .defineInRange("vaporSheets", 5, 0, 8);

    public static final ModConfigSpec.DoubleValue VAPOR_UNDULATION = CLIENT
            .comment("Maximum height (in blocks) the mist sheets rise off the plane, giving the flat plane",
                    "rolling rises and falls. 0 = flat sheets.")
            .defineInRange("vaporUndulation", 1.5, 0.0, 16.0);

    public static final ModConfigSpec.BooleanValue VAPOR_UNDERSIDE = CLIENT
            .comment("Hang the mist sheets under the plane as well as over it, mirrored. On, the layer looks",
                    "the same from either side and does not flip across as you cross the boundary. Off (the",
                    "default), the mist sits on top only: from beneath, the murk is a clean ceiling. Distinct",
                    "from vaporUnderwater, which is about the water pass rather than the side of the plane.")
            .define("vaporUnderside", false);

    public static final ModConfigSpec.BooleanValue VAPOR_UNDERWATER = CLIENT
            .comment("Draw a second copy of the mist before the water pass, so water composites OVER it",
                    "and the layer reads as lying beneath the surface of a lake instead of floating on",
                    "top of it. The copy after the water pass is always drawn; together they keep the mist",
                    "continuous across a shoreline. Off = the single pass over water, and half the cost.")
            .define("vaporUnderwater", true);

    static { CLIENT.pop(); }   // [plane.vapor]
    static { CLIENT.pop(); }   // [plane]

    // ==== client [debug] : diagnostics for the render path ====
    static {
        CLIENT.comment("Ways of seeing what the renderer is doing: raw views of the buffers behind the",
                "murk, a readout of its state, and whether compatibility trouble is logged.");
        CLIENT.push("debug");
    }

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

    /**
     * The highest the breathing boundary ever gets: the tallest height in the schedule plus the larger
     * of the two overday offsets. This is the murk's high-water mark, and what the karst towers are
     * sized against (see {@code KarstPillarsFeature}).
     *
     * <p>Deliberately independent of the world clock, unlike {@link #breathHeight}. Worldgen happens
     * once, whenever a chunk is first reached, so sizing towers against the CURRENT boundary would make
     * their height depend on how far into the game the player was when they walked that way. Against
     * the schedule's peak, every group in the world is cut to the same line no matter when it is found.
     */
    public static double maxBreathHeight() {
        ensureSchedule();
        double peak = Double.NEGATIVE_INFINITY;
        for (double h : schedHeights) {
            peak = Math.max(peak, h);
        }
        if (peak == Double.NEGATIVE_INFINITY) {
            peak = 0.0; // empty schedule: breathHeight() falls back the same way
        }
        return peak + Math.max(OVERDAY_OFFSET_NOON.get(), OVERDAY_OFFSET_MIDNIGHT.get());
    }

    /**
     * World Y of the murk's visible surface -- the top of the separation plane.
     *
     * <p>{@link Integer#MIN_VALUE} when there is nothing there to land on: the plane is not being
     * drawn, or flipFog has put the murk overhead, where it is a ceiling rather than a floor. Callers
     * that treat the plane as ground should check for that rather than assume a surface exists.
     */
    public static int planeSurfaceY(Level level) {
        if (!RENDER_PLANE.getAsBoolean() || FLIP_FOG.getAsBoolean()) {
            return Integer.MIN_VALUE;
        }
        return (int) Math.ceil(breathHeight(level) + PLANE_SURFACE_OFFSET);
    }

    // True when a camera at world height y is on the fogged side of the boundary (the thick murk side).
    // Normally that is below the boundary; flipFog moves it to the side above. Shared by the fog
    // override, the separation plane and the weather suppression so they all agree on the murk side.
    public static boolean fogged(Level level, double y) {
        double fogLine = breathHeight(level) + PLANE_SURFACE_OFFSET + FOG_START_RAISE.get();
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
    // sat in. Silent -- it is the murk taking something, not a process to look up.
    //
    // Copper it weathers, all the way through, which IS worth looking up: someone who finds their roof
    // green should be able to ask why. Every unwaxed weathering block, in each of its three
    // un-oxidised stages. Waxed copper is deliberately absent -- wax is what stops the weather getting
    // at it, and the murk is weather. Listed rather than driven off vanilla's WeatheringCopper so a
    // pack can edit any line of it.
    //
    // And moss, which cannot hold on fifty blocks down; that depth is what makes it worth showing.
    private static final String[][] COPPER_FAMILIES = {
            { "copper_block", "exposed_copper", "weathered_copper", "oxidized_copper" },
            { "cut_copper", "exposed_cut_copper", "weathered_cut_copper", "oxidized_cut_copper" },
            { "cut_copper_stairs", "exposed_cut_copper_stairs", "weathered_cut_copper_stairs",
                    "oxidized_cut_copper_stairs" },
            { "cut_copper_slab", "exposed_cut_copper_slab", "weathered_cut_copper_slab",
                    "oxidized_cut_copper_slab" },
            { "chiseled_copper", "exposed_chiseled_copper", "weathered_chiseled_copper",
                    "oxidized_chiseled_copper" },
            { "copper_grate", "exposed_copper_grate", "weathered_copper_grate", "oxidized_copper_grate" },
            { "copper_door", "exposed_copper_door", "weathered_copper_door", "oxidized_copper_door" },
            { "copper_trapdoor", "exposed_copper_trapdoor", "weathered_copper_trapdoor",
                    "oxidized_copper_trapdoor" },
            { "copper_bulb", "exposed_copper_bulb", "weathered_copper_bulb", "oxidized_copper_bulb" },
    };

    // What the murk does to the things growing under it. These were rules in FogScour until they were
    // moved out here, where a pack can argue with them.
    private static List<String> defaultScouredBlocks() {
        return new ArrayList<>(List.of(
                "#minecraft:leaves",
                "#minecraft:flowers",
                "minecraft:short_grass",
                "minecraft:tall_grass",
                "minecraft:fern",
                "minecraft:large_fern",
                "minecraft:sweet_berry_bush"));
    }

    private static List<String> defaultTransforms() {
        List<String> out = new ArrayList<>();
        out.add("minecraft:coal_ore=minecraft:stone !silent");
        // The sward is the first thing the murk takes. Coarse dirt, not plain dirt, because plain dirt
        // re-grasses from a lit neighbour the moment the boundary moves off it. Silent: it is the murk
        // killing the grass, not a process anyone looks up.
        out.add("minecraft:grass_block=minecraft:coarse_dirt !silent");
        out.add("minecraft:deepslate_coal_ore=minecraft:deepslate !silent");
        for (String[] family : COPPER_FAMILIES) {
            // One line a family: the three un-oxidised stages, comma-separated, all ending at the same
            // block. Twenty-seven lines said the same thing.
            StringBuilder from = new StringBuilder();
            for (int i = 0; i < family.length - 1; i++) {
                from.append(i == 0 ? "" : ",").append("minecraft:").append(family[i]);
            }
            out.add(from + "=minecraft:" + family[family.length - 1]);
        }
        return out;
    }

    /** Deepest a transform may ask to be, in blocks below the surface. Past the world's own height. */
    static final int MAX_TRANSFORM_DEPTH = 512;
    /** Most a transform may take from a stack, or leave in its place. A stack is 64 at the widest. */
    static final int MAX_TRANSFORM_COUNT = 64;

    private static final String FLAG_SILENT = "!silent";

    /**
     * One parsed line of {@link #TRANSFORMS}: "[N ]from=[M ]to [@depth] [!silent]".
     *
     * <p>{@code from} keeps its '#' (a tag) or its '*' wildcards; both ids have had a namespace filled
     * in. Counts are 1 unless the line says otherwise, and are about stacks -- a placed block is one
     * block and ignores them.
     */
    record Transform(int fromCount, List<String> from, int toCount, String to, int depth, boolean silent) {}

    /**
     * Parse one transform line, or return null if it is not usable -- which is what the config
     * validator tests, so a malformed line is rejected at load rather than ignored later.
     *
     * <p>Neither '@', '!' nor a space can appear in a resource location, so none of the three markers
     * can ever be part of an id.
     */
    static Transform parseTransform(String rawEntry) {
        String entry = rawEntry.trim();
        boolean silent = false;
        int flag = entry.toLowerCase(java.util.Locale.ROOT).indexOf(FLAG_SILENT);
        if (flag >= 0) {
            silent = true;
            entry = (entry.substring(0, flag) + entry.substring(flag + FLAG_SILENT.length())).trim();
        }
        if (entry.indexOf('!') >= 0) {
            return null; // some other flag: a typo, not something to quietly drop
        }

        int depth = 0;
        int at = entry.lastIndexOf('@');
        if (at >= 0) {
            Integer parsed = number(entry.substring(at + 1), MAX_TRANSFORM_DEPTH);
            if (parsed == null) {
                return null;
            }
            depth = parsed;
            entry = entry.substring(0, at).trim();
        }

        int eq = entry.indexOf('=');
        if (eq <= 0 || eq == entry.length() - 1) {
            return null;
        }
        String[] left = side(entry.substring(0, eq), true);
        String[] right = side(entry.substring(eq + 1), false);
        if (left == null || right == null) {
            return null;
        }
        // The left may name several things at once, comma-separated: one line for a whole family,
        // where the alternative is the same target written out a dozen times.
        List<String> from = new ArrayList<>();
        for (String part : left[1].split(",", -1)) {
            String id = idOrTag(part.trim(), true);
            if (id == null) {
                return null;
            }
            from.add(id);
        }
        return new Transform(Integer.parseInt(left[0]), List.copyOf(from),
                Integer.parseInt(right[0]), right[1], depth, silent);
    }

    // One side of the '=': an optional count, then an id. Returns {count, id} or null.
    private static String[] side(String raw, boolean isFrom) {
        String text = raw.trim();
        int count = 1;
        int space = text.indexOf(' ');
        if (space > 0) {
            Integer parsed = number(text.substring(0, space), MAX_TRANSFORM_COUNT);
            if (parsed != null && parsed >= 1) {
                count = parsed;
                text = text.substring(space + 1).trim();
            }
            // A leading token that is not a count is left where it is: the only other thing a space can
            // separate is the ids of a comma-separated list, written out with room to breathe.
        }
        // Whitespace inside the list is fine -- "a, b, c" reads better than "a,b,c" -- but nowhere else.
        text = text.replace(" ", "");
        if (text.isEmpty()) {
            return null;
        }
        if (isFrom) {
            return new String[] { Integer.toString(count), text }; // ids are checked one by one above
        }
        if (text.indexOf(',') >= 0) {
            return null; // the target has to be one concrete thing
        }
        String id = idOrTag(text, false);
        return id == null ? null : new String[] { Integer.toString(count), id };
    }

    // One id, with its namespace filled in: a '#' tag or a '*' glob on the left, one plain id on the
    // right. Null if it is not usable there.
    private static String idOrTag(String raw, boolean isFrom) {
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.startsWith("#")) {
            if (!isFrom) {
                return null; // a tag names many things; there is nothing to turn INTO
            }
            String id = withNamespace(raw.substring(1));
            return ResourceLocation.tryParse(id) == null ? null : "#" + id;
        }
        String id = withNamespace(raw);
        if (!isFrom && id.indexOf('*') >= 0) {
            return null;
        }
        if (id.indexOf('*') < 0 && ResourceLocation.tryParse(id) == null) {
            return null;
        }
        return id;
    }

    private static Integer number(String raw, int max) {
        try {
            int value = Integer.parseInt(raw.trim());
            return value < 0 || value > max ? null : value;
        } catch (NumberFormatException e) {
            return null;
        }
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
