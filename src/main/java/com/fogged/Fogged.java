package com.fogged;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import com.fogged.registry.ModBlockEntities;
import com.fogged.registry.ModBlocks;
import com.fogged.registry.ModCreativeTabs;
import com.fogged.registry.ModEntities;
import com.fogged.registry.ModItems;

import net.neoforged.bus.api.IEventBus;
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
        ModCreativeTabs.register(modEventBus);
        ModBlockEntities.register(modEventBus);
        ModEntities.register(modEventBus);

        // Register our mod's ModConfigSpec so that FML can create and load the config file for us
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
    }
}
