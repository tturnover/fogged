package com.fogged;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.fogged.registry.ModRecipes;

import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/**
 * Hands the {@link MurkTransformRecipe} files to {@link ScourRules} whenever they change, on either
 * side: the server rereads them with the rest of a datapack reload, and a client is told by the recipe
 * sync that follows it (and the one it gets on joining). Both sides need them -- the server to work on
 * the world, the client for what JEI shows -- and both learn about them the way recipes are already
 * delivered, which is the reason the conversions are recipes at all.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class MurkTransformLoader {
    private MurkTransformLoader() {}

    @SubscribeEvent
    static void onAddReloadListener(AddReloadListenerEvent event) {
        ReloadableServerResources resources = event.getServerResources();
        // Added after the vanilla listeners, so the recipe manager has finished rebuilding by the time
        // this runs -- there is nothing to prepare, only something to read once it has.
        event.addListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparableReloadListener.PreparationBarrier barrier,
                    ResourceManager manager, ProfilerFiller prepareProfiler, ProfilerFiller applyProfiler,
                    Executor prepareExecutor, Executor applyExecutor) {
                return barrier.wait(null).thenRunAsync(
                        () -> apply(resources.getRecipeManager()), applyExecutor);
            }

            @Override
            public String getName() {
                return "fogged:murk_transforms";
            }
        });
    }

    @EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
    static final class ClientSide {
        private ClientSide() {}

        // Highest priority: JEI starts off this same event, and it asks ScourRules what to show. Left
        // at the default the two would race, and a join could land JEI with the conversions the last
        // world had.
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        static void onRecipesUpdated(RecipesUpdatedEvent event) {
            apply(event.getRecipeManager());
        }
    }

    // A world that is done with takes its datapack's conversions with it, so the next one does not
    // start out holding rules nothing in it defines.
    @SubscribeEvent
    static void onServerStopped(ServerStoppedEvent event) {
        ScourRules.setDataTransforms(List.of());
    }

    private static void apply(RecipeManager recipes) {
        List<String> entries = new ArrayList<>();
        for (RecipeHolder<MurkTransformRecipe> holder : recipes.getAllRecipesFor(ModRecipes.MURK_TRANSFORM.get())) {
            entries.add(holder.value().toEntry());
        }
        ScourRules.setDataTransforms(entries);
        if (!entries.isEmpty()) {
            Fogged.LOGGER.debug("Fogged: {} murk transforms from datapacks", entries.size());
        }
    }
}
