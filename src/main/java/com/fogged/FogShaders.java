package com.fogged;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

// Registers a POSITION_COLOR shader that, unlike vanilla's, samples the world fog uniforms so the
// separation plane fades into the distance with the rest of the world (incl. our thick under-fog).
@EventBusSubscriber(modid = Fogged.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class FogShaders {

    // Set once the shaders have loaded; read by FogPlaneRenderer / FogVapor. Null until loaded.
    public static ShaderInstance FOG_PLANE;
    public static ShaderInstance FOG_VAPOR;
    // Copies the main render target's depth by sampling it (see SceneDepth) instead of glBlitFramebuffer,
    // so it never depends on the source/destination depth formats matching.
    public static ShaderInstance DEPTH_COPY;

    // Monotonic wall clock for the surface boil. The game's per-frame partial tick can slip backward
    // between frames (esp. at uncapped FPS), which made the time-driven noise morph run forward then
    // backward. nanoTime never goes back, so the boil only ever evolves forward. Cosmetic-only, so it
    // is fine that it keeps ticking while paused.
    private static final long ANIM_EPOCH_NS = System.nanoTime();

    public static float animTimeSeconds() {
        return (System.nanoTime() - ANIM_EPOCH_NS) / 1_000_000_000.0F;
    }

    @SubscribeEvent
    static void onRegisterShaders(RegisterShadersEvent event) throws Exception {
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_plane"),
                        DefaultVertexFormat.POSITION_COLOR),
                shader -> FOG_PLANE = shader);
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_vapor"),
                        DefaultVertexFormat.POSITION_COLOR),
                shader -> FOG_VAPOR = shader);
        event.registerShader(
                new ShaderInstance(event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "depth_copy"),
                        DefaultVertexFormat.POSITION),
                shader -> DEPTH_COPY = shader);
    }
}
