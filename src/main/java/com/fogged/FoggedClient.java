package com.fogged;

import com.fogged.registry.ModItems;
import com.fogged.registry.ModParticles;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
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
        modEventBus.addListener(FoggedClient::registerAdditionalModels);
        modEventBus.addListener(FoggedClient::modifyBakingResult);
        modEventBus.addListener(FoggedClient::registerClientExtensions);
    }

    // Bind the colour-tintable puff particle to its renderer (reuses the vanilla POOF sprites).
    private static void registerParticleProviders(RegisterParticleProvidersEvent event) {
        event.registerSpriteSet(ModParticles.PUFF.get(), PuffParticle.Provider::new);
    }

    // Give the puff ball its steady raise-to-mouth pose while breathing (see PuffBallHandTransform).
    private static void registerClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(new PuffBallHandTransform(), ModItems.PUFF_BALL.get());
    }

    // The puff ball's flat GUI icon. Side-loaded because nothing references it as a model parent; the
    // 3D item/puff_ball model is the item's real model and PuffBallModel swaps to this one in the GUI.
    private static final ModelResourceLocation PUFF_BALL_GUI =
            ModelResourceLocation.standalone(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "item/puff_ball_gui"));

    // The 3 puff-bush-leaves looks (plain, rare, rare mirrored), side-loaded standalone so
    // PuffBushLeavesModel can pick between them itself instead of via the blockstate's position hash.
    private static final ModelResourceLocation PUFF_BUSH_LEAVES_PLAIN = ModelResourceLocation
            .standalone(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "block/puff_bush_leaves"));
    private static final ModelResourceLocation PUFF_BUSH_LEAVES_RARE = ModelResourceLocation
            .standalone(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "block/puff_bush_leaves_rare"));
    private static final ModelResourceLocation PUFF_BUSH_LEAVES_RARE_MIRRORED = ModelResourceLocation
            .standalone(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "block/puff_bush_leaves_rare_m"));

    private static void registerAdditionalModels(ModelEvent.RegisterAdditional event) {
        event.register(PUFF_BALL_GUI);
        event.register(PUFF_BUSH_LEAVES_PLAIN);
        event.register(PUFF_BUSH_LEAVES_RARE);
        event.register(PUFF_BUSH_LEAVES_RARE_MIRRORED);
    }

    // Wrap the puff ball's baked model so it renders flat in the GUI and 3D everywhere else, and every
    // puff_bush_leaves blockstate combo (LeavesBlock has distance/persistent/waterlogged properties, so
    // there are many) so it picks rare-vs-mirrored by Y parity instead of by position hash.
    private static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
        ModelResourceLocation itemModel =
                ModelResourceLocation.inventory(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "puff_ball"));
        BakedModel model3d = event.getModels().get(itemModel);
        BakedModel gui = event.getModels().get(PUFF_BALL_GUI);
        if (model3d != null && gui != null) {
            event.getModels().put(itemModel, new PuffBallModel(model3d, gui));
        }

        BakedModel leavesPlain = event.getModels().get(PUFF_BUSH_LEAVES_PLAIN);
        BakedModel leavesRare = event.getModels().get(PUFF_BUSH_LEAVES_RARE);
        BakedModel leavesRareMirrored = event.getModels().get(PUFF_BUSH_LEAVES_RARE_MIRRORED);
        if (leavesPlain != null && leavesRare != null && leavesRareMirrored != null) {
            ResourceLocation leavesBlockId = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "puff_bush_leaves");
            PuffBushLeavesModel wrapper = new PuffBushLeavesModel(leavesPlain, leavesRare, leavesRareMirrored);
            for (ModelResourceLocation loc : event.getModels().keySet()) {
                if (loc.id().equals(leavesBlockId)) {
                    event.getModels().put(loc, wrapper);
                }
            }
        }
    }
}
