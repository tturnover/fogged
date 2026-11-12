package com.fogged;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * A look-alike of Create's backtank air indicator, drawn at our dry breathing boundary. Create's own
 * diving-gear overlay (RemainingAirOverlay) bails whenever the eye is in air, so it never shows below
 * the boundary even though the backtank is feeding the player air server-side; {@link #onRenderGui}
 * draws one in exactly that case. Vanilla's own air bubbles already show up on their own (they trigger
 * off {@code getAirSupply() < getMaxAirSupply()}, not off real water), so there is no need to draw
 * those. Reads the backtank's air via its registered data component, so there is no compile dependency
 * on Create -- if it isn't installed the component is absent and nothing renders.
 *
 * <p>Client only, and kept apart from {@link CreateCompatibility}, which the nozzle filter calls on a
 * dedicated server.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class CreateAirHud {

    // Create registers this Integer component under "create:banktank_air" (its own spelling). It is
    // network-synchronised, so the value is present on the client.
    private static final ResourceLocation BACKTANK_AIR_ID =
            ResourceLocation.fromNamespaceAndPath("create", "banktank_air");

    private static final EquipmentSlot[] ARMOUR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    @SubscribeEvent
    static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.gameMode == null || mc.gameMode.getPlayerMode() == GameType.SPECTATOR) {
            return;
        }
        LocalPlayer player = mc.player;
        if (player == null || player.isCreative() || !belowBoundaryInAir(player)) {
            return;
        }

        // Vanilla's own air bubbles already show whenever getAirSupply() < getMaxAirSupply(), regardless
        // of whether the eye is in real water -- BreathHandler draining the air stat is enough to trigger
        // them on its own. Drawing a second copy here only doubled up and drifted out of sync with it.

        // Backtank-air indicator where Create's would be (only while gear is feeding air).
        int air = feedingAir(player);
        if (air > 0) {
            renderIndicator(event.getGuiGraphics(), mc.font, displayTank(player), air);
        }
    }

    private static void renderIndicator(GuiGraphics g, Font font, ItemStack tank, int air) {
        // Same anchor Create uses: right of centre, near the bottom; nudged down for a fire-proof tank.
        boolean fireproof = tank.has(DataComponents.FIRE_RESISTANT);
        int x = g.guiWidth() / 2 + 90;
        int y = g.guiHeight() - 53 + (fireproof ? 9 : 0);

        g.renderItem(tank, x, y);

        // Backtank drains 1 air per 20 ticks (~1s), so the air count is roughly the seconds remaining.
        int secs = Math.max(0, air - 1);
        Component text = Component.literal(secs / 60 + ":" + String.format("%02d", secs % 60));
        int color = 0xFFFFFFFF;
        if (secs < 60 && (secs & 1) == 0) {
            color = 0xFFFF5555; // flash red when running low, like Create
        }
        g.drawString(font, text, x + 16, y + 5, color);
    }

    // Total backtank air the player is breathing from right now, or 0 if our indicator shouldn't show:
    // not creative/spectator, eye in air (Create's own HUD covers real fluid), below the boundary, and
    // wearing a Create diving helmet over a backtank that still has air.
    private static int feedingAir(LocalPlayer player) {
        if (!belowBoundaryInAir(player) || !isDivingHelmet(player.getItemBySlot(EquipmentSlot.HEAD))) {
            return 0;
        }
        DataComponentType<?> airType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(BACKTANK_AIR_ID).orElse(null);
        if (airType == null) {
            return 0; // Create not installed
        }
        int total = 0;
        for (EquipmentSlot slot : ARMOUR) {
            total += airOf(player.getItemBySlot(slot), airType);
        }
        return total;
    }

    // True when the player is below the breathing boundary with the eye in air (our fake-underwater
    // zone) -- where vanilla suppresses the air bar. Creative players are exempt.
    private static boolean belowBoundaryInAir(LocalPlayer player) {
        return player != null
                && !player.isCreative()
                && player.getEyeInFluidType().isAir()
                && Config.eyesUnderSurface(player.level(), player.getEyeY())
                && !BreatheSpheres.isBreathable(player.level(), player.getEyePosition());
    }

    // The backtank icon to draw: the first worn item that still has air.
    private static ItemStack displayTank(LocalPlayer player) {
        DataComponentType<?> airType = BuiltInRegistries.DATA_COMPONENT_TYPE.getOptional(BACKTANK_AIR_ID).orElse(null);
        if (airType != null) {
            for (EquipmentSlot slot : ARMOUR) {
                ItemStack stack = player.getItemBySlot(slot);
                if (airOf(stack, airType) > 0) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    private static boolean isDivingHelmet(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id.getNamespace().equals("create") && id.getPath().contains("diving_helmet");
    }

    private static int airOf(ItemStack stack, DataComponentType<?> airType) {
        if (stack.isEmpty()) {
            return 0;
        }
        Object value = stack.get(airType);
        return value instanceof Integer i ? i : 0;
    }

    private CreateAirHud() {
    }
}
