package com.fogged;

import com.fogged.registry.ModParticles;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Fogged.MODID, dist = Dist.CLIENT)
public class FoggedClient {
    public FoggedClient(IEventBus modEventBus, ModContainer container) {
        // Rendering config is client-only: it never reaches a server and a server never overrides it.
        container.registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_SPEC);

        // Config screen, reached from the Mods screen. YACL when it is installed, NeoForge's own
        // screen otherwise -- both edit the same specs, so neither is the source of truth.
        container.registerExtensionPoint(IConfigScreenFactory.class, FoggedClient::configScreen);

        modEventBus.addListener(FoggedClient::registerParticleProviders);
        modEventBus.addListener(FoggedClient::modifyBakingResult);
    }

    // YACL is optional: the ModList check stays here so YaclCompatibility (and every YACL class it
    // names) is only ever loaded when YACL is actually installed. Same isolation as SableCompatibility.
    private static Screen configScreen(ModContainer container, Screen parent) {
        if (ModList.get().isLoaded("yet_another_config_lib_v3")) {
            Screen screen = YaclCompatibility.screen(parent);
            if (screen != null) {
                return screen;
            }
        }
        return new ConfigurationScreen(container, parent);
    }

    // Bind the colour-tintable puff particle to its renderer (reuses the vanilla POOF sprites). The
    // nozzle filter emits it white above the fog plane and foam-coloured below.
    private static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.PUFF.get(), PuffParticle.Provider::new);
    }

    // Wrap the fog-detector / extension models so their pole retextures to the column's chosen log.
    private static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ResourceLocation detector = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_detector");
        ResourceLocation extension = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_detector_extension");
        for (var entry : event.getModels().entrySet()) {
            ModelResourceLocation loc = entry.getKey();
            if (loc.id().equals(detector) || loc.id().equals(extension)) {
                entry.setValue(new FogDetectorLogModel(entry.getValue()));
            }
        }
    }
}
