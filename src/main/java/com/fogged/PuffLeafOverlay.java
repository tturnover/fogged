package com.fogged;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * TEMPORARY placeholder screen overlay for standing with your head inside a puff bush leaf pocket.
 * Copies vanilla's powder-snow screen overlay (same blit call, same vanilla texture) but skips the
 * gradual fade vanilla drives off {@code getPercentFrozen()} -- this just snaps on/off at a fixed
 * alpha, per spec ("without gradual appearance just toggle on off").
 *
 * <p><b>How to change it later:</b>
 * <ul>
 *   <li>Different look entirely: replace {@link #TEXTURE} with your own {@code ResourceLocation}
 *       (any full-screen PNG works with the same blit call below).</li>
 *   <li>Stronger/weaker tint: change {@link #ALPHA} (0..1).</li>
 *   <li>Different trigger: edit the condition in {@link #onRenderGui} -- it currently mirrors
 *       {@link BreathHandler}'s leaf-pocket exemption ({@link PuffLeafAir#isBreathable} and not
 *       actually underwater).</li>
 *   <li>Remove it: delete this class (nothing else references it; the {@code @EventBusSubscriber}
 *       annotation is how it gets picked up, so no other file needs editing).</li>
 * </ul>
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
final class PuffLeafOverlay {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/powder_snow_outline.png");
    private static final float ALPHA = 1.0F;

    @SubscribeEvent
    static void onRenderGui(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui) {
            return;
        }
        if (player.isEyeInFluid(FluidTags.WATER)
                || !PuffLeafAir.isBreathable(player.level(), player.getEyePosition())) {
            return;
        }

        GuiGraphics g = event.getGuiGraphics();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        g.setColor(1.0F, 1.0F, 1.0F, ALPHA);
        g.blit(TEXTURE, 0, 0, -90, 0.0F, 0.0F, g.guiWidth(), g.guiHeight(), g.guiWidth(), g.guiHeight());
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    private PuffLeafOverlay() {}
}
