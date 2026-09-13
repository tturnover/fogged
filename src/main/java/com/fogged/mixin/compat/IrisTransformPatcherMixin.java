package com.fogged.mixin.compat;

import java.util.EnumMap;
import java.util.Map;

import com.fogged.Fogged;
import com.fogged.IrisShaderPatcher;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import net.irisshaders.iris.pipeline.transform.PatchShaderType;

/**
 * Hands the pack's fragment source for the programs this mod draws through to IrisShaderPatcher, as
 * Iris' transform finishes with it and before ShaderCreator compiles it. The patcher test-compiles
 * what it made before it is handed on, and the original goes through untouched when that fails, so a
 * pack this cannot handle keeps loading -- Iris compiles the map's contents later, where a failure
 * would sink the whole pack.
 *
 * <p>On {@code patchVanilla} itself rather than on its call site inside {@code ShaderCreator.create},
 * which is where this used to sit. Naming a call site means naming the callee's full descriptor --
 * eleven parameter types written out as a string -- and any one of them changing between Iris builds
 * silently stops the injector matching. Here the only thing named is the method, and {@code @Local}
 * picks the program name out of the arguments by being the first String among them, so the other ten
 * can come and go. {@code patchVanilla} is Iris' entry point for its vanilla-shader replacements and
 * nothing else calls it, so the reach is exactly what it was (the pack's Sodium and composite
 * programs go through patchSodium / patchComposite, which this leaves alone).
 *
 * <p>A fresh map is returned because the transform keeps an LRU cache of these and may hand back the
 * entry itself; writing into it would patch the cached copy for every later caller.
 *
 * <p>Lives in {@code fogged.iris.mixins.json}, which is not required: with Iris absent the target
 * class is never loaded and this never applies.
 */
@Mixin(targets = "net.irisshaders.iris.pipeline.transform.TransformPatcher", remap = false)
public abstract class IrisTransformPatcherMixin {

    @ModifyReturnValue(method = "patchVanilla", at = @At("RETURN"), remap = false)
    private static Map<PatchShaderType, String> fogged$patchFragment(Map<PatchShaderType, String> transformed,
            @Local(argsOnly = true, ordinal = 0) String name) {
        if (!IrisShaderPatcher.patches(name) || transformed == null) {
            return transformed;
        }
        try {
            String patched = IrisShaderPatcher.patchFragment(transformed.get(PatchShaderType.FRAGMENT));
            if (patched == null) {
                return transformed;
            }
            Map<PatchShaderType, String> copy = new EnumMap<>(PatchShaderType.class);
            copy.putAll(transformed);
            copy.put(PatchShaderType.FRAGMENT, patched);
            Fogged.LOGGER.info("Fogged: spliced the murk into the shader pack's '{}' program", name);
            return copy;
        } catch (Throwable t) {
            Fogged.LOGGER.warn("Fogged: could not patch the shader pack's '{}' program ({}); the plane "
                    + "will draw through it flat and the vapour not at all", name, t.toString());
            return transformed;
        }
    }
}
