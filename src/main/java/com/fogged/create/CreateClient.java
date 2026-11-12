package com.fogged.create;

import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/** Client half of {@link CreateContent}; reached from FoggedClient only when Create is loaded. */
public final class CreateClient {

    private CreateClient() {}

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(CreateContent.KINETIC_AIR_COMPRESSOR_BE.get(), KineticAirCompressorRenderer::new);
    }
}
