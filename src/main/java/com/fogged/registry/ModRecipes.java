package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.MurkTransformRecipe;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The murk's conversions as datapack files: one recipe type, so that what the murk turns things into
 * can be shipped by another mod or by a pack instead of being typed into the config by hand.
 *
 * <p>A recipe rather than a registry of our own because of what a recipe already does: it reloads on
 * {@code /reload}, it is sent to every client that joins, and JEI is built to read it -- all three of
 * which the murk's conversions want, since they are shown in JEI and have to agree between a server
 * and the clients looking at them.
 */
public final class ModRecipes {
    private ModRecipes() {}

    public static final DeferredRegister<RecipeType<?>> TYPES =
            DeferredRegister.create(Registries.RECIPE_TYPE, Fogged.MODID);
    public static final DeferredRegister<RecipeSerializer<?>> SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, Fogged.MODID);

    /** {@code data/<namespace>/recipe/<name>.json} with {@code "type": "fogged:murk_transform"}. */
    public static final DeferredHolder<RecipeType<?>, RecipeType<MurkTransformRecipe>> MURK_TRANSFORM =
            TYPES.register("murk_transform", id -> RecipeType.simple(id));

    public static final DeferredHolder<RecipeSerializer<?>, MurkTransformRecipe.Serializer> MURK_TRANSFORM_SERIALIZER =
            SERIALIZERS.register("murk_transform", id -> new MurkTransformRecipe.Serializer());

    public static void register(IEventBus modEventBus) {
        TYPES.register(modEventBus);
        SERIALIZERS.register(modEventBus);
    }
}
