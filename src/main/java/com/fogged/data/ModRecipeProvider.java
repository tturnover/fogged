package com.fogged.data;

import java.util.concurrent.CompletableFuture;

import com.fogged.registry.ModBlocks;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
import net.minecraft.data.recipes.ShapedRecipeBuilder;
import net.minecraft.data.recipes.ShapelessRecipeBuilder;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.Tags;

public class ModRecipeProvider extends RecipeProvider {
    public ModRecipeProvider(PackOutput output, CompletableFuture<HolderLookup.Provider> registries) {
        super(output, registries);
    }

    @Override
    protected void buildRecipes(RecipeOutput recipeOutput) {
        ShapelessRecipeBuilder.shapeless(RecipeCategory.REDSTONE, ModBlocks.FOG_DETECTOR.get())
                .requires(Items.REDSTONE)
                .requires(Tags.Items.STRIPPED_LOGS)
                .requires(Items.STONE)
                .unlockedBy("has_redstone", has(Items.REDSTONE))
                .save(recipeOutput);

        // Iron casing, bars for the grilles front and back, redstone to run the fan.
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModBlocks.AIR_COMPRESSOR.get())
                .pattern("IBI")
                .pattern("ICI")
                .pattern("IRI")
                .define('I', Tags.Items.INGOTS_IRON)
                .define('B', Items.IRON_BARS)
                .define('C', Tags.Items.INGOTS_COPPER)
                .define('R', Tags.Items.STORAGE_BLOCKS_REDSTONE)
                .unlockedBy("has_iron_bars", has(Items.IRON_BARS))
                .save(recipeOutput);

        // Iron sheet round an open middle: six ducts.
        ShapedRecipeBuilder.shaped(RecipeCategory.REDSTONE, ModBlocks.AIR_DUCT.get(), 6)
                .pattern("I I")
                .pattern("I I")
                .pattern("I I")
                .define('I', Tags.Items.INGOTS_IRON)
                .unlockedBy("has_air_compressor", has(ModBlocks.AIR_COMPRESSOR.get()))
                .save(recipeOutput);
    }
}
