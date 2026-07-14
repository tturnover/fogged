package com.fogged.data;

import java.util.Arrays;
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
    public ModLanguageProvider(PackOutput output) {
        super(output, Fogged.MODID, "en_us");
    }

    @Override
    protected void addTranslations() {
        addConfigTranslations();

        add("entity.fogged.fog_lurker", "Fog Lurker");

        // Create-style Fogged tooltips (see FoggedTooltip / FoggedTooltips). The "Hold [Shift]" prompt
        // reuses Create's own lang keys at runtime, so only the description bodies live here.
        add("fogged.tooltip.nozzle.desc1", "Apply _3 Fluff Balls_ to filter out _thick fog_.");
        add("fogged.tooltip.nozzle.desc2", "An _Encased Fan's_ speed controls the _breathable area_.");
        add("fogged.tooltip.fog_detector.desc1", "Emits a _redstone signal_ by how deep it sits under the fog.");
        add("fogged.tooltip.fog_detector.desc2", "Right-click with _another_ to stack it taller.");

        // Custom death messages (keyed by each damage type's message_id).
        add("death.attack.fog_suffocation", "%1$s was swallowed by the fog");
        add("death.attack.fog_suffocation.player", "%1$s drowned in the fog while fleeing %2$s");
        add("death.attack.fog_lurker", "%1$s was dragged into the murk by %2$s");

        ModBlocks.BLOCKS.getEntries()
                .forEach(holder -> add(holder.get(), titleCase(holder.getId().getPath())));

        ModItems.ITEMS.getEntries().forEach(holder -> {
            if (holder.get() instanceof BlockItem) {
                return; // shares the block's translation key
            }
            add(holder.get(), titleCase(holder.getId().getPath()));
        });
    }

    /** Config-screen translation keys. Owned here so en_us.json has a single generated source. */
    private void addConfigTranslations() {
        add("itemGroup.fogged", "Fogged");
        add("fogged.configuration.title", "Fogged Configs");
        add("fogged.configuration.section.fogged.common.toml", "Fogged Configs");
        add("fogged.configuration.section.fogged.common.toml.title", "Fogged Configs");
        add("fogged.configuration.breathHeight", "Breathing Boundary Height");
        add("fogged.configuration.airLossPerTick", "Air Loss Per Tick");
        add("fogged.configuration.fogDistance", "Under-Plane Fog Distance");
        add("fogged.configuration.renderPlane", "Render Separation Plane");
        add("fogged.configuration.planeColor", "Plane Colour (hex RGBA)");
        add("fogged.configuration.foamColor", "Foam Colour (hex RGBA)");
        add("fogged.configuration.foamWidth", "Foam Width");
        add("fogged.configuration.sableFoam", "Sable Sub-Level Foam");
    }

    private static String titleCase(String path) {
        return Arrays.stream(path.split("_"))
                .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                .collect(Collectors.joining(" "));
    }
}
