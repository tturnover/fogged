package com.fogged;

import com.fogged.registry.ModParticles;

import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = Fogged.MODID, dist = Dist.CLIENT)
public class FoggedClient {
    public FoggedClient(IEventBus modEventBus, ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        modEventBus.addListener(FoggedClient::registerParticleProviders);
        modEventBus.addListener(FoggedClient::modifyBakingResult);
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
