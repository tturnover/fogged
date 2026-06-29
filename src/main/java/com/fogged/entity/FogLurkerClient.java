package com.fogged.entity;

import com.fogged.Fogged;
import com.fogged.entity.part.LurkerHeadModel;
import com.fogged.entity.part.LurkerSegmentAModel;
import com.fogged.entity.part.LurkerSegmentBModel;
import com.fogged.entity.part.LurkerSegmentCModel;
import com.fogged.entity.part.LurkerTailModel;
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
        event.registerLayerDefinition(LurkerSegmentAModel.LAYER, LurkerSegmentAModel::createBodyLayer);
        event.registerLayerDefinition(LurkerSegmentBModel.LAYER, LurkerSegmentBModel::createBodyLayer);
        event.registerLayerDefinition(LurkerSegmentCModel.LAYER, LurkerSegmentCModel::createBodyLayer);
        event.registerLayerDefinition(LurkerTailModel.LAYER, LurkerTailModel::createBodyLayer);
    }
}
