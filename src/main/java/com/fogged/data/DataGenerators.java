package com.fogged.data;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.fogged.Fogged;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.minecraft.data.loot.LootTableProvider;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;

/**
 * Single entry point for data generation. Iterates the {@code ModBlocks} / {@code ModItems}
 * registers and produces every asset/data folder + JSON link:
 * <ul>
 *   <li>blockstates + block/item models — {@link ModBlockStateProvider}, {@link ModItemModelProvider}</li>
 *   <li>block loot tables — {@link ModBlockLoot}</li>
 *   <li>{@code en_us} / {@code uk_ua} lang — {@link ModLanguageProvider}, {@link ModLanguageProviderUk}</li>
 * </ul>
 * Run with {@code ./gradlew runData}; output lands in {@code src/generated/resources}.
 */
@EventBusSubscriber(modid = Fogged.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class DataGenerators {
    @SubscribeEvent
    public static void gatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper exFileHelper = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        // Client-side assets: models, blockstates, lang.
        generator.addProvider(event.includeClient(), new ModBlockStateProvider(output, exFileHelper));
        generator.addProvider(event.includeClient(), new ModItemModelProvider(output, exFileHelper));
        generator.addProvider(event.includeClient(), new ModLanguageProvider(output));
        generator.addProvider(event.includeClient(), new ModLanguageProviderUk(output));

        // Server-side data: loot tables, recipes.
        generator.addProvider(event.includeServer(), new LootTableProvider(
                output,
                Set.of(),
                List.of(new LootTableProvider.SubProviderEntry(ModBlockLoot::new, LootContextParamSets.BLOCK)),
                lookup));
        generator.addProvider(event.includeServer(), new ModRecipeProvider(output, lookup));
    }

    private DataGenerators() {}
}
