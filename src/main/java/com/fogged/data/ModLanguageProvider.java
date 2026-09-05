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
        add("fogged.configuration.plane", "Separation Plane");
        add("fogged.configuration.vapor", "Cold Vapour");
        add("fogged.configuration.debug", "Debug");
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
        add("fogged.configuration.group.murkActions", "What The Murk Does");
        cfg("enableExtinguish", "Extinguish Fire",
                "The murk puts fire out under the boundary: fires vanish, lava freezes, torches fall, "
                        + "and the devices below are unlit and emptied of fuel.");
        cfg("snuffedDevices", "Snuffed Devices",
                "Fire-burning devices the murk puts out: each is unlit, its burn timer zeroed and its "
                        + "fuel ejected, so it cannot keep running under the fog.\n"
                        + "One block id per entry; '*' matches any run of characters and 'minecraft:' may "
                        + "be left off. An entry for a mod you do not have is simply ignored.\n"
                        + "furnace\n"
                        + "create:lit_blaze_burner\n"
                        + "simulated:*_portable_engine");
        cfg("enableScour", "Scour Blocks",
                "The murk takes things out under the boundary: everything in the list below, broken "
                        + "without drops, and farmland, which reverts and loses what was planted on it.");
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
        cfg("sableFoam", "Sable Sub-Level Foam",
                "Also ring Sable ships and contraptions with foam. No effect without Sable installed.");
        cfg("planeSoftOcclusion", "Soft Occlusion Edge (experimental)",
                "Dissolve the plane against blocks, mobs and machines instead of cutting them hard at the "
                        + "boundary, and open a soft disc around each entity crossing it. Experimental: it "
                        + "reads the scene's depth buffer, which other rendering mods can move or replace. "
                        + "Turn it off first if the murk looks wrong.");
        cfg("waterlineCellsPerBlock", "Foam Grid Resolution",
                "Cells per block in the foam distance field. Lower is coarser foam and noticeably cheaper.");
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
