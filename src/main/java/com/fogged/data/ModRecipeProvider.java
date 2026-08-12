package com.fogged.data;

import java.util.concurrent.CompletableFuture;

import com.fogged.registry.ModBlocks;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.recipes.RecipeCategory;
import net.minecraft.data.recipes.RecipeOutput;
import net.minecraft.data.recipes.RecipeProvider;
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
    }
}
