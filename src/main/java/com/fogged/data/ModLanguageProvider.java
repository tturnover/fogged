package com.fogged.data;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import com.fogged.Fogged;
import com.fogged.registry.ModBlocks;
import com.fogged.registry.ModItems;

import net.minecraft.data.PackOutput;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.common.data.LanguageProvider;

/**
 * Emits {@code assets/fogged/lang/en_us.json} entries for every block and item, deriving a readable
 * display name from the registry path ({@code fog_detector} -&gt; {@code Fog Detector}). Block names come
 * from the block (its {@link BlockItem} shares the same translation key), plain items from the item.
 */
public class ModLanguageProvider extends LanguageProvider {

    // Names that are not just the registry path spelled out -- the filter is sold on what it filters,
    // not on the Create nozzle it replaces.
    private static final Map<String, String> NAMES = Map.of(
            "nozzle_filter", "Fog Filter");

    public ModLanguageProvider(PackOutput output) {
        super(output, Fogged.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        addConfigTranslations();

        // Create-style Fogged tooltips (see FoggedTooltip / FoggedTooltips). The "Hold [Shift]" prompt
        // reuses Create's own lang keys at runtime, so only the description bodies live here.
        add("fogged.tooltip.nozzle.desc1", "Apply a _Wool Block_ to filter out _thick fog_.");
        add("fogged.tooltip.nozzle.desc2", "An _Encased Fan's_ speed controls the _breathable area_.");
        add("fogged.tooltip.fog_detector.desc1", "Emits a _redstone signal_ by how deep it sits under the fog.");
        add("fogged.tooltip.fog_detector.desc2", "Right-click with _another_ to stack it taller.");

        // Custom death messages (keyed by each damage type's message_id).
        // JEI category for the murk's block conversions (see FoggedJeiPlugin); only shown when JEI is in.
        add("fogged.jei.murk_transform", "Murk Conversion");

        add("death.attack.fog_suffocation", "%1$s was swallowed by the fog");
        add("death.attack.fog_suffocation.player", "%1$s drowned in the fog while fleeing %2$s");

        ModBlocks.BLOCKS.getEntries()
                .forEach(holder -> add(holder.get(), name(holder.getId().getPath())));

        ModItems.ITEMS.getEntries().forEach(holder -> {
            if (holder.get() instanceof BlockItem) {
                return; // shares the block's translation key
            }
            add(holder.get(), name(holder.getId().getPath()));
        });
    }

    /** Config-screen translation keys, one per option in {@link com.fogged.Config}. */
    private void addConfigTranslations() {

        add("fogged.configuration.title", "Fogged Configs");
        add("fogged.configuration.section.fogged.common.toml", "Fogged Gameplay");
        add("fogged.configuration.section.fogged.common.toml.title", "Fogged Gameplay");
        add("fogged.configuration.section.fogged.client.toml", "Fogged Rendering");
        add("fogged.configuration.section.fogged.client.toml.title", "Fogged Rendering");
        add("fogged.configuration.boundary", "Boundary");
        add("fogged.configuration.suffocation", "Suffocation");
        add("fogged.configuration.compat", "Other Mods");
        add("fogged.configuration.pillars", "Karst Pillars");
        add("fogged.configuration.layout", "Where They Stand");
        add("fogged.configuration.shape", "How They Are Built");
        add("fogged.configuration.surface", "What Covers Them");
        add("fogged.configuration.plane", "Separation Plane");
        add("fogged.configuration.dither", "Pixel Dither");
        add("fogged.configuration.vapor", "Cold Vapour");
        add("fogged.configuration.debug", "Debug");
        cfg("dimensions", "Fogged Dimensions",
                "Dimension ids the murk exists in, one per line. Anywhere unlisted has no boundary at all. "
                        + "\"*\" means every dimension.");
        cfg("planeHeightSchedule", "Boundary Height Schedule",
                "One \"day=height\" entry per line; the boundary eases between them as the days pass.");
        cfg("planeHeightCycle", "Cycle Height Schedule",
                "Loop the schedule back and forth instead of holding the last height forever.");
        cfg("overdayOffsetNoon", "Daily Offset At Noon",
                "Height added to the schedule at noon -- the low point of the daily rise and fall.");
        cfg("overdayOffsetMidnight", "Daily Offset At Midnight",
                "Height added to the schedule at midnight -- the high point of the daily rise and fall.");
        cfg("fogDistance", "Under-Plane Fog Distance",
                "How far you can see (in blocks) once the camera is under the boundary. Lower = denser murk.");
        cfg("fogStartRaise", "Murk Start Offset",
                "Where the murk begins, from the plane: above it, 0 at it, or below it.");
        cfg("flipFog", "Flip Fog Side",
                "Put the murk above the boundary instead of below it. Does not move the breathing boundary.");
        cfg("enableWorldChanges", "Change The World",
                "Master switch over everything the murk does to blocks and items below the boundary: "
                        + "putting fire out, taking things, turning them into other things. Off leaves "
                        + "the world exactly as it was built.");
        cfg("worldChangeSkip", "Untouched Layer",
                "How many blocks directly under the boundary the murk leaves alone.");
        cfg("worldChangeDelaySeconds", "Working Time",
                "Seconds a block or dropped stack steams under the murk before it changes. 0 changes it "
                        + "at once. Fires and devices go out immediately either way.");
        add("fogged.configuration.group.murkActions", "What The Murk Does");
        add("fogged.configuration.boundary.tooltip", "Where the boundary sits, how the murk behaves at it, and what it does to the world underneath. Shared by the server and its clients: gameplay, not looks.");
        add("fogged.configuration.suffocation.tooltip", "What the murk does to the things breathing in it -- players and mobs -- and how much worse that gets with depth.");
        add("fogged.configuration.compat.tooltip", "What the murk does inside other mods: to the survival mods that measure a player, and to Sable's ships and contraptions -- whether it floats them, how heavily it carries them, and how it marks where they cut the surface. Each value is read only when its mod is installed.");
        cfg("sableBuoyancy", "Float Ships On The Murk",
                "Ships and contraptions float on the murk as they would on water: every float block under "
                        + "the surface is pushed back up by the murk it displaces. Ignored while the murk "
                        + "is flipped overhead.");
        cfg("sableFloatBlocks", "Blocks That Float",
                "The blocks that hold a ship up and how hard each one does, as \"block=strength\" -- wool by "
                        + "default. Strength multiplies the murk's density for that block alone, and may be "
                        + "left off for 1; 0 takes a block back out of a tag that covers it. Only listed "
                        + "blocks lift; everything else on board is weight. Ids, '#tags' and '*' globs.");
        cfg("sableMurkDensity", "Murk Density",
                "How dense the murk is, in Sable's units, where a plain block has mass 1 and volume 1: one "
                        + "submerged float block holds up this much of the ship's mass. Raise it to float a "
                        + "ship on less wool. 0 is no buoyancy.");

        cfg("sableMurkDrag", "Murk Drag",
                "How much of a submerged hull's speed the murk takes per second, at the point where all "
                        + "its float blocks are under. 0 and a ship dropped in bobs forever. Hulls only.");
        cfg("sableMurkSpinDrag", "Murk Spin Drag",
                "The same for a submerged hull's spin -- what stops a ship rolling on once the murk has "
                        + "righted it.");
        cfg("sableBuoyancyProbes", "Hull Sample Limit",
                "How many float blocks of one hull the murk is measured against at most. A ship with more "
                        + "is sampled instead, so this is the cost per ship per physics step, not a size limit.");
        add("fogged.configuration.pillars.tooltip", "Stone towers raised from bedrock to around the murk's high-water mark, in groups. Worldgen: changes here only reach chunks generated afterwards.");
        add("fogged.configuration.layout.tooltip", "Where the groups stand and how often you meet them.");
        add("fogged.configuration.shape.tooltip", "What one tower is built out of, and the range of builds a group draws from.");
        add("fogged.configuration.surface.tooltip", "Everything laid over the bare rock: its bedding, the scree at its foot, and what grows or freezes on it.");
        add("fogged.configuration.plane.tooltip", "The murk's visible surface: its colour, the foam along its waterline, and how it meets the blocks that cross it. Yours alone; a server cannot dictate it.");
        add("fogged.configuration.dither.tooltip", "The pixel dither: how the plane thins out around the camera, and the grain of every dithered edge. Under an Iris shader pack this is also how the whole murk is drawn.");
        add("fogged.configuration.vapor.tooltip", "The cold-vapour layer: stacked mist sheets over the surface, terraced by a noise field so the plane never reads as dead flat.");
        add("fogged.configuration.debug.tooltip", "Ways of seeing what the renderer is doing: raw views of the buffers behind the murk, a readout of its state, and whether compatibility trouble is logged.");
        add("fogged.configuration.group.murkActions.tooltip", "Three switches over everything the murk does to blocks and items, each one governing the list of the same name below.");
        cfg("murkThirstScale", "Thirst Under the Murk",
                "With Thirst Was Taken: how fast a player under the boundary loses water, as a share of "
                        + "the usual rate. The murk is a wet place; 0.25 is a quarter of the usual thirst. "
                        + "1 leaves thirst alone. Does nothing without that mod.");
        cfg("murkColdness", "Cold Under the Murk",
                "With Cold Sweat: how much colder it is for a player under the boundary, as the share of "
                        + "the world's warmth the murk takes away. Warmth is the world temperature's margin "
                        + "above Cold Sweat's minimum habitable temperature, so a sixth takes a sixth of "
                        + "that -- always colder, never below where the world already was. 0 leaves the "
                        + "temperature alone. Does nothing without that mod.");
        cfg("enableExtinguish", "Extinguish Fire",
                "The murk puts fire out under the boundary: fires vanish, lava freezes, torches fall, "
                        + "and the devices below are unlit and emptied of fuel.");
        cfg("snuffedDevices", "Snuffed Devices",
                "Fire-burning devices the murk puts out: each is unlit, its burn timer zeroed and its "
                        + "fuel ejected.\n"
                        + "One block id per entry; '*' matches any run of characters and 'minecraft:' may "
                        + "be left off. An entry for a mod you do not have is simply ignored.\n"
                        + "A device that needs more than unlighting has its own option under Other Mods "
                        + "instead, as Cold Sweat's hearth and boiler do.\n"
                        + "furnace\n"
                        + "create:lit_blaze_burner\n"
                        + "simulated:*_portable_engine");
        cfg("snuffColdSweatDevices", "Smother Cold Sweat's Burners",
                "With Cold Sweat: the murk smothers its hearth and boiler under the boundary -- both are "
                        + "held still, so they neither warm nor burn, and what waits in the fuel slot is "
                        + "thrown out, while the fuel already in the tank keeps. Off leaves them running. "
                        + "Does nothing without that mod.");
        cfg("enableScour", "Scour Blocks",
                "The murk takes things out under the boundary: everything in the list below, broken "
                        + "without drops, and farmland, which reverts and loses what was planted on it.");
        cfg("scourAllPlants", "Wilt Every Plant",
                "Everything that grows goes under the boundary, from any mod, judged by what a block is rather "
                        + "than by its id: flowers, saplings, grasses, crops, mushrooms, vines, leaves. Off leaves "
                        + "the list to name each one.");
        cfg("scouredBlocks", "Scoured Blocks",
                "Extra blocks the murk breaks under the boundary, on top of the built-in scour. Broken "
                        + "without drops.\n"
                        + "One block id per entry; '*' matches any run of characters and a leading '#' "
                        + "names a block tag. 'minecraft:' may be left off.\n"
                        + "cobweb\n"
                        + "#minecraft:banners\n"
                        + "create:*_casing");
        cfg("enableTransforms", "Enable Transforms",
                "Master switch for the transform list. Off, the murk changes nothing into anything and "
                        + "JEI stops listing the conversions; the list itself is kept.");
        cfg("transforms", "Murk Transforms",
                "What the murk turns things into under the boundary -- placed blocks and dropped stacks "
                        + "alike, from one list.\n"
                        + "Syntax: [count] from = [count] to [@depth] [!silent]\n"
                        + "from: a block id, an item id, an id with '*' wildcards, or a '#' tag. "
                        + "'minecraft:' may be left off.\n"
                        + "to: one id. A BLOCK replaces a placed block and keeps the properties they "
                        + "share; an ITEM breaks it and drops instead.\n"
                        + "count: how many a dropped stack gives up, and how many it gets back. 1 to 64. "
                        + "Counts are about stacks: an entry with one does not touch placed blocks, "
                        + "unless it is the number of items a broken block drops.\n"
                        + "@depth: only this far below the surface, in blocks. Moves with the boundary. "
                        + "0 to 512.\n"
                        + "!silent: keep it out of JEI. Everything else is listed there.\n"
                        + "coal_ore=stone !silent\n"
                        + "moss_block=coarse_dirt @50\n"
                        + "4 diamond=2 dirt\n"
                        + "#minecraft:leaves=1 stick !silent\n"
                        + "create:*_casing=mud @20");
        cfg("itemTransforms", "Item Transforms",
                "\"from=to\" swaps for dropped items, by item id. All of them are listed in JEI.");
        cfg("playerSuffocation", "Player Suffocation",
                "Players drown under the boundary: air drains, then drowning damage.");
        cfg("airLossPerTick", "Air Loss Per Tick",
                "Air a player loses per tick, out of 300. Higher = drown faster.");
        cfg("depthScaling", "Depth Scaling",
                "The murk bites harder the deeper you go: air drains faster and breathing spheres shrink.");
        cfg("depthScalingBlocks", "Depth Step",
                "How many blocks below the boundary make up one step of scaling. 0 disables it.");
        cfg("depthScalingPercent", "Depth Step Percent",
                "Per step: air loss up by this percent, nozzle-filter sphere radius down by it. Steps compound.");
        cfg("mobSuffocation", "Mob Suffocation",
                "Non-allowed mobs cannot spawn in the murk and take damage once they linger there.");
        cfg("mobSpawnsInBreathingSpheres", "Mobs Spawn In Breathing Spheres",
                "Nozzle-filter spheres are exempt from the spawn ban: mobs spawn inside them as they would above the boundary.");
        cfg("mobEscape", "Mobs Flee The Murk",
                "Suffocating mobs run for the nearest breathing sphere, or climb towards the boundary.");
        cfg("generatePillars", "Generate Karst Pillars",
                "Raise groups of stone towers from bedrock towards the murk's high-water mark.");
        cfg("pillarBlock", "Pillar Block",
                "Block id the towers are built from. Falls back to stone if the id names nothing.");
        cfg("pillarGroupSpacing", "Pillar Group Spacing",
                "Average distance in blocks between one group of towers and the next. Higher means you meet them far less often.");
        cfg("pillarSpawnGroup", "Group At Spawn",
                "Always place one group within a hundred blocks of the world spawn, however rare they are set to be elsewhere.");
        cfg("pillarRidgeChance", "Pillar Ridges",
                "Share of groups that string their towers along one line, as a ridge, instead of scattering them as a cluster of isles.");
        cfg("pillarRidgeLengthMin", "Shortest Ridge",
                "Shortest a ridge may run, end to end, in blocks.");
        cfg("pillarRidgeLengthMax", "Longest Ridge",
                "Longest a ridge may run, in blocks. A ridge may be longer than the spacing between groups.");
        cfg("pillarIsleRadius", "Isle Cluster Radius",
                "How far from its middle a cluster scatters its isles, in blocks.");
        cfg("pillarDensity", "Isle Density",
                "How thickly a cluster packs its isles. Ridges ignore this -- a crest is continuous.");
        cfg("pillarRadiusMin", "Thinnest Pillar",
                "Radius in blocks of the thinnest tower.");
        cfg("pillarRadiusMax", "Thickest Pillar",
                "Radius in blocks of the thickest tower.");
        cfg("pillarLedges", "Pillar Shelves",
                "Most flat benches a tower may carry on its flanks. These are what greenery grows on.");
        cfg("pillarGreenery", "Pillar Greenery",
                "Grass the level ground on a tower -- its cap and its shelves -- and scatter foliage over it. Sheer faces stay bare.");
        cfg("pillarGreeneryDepth", "Pillar Greenery Depth",
                "How far below the murk's high-water mark greenery still grows. Deeper shelves are left bare.");
        cfg("pillarClimate", "Pillar Climate Dressing",
                "Snow and icicles on cold towers, leaves and vines and bushes on warm wet ones, grass on the rest.");
        cfg("pillarBaseErosion", "Pillar Base Erosion",
                "How far the scree skirt spreads where a tower meets the ground, so it runs into the landscape instead of being stamped through it.");
        cfg("pillarStoneBands", "Pillar Stone Bands",
                "Band the towers with granite, diorite, andesite, tuff and deepslate. The beds roll, dip and thin out rather than running as stripes.");
        cfg("pillarTopMin", "Pillar Top, Lowest",
                "Lowest a tower's top may sit, in blocks relative to the murk's high-water mark: negative "
                        + "is below it, positive above. Each top is rolled between this and the highest.");
        cfg("pillarTopMax", "Pillar Top, Highest",
                "Highest a tower's top may stand above the murk's high-water mark, in blocks. Equal to "
                        + "the lowest tops every tower off at exactly that height.");
        cfg("pillarTilt", "Pillar Lean",
                "Greatest lean off vertical, in degrees. Each tower leans a random amount up to this.");
        cfg("allowedMobs", "Mobs Allowed Under The Fog",
                "Entity-type ids that may live in the murk. The 'minecraft:' namespace may be omitted.");
        cfg("mobSuffocateDelaySeconds", "Mob Suffocation Delay",
                "Seconds a non-allowed mob survives in the murk before it starts taking damage.");
        cfg("mobSuffocateDamage", "Mob Suffocation Damage",
                "Damage dealt each second once the delay is up. 2.0 = one heart.");
        cfg("renderPlane", "Render Separation Plane",
                "Draw the murk surface at the breathing boundary.");
        cfg("planeColor", "Plane Colour",
                "Colour of the murk surface. Its alpha is ignored -- the plane always draws opaque.");
        cfg("foamColor", "Foam Colour",
                "Colour of the foam ring around everything crossing the surface. Alpha scales how strongly it shows.");
        cfg("foamWidth", "Foam Width",
                "How far the foam reaches (in blocks) from every edge. 0 disables it.");
        cfg("foamReach", "Foam Reach",
                "How far above and below the plane something still raises foam. Full strength at the "
                        + "plane, fading to nothing at this distance. 0 restricts the foam to what the "
                        + "plane actually cuts through. The plane sits at a fractional height in its "
                        + "block row, so a reach under about 0.65 never reaches the row below it.");
        cfg("sableFoam", "Sable Sub-Level Foam",
                "Also ring Sable ships and contraptions with foam. No effect without Sable installed.");
        cfg("planeSoftOcclusion", "Soft Occlusion Edge",
                "Dissolve the plane against blocks, mobs, machines and flowing water instead of cutting them "
                        + "hard at the boundary, and open a soft disc around everything crossing it, ships "
                        + "and contraptions included. It reads the scene's depth buffer, which other "
                        + "rendering mods can move or replace, so turn it off first if the murk looks wrong.");
        cfg("murkDarkness", "Murk Darkness",
                "The surface's shadow: how much darker the world under the boundary is drawn, by how deep under "
                        + "it each thing lies, from either side. 0 leaves the lighting alone.");
        cfg("waterlineCellsPerBlock", "Foam Grid Resolution",
                "Cells per block in the foam distance field. Lower is coarser foam and noticeably cheaper.");
        cfg("planeNearDither", "Near-Camera Dither",
                "Dissolve the plane into a pixel dither as the camera comes close, so the surface opens "
                        + "up around the eye instead of snapping in as a hard sheet when the head crosses it.");
        cfg("nearDitherStart", "Dither Start Distance",
                "Inside this many blocks of the camera the plane is thinned all the way to its minimum visibility.");
        cfg("nearDitherEnd", "Dither End Distance",
                "Beyond this many blocks the plane is solid again.");
        cfg("nearDitherMinVisibility", "Dither Minimum Visibility",
                "How much of the plane is left right at the camera: 0 opens it fully, 1 never thins it.");
        cfg("ditherPixelSize", "Dither Pixel Size",
                "Screen pixels per dither cell. 1 is a fine screen-door; larger is chunkier, closer to the foam's look.");
        cfg("stepDither", "Dither Foam Bands",
                "Blend the foam's bands into each other pixel by pixel, in the foam's own grid, instead of a hard line between them.");
        cfg("vaporColorOffsetRed", "Vapour Red Offset",
                "Red added to the plane colour to get the vapour colour.");
        cfg("vaporColorOffsetGreen", "Vapour Green Offset",
                "Green added to the plane colour to get the vapour colour.");
        cfg("vaporColorOffsetBlue", "Vapour Blue Offset",
                "Blue added to the plane colour to get the vapour colour.");
        cfg("vaporStrength", "Vapour Strength",
                "Overall mist alpha. 0 hides the vapour entirely.");
        cfg("vaporSheets", "Vapour Sheets",
                "How many mist sheets are stacked over the plane.");
        cfg("vaporUndulation", "Vapour Undulation",
                "How high (in blocks) the mist sheets rise off the plane. 0 = flat.");
        cfg("vaporUnderside", "Mist Under The Plane",
                "Mirror the mist sheets below the surface too, so the layer reads the same from either side.");
        cfg("vaporUnderwater", "Mist Under Water",
                "Also draw the mist before the water pass, so a lake veils it instead of it floating on top.");
        cfg("debugView", "Render Debug View",
                "Replace the plane with a raw view of one of the buffers behind it.");
        cfg("debugHud", "Debug Readout",
                "Draw the render state (boundary, fade range, depth capture, hole count) in the corner.");
        cfg("logRenderCompatWarnings", "Log Compatibility Warnings",
                "Log once when this mod detects it cannot work with the current rendering setup.");
    }

    /** One config option: its display name and the tooltip both screens show under it. */
    private void cfg(String key, String name, String tooltip) {
        add("fogged.configuration." + key, name);
        add("fogged.configuration." + key + ".tooltip", tooltip);
    }

    /** Display name for a registry path: the listed name if there is one, else the path spelled out. */
    static String name(String path) {
        return NAMES.getOrDefault(path, titleCase(path));
    }

    private static String titleCase(String path) {
        return Arrays.stream(path.split("_"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }
}
