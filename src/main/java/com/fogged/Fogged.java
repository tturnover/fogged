package com.fogged;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.fogged.registry.ModBlockEntities;
import com.fogged.registry.ModBlocks;
import com.fogged.registry.ModFeatures;
import com.fogged.registry.ModItems;
import com.fogged.registry.ModParticles;
import com.fogged.registry.ModStructurePieces;
import com.fogged.registry.ModStructures;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(Fogged.MODID)
public class Fogged {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "fogged";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();

    // The constructor for the mod class is the first code that is run when your mod is loaded.
    // FML will recognize some parameter types like IEventBus or ModContainer and pass them in automatically.
    public Fogged(IEventBus modEventBus, ModContainer modContainer) {
        // Registries are declared in one place each (ModBlocks / ModItems); datagen reads them.
        ModBlocks.register(modEventBus);
        ModItems.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModParticles.register(modEventBus);
        ModFeatures.register(modEventBus);
        ModStructures.register(modEventBus);
        ModStructurePieces.register(modEventBus);

        // Gameplay config only. Everything the client draws lives in a CLIENT spec registered from
        // FoggedClient, so a server never dictates someone's visuals.
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.COMMON_SPEC);

        // Cold Sweat is optional; its classes are named only inside ColdSweatCompatibility, which this
        // check keeps from ever loading without it.
        if (ModList.get().isLoaded("cold_sweat")) {
            ColdSweatCompatibility.register(NeoForge.EVENT_BUS);
        }
    }
}
