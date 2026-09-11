package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.KarstPillarsFeature;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Worldgen features. {@code karst_pillars} raises groups of stone towers from bedrock to around the
 * murk's high-water mark; see {@link KarstPillarsFeature}.
 *
 * <p>The feature takes no configuration of its own -- it reads {@code Config} instead -- so the
 * datapack side is a single fixed configured/placed feature pair under
 * {@code data/fogged/worldgen}, added to the overworld by a NeoForge biome modifier.
 */
public final class ModFeatures {
    public static final DeferredRegister<Feature<?>> FEATURES =
            DeferredRegister.create(Registries.FEATURE, Fogged.MODID);

    public static final DeferredHolder<Feature<?>, KarstPillarsFeature> KARST_PILLARS =
            FEATURES.register("karst_pillars", () -> new KarstPillarsFeature(NoneFeatureConfiguration.CODEC));

    public static void register(IEventBus modEventBus) {
        FEATURES.register(modEventBus);
    }

    private ModFeatures() {}
}
