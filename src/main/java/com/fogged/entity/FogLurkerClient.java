package com.fogged.entity;

import com.fogged.Fogged;
import com.fogged.entity.part.LurkerBodyCenterModel;
import com.fogged.entity.part.LurkerBodyPawsModel;
import com.fogged.entity.part.LurkerHeadModel;
import com.fogged.entity.part.LurkerTailStartModel;
import com.fogged.entity.part.LurkerTailMiddleModel;
import com.fogged.entity.part.LurkerTailEndModel;
import com.fogged.registry.ModEntities;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client-side wiring for the fog lurker: its chain renderer and every piece's model layer. */
@EventBusSubscriber(modid = Fogged.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class FogLurkerClient {
    private FogLurkerClient() {}

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.FOG_LURKER.get(), FogLurkerRenderer::new);
    }

    @SubscribeEvent
    static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LurkerHeadModel.LAYER, LurkerHeadModel::createBodyLayer);
        event.registerLayerDefinition(LurkerBodyPawsModel.LAYER, LurkerBodyPawsModel::createBodyLayer);
        event.registerLayerDefinition(LurkerBodyCenterModel.LAYER, LurkerBodyCenterModel::createBodyLayer);
        event.registerLayerDefinition(LurkerTailStartModel.LAYER, LurkerTailStartModel::createBodyLayer);
        event.registerLayerDefinition(LurkerTailMiddleModel.LAYER, LurkerTailMiddleModel::createBodyLayer);
        event.registerLayerDefinition(LurkerTailEndModel.LAYER, LurkerTailEndModel::createBodyLayer);
    }
}
