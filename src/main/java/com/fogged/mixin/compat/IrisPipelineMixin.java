package com.fogged.mixin.compat;

import com.fogged.MurkComposite;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the murk's screen-space fog once Iris has finished the world frame -- its final program has
 * written the image to the main render target, and it has stopped overriding this mod's shaders.
 * See MurkComposite for why the fog has to be laid on this way under a pack.
 *
 * <p>Lives in {@code fogged.iris.mixins.json}, which is not required: with Iris absent the target
 * class is never loaded and this never applies.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPipelineMixin {

    @Inject(method = "finalizeLevelRendering", at = @At("TAIL"), remap = false)
    private void fogged$murkOverFrame(CallbackInfo ci) {
        MurkComposite.renderUnderPack();
    }
}
