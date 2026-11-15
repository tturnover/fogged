package com.fogged;

import com.fogged.FogWalker.SuitFilter;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The fog walker's filter readout, in the place and style of Create's backtank air: the suit, and the
 * time its filter has left before it clogs at the depth the wearer breathes at, flashing red
 * in its last minute. Shown while it is cleaning the murk for them, and wherever they are once it is clogged.
 * A GUI layer just above the air bar, so chat and screens stack over it as they do over the hotbar.
 */
public final class FogWalkerHud {

    static void registerLayer(RegisterGuiLayersEvent event) {
        event.registerAbove(VanillaGuiLayers.AIR_LEVEL,
                ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_walker_filter"), FogWalkerHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.options.hideGui || mc.gameMode == null
                || mc.gameMode.getPlayerMode() == GameType.SPECTATOR || player.isCreative()) {
            return;
        }
        SuitFilter filter = FogWalker.breathingThrough(player);
        if (filter == null) {
            return;
        }
        boolean clogged = FogWalker.choking(player) && Config.synced(Config.PLAYER_SUFFOCATION);
        if (!clogged && !BreathHandler.breathingMurk(player)) {
            return;
        }

        // Create's anchor: right of centre, near the bottom.
        int x = g.guiWidth() / 2 + 90;
        int y = g.guiHeight() - 53;
        // The suit itself, as Create shows the backtank: no bars, the time beside it says it.
        ItemStack icon = FogWalker.suit(player);
        g.renderItem(icon, x, y);

        double wear = FogWalker.wearPerSecond(player, filter);
        if (wear <= 0.0) {
            return; // never clogs: nothing to count down
        }
        int secs = clogged ? 0 : (int) Math.ceil((1.0 - filter.worn()) / wear);
        Component text = Component.literal(secs / 60 + ":" + String.format("%02d", secs % 60));
        int color = 0xFFFFFFFF;
        if (clogged || secs < 60 && (secs & 1) == 0) {
            color = 0xFFFF5555; // red when running low, like Create; solid once it has clogged
        }
        g.drawString(mc.font, text, x + 16, y + 5, color);
    }

    private FogWalkerHud() {}
}
