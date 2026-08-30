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
        add("itemGroup.fogged", "Fogged");

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
        cfg("flipFog", "Flip Fog Side",
                "Put the murk above the boundary instead of below it. Does not move the breathing boundary.");
        cfg("submergeWorld", "Submerge World",
                "The murk drowns what is under it: snuffs fires, freezes lava, drowns torches, wilts plants.");
        cfg("submergeSkip", "Submerge Dead Zone",
                "Blocks directly under the plane that the scour leaves alone.");
        cfg("snuffedDevices", "Snuffed Devices",
                "Block ids of fire-burning devices the murk puts out. '*' matches any run of characters.");
        cfg("playerSuffocation", "Player Suffocation",
                "Players drown under the boundary: air drains, then drowning damage.");
        cfg("airLossPerTick", "Air Loss Per Tick",
                "Air a player loses per tick, out of 300. Higher = drown faster.");
        cfg("depthScaling", "Depth Scaling",
                "Make the murk bite harder the deeper you go.");
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
        cfg("planeSoftOcclusion", "Soft Occlusion Edge",
                "Dissolve the plane against blocks, mobs and machines instead of cutting them hard at the boundary.");
        cfg("distantHorizonsCompat", "Distant Horizons Support",
                "Extend the murk to the LOD horizon and drive DH's fog. Off, the mod ignores Distant Horizons entirely.");
        cfg("distantHorizonsLodCut", "Cut Distant Murk Against LOD",
                "With Distant Horizons installed, let far-off LOD terrain rise through the distant murk instead of being covered by it.");
        cfg("waterlineCellsPerBlock", "Foam Grid Resolution",
                "Cells per block in the foam distance field. Lower is coarser foam and noticeably cheaper.");
        cfg("renderVapor", "Render Cold-Vapour Layer",
                "Stacked mist sheets over the plane, for a liquid-nitrogen look.");
        cfg("vaporColorOffsetRed", "Vapour Red Offset",
                "Red added to the plane colour to get the vapour colour.");
        cfg("vaporColorOffsetGreen", "Vapour Green Offset",
                "Green added to the plane colour to get the vapour colour.");
        cfg("vaporColorOffsetBlue", "Vapour Blue Offset",
                "Blue added to the plane colour to get the vapour colour.");
        cfg("vaporStage", "Vapour Render Stage",
                "Which render stage the mist sheets are drawn at, independently of the murk's.");
        cfg("vaporUnderwater", "Vapour Under Water",
                "Draw a second copy of the mist before the water pass, so it reads as lying beneath a lake's surface.");
        cfg("vaporUnderwaterStage", "Vapour Under-Water Stage",
                "Which stage that second copy is drawn at. Must be before the translucent water pass.");
        cfg("vaporStrength", "Vapour Strength",
                "Overall mist alpha. 0 hides the vapour entirely.");
        cfg("vaporSheets", "Vapour Sheets",
                "How many mist sheets are stacked over the plane.");
        cfg("vaporUndulation", "Vapour Undulation",
                "How high (in blocks) the mist sheets rise off the plane. 0 = flat.");
        cfg("debugView", "Render Debug View",
                "Replace the plane with a raw view of one of the buffers behind it.");
        cfg("planeStage", "Composite Render Stage",
                "Which render stage the murk is composited at \u2014 i.e. what is already in the colour and depth buffers when it runs.");
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
