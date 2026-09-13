package com.fogged.mixin.compat;

import com.fogged.FogModifier;
import com.fogged.MixinHealthCheck;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re-asserts the murk fog at Sodium's terrain draw, the way LevelRendererMixin does at vanilla's.
 *
 * <p>Sodium {@code @Overwrite}s {@code LevelRenderer.renderSectionLayer} to call this method, so the
 * vanilla-side re-assert is injecting into a body that may no longer exist by the time the transformer
 * is done -- see SodiumCompatibility. This one is on the method Sodium actually draws from, which is
 * past every ordering question. Sodium's chunk shaders take their fog straight off {@code RenderSystem}
 * (ChunkShaderFogComponent.Smooth.setup), so writing it here is all that is needed.
 *
 * <p>Lives in {@code fogged.sodium.mixins.json}, which is not required: with Sodium absent the target
 * class is never loaded and this never applies.
 */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public abstract class SodiumChunkLayerMixin {

    @Inject(method = "drawChunkLayer", at = @At("HEAD"), remap = false, require = 0)
    private void fogged$enforceMurkFog(CallbackInfo ci) {
        MixinHealthCheck.sodiumFogFired = true;
        FogModifier.enforceMurkFog();
        FogModifier.sampleTerrainFog("sodium");
    }
}
