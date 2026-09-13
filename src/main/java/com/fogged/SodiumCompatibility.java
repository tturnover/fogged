package com.fogged;

import net.neoforged.fml.ModList;

/**
 * What this mod needs to know about Sodium. Sodium is optional: nothing here names a Sodium class --
 * the one place that does is the mixin in {@code fogged.sodium.mixins.json}, which never applies
 * without it -- so this is only the loaded flag and the health check the HUD reads.
 *
 * <p>Why Sodium needs its own hook at all: it {@code @Overwrite}s {@code LevelRenderer
 * .renderSectionLayer} and forwards to {@code SodiumWorldRenderer.drawChunkLayer}, so the murk-fog
 * re-assert LevelRendererMixin puts at that method's head is left injecting into a body Sodium has
 * replaced. Whether it survives comes down to which mixin the transformer applies first, which is not
 * something either mod promises. SodiumChunkLayerMixin claims the fog at Sodium's own draw instead,
 * and the two are harmless together: enforceMurkFog writes the same values twice.
 *
 * <p>Nothing else this mod draws goes through Sodium. The plane, the vapour and the composite are
 * ordinary ShaderInstance draws on the immediate buffers, which Sodium leaves alone, and its terrain
 * shaders read the fog off {@code RenderSystem} exactly as vanilla's do (ChunkShaderFogComponent),
 * with the same smoothstep and the same sphere/cylinder split -- so the murk fogs Sodium's terrain
 * the way it fogs vanilla's once the values are in place.
 */
public final class SodiumCompatibility {

    private static final boolean SODIUM = ModList.get().isLoaded("sodium");

    private SodiumCompatibility() {
    }

    /** True when Sodium is installed, and therefore owns the terrain draw. */
    public static boolean loaded() {
        return SODIUM;
    }

    /**
     * True when Sodium is installed but its terrain hook has never run -- the murk fog is then at the
     * mercy of whichever mixin won on renderSectionLayer. Best-effort, same caveat as MixinHealthCheck:
     * it also reads true before the first terrain draw of the session.
     */
    public static boolean hookMissing() {
        return SODIUM && !MixinHealthCheck.sodiumFogFired;
    }
}
