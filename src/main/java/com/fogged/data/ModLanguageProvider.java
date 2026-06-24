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
