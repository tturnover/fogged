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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

// Create's diving-gear HUD (RemainingAirOverlay) bails whenever the eye is in air, so it never shows
// at our dry breathing boundary even though the backtank is feeding the player air server-side. This
// draws a look-alike indicator in exactly that case (below the boundary, in air, wearing a Create
// diving helmet over a backtank with air), alongside the normal vanilla air bubbles.
// Reads the backtank's air via the registered data component, so there is no compile dependency on
// Create -- if Create isn't installed the component is absent and nothing renders.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class BacktankAirOverlay {

    // Create registers this Integer component under "create:banktank_air" (its own spelling). It is
    // network-synchronised, so the value is present on the client.
    private static final ResourceLocation BACKTANK_AIR_ID =
            ResourceLocation.fromNamespaceAndPath("create", "banktank_air");

    private static final EquipmentSlot[] ARMOUR = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };

    // Vanilla air-bubble HUD sprites.
    private static final ResourceLocation AIR_SPRITE = ResourceLocation.withDefaultNamespace("hud/air");
    private static final ResourceLocation AIR_BURSTING_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/air_bursting");

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

        // Vanilla won't draw air bubbles unless the eye is in real water, so draw them ourselves to
        // sell the "underwater" feel below the boundary.
        renderAirBubbles(event.getGuiGraphics(), player);

        // Backtank-air indicator where Create's would be (only while gear is feeding air).
        int air = feedingAir(player);
        if (air > 0) {
            renderIndicator(event.getGuiGraphics(), mc.font, displayTank(player), air);
        }
    }

    // Mirror of vanilla Gui#renderAirLevel: a right-aligned row of up to 10 bubbles for the air supply.
    private static void renderAirBubbles(GuiGraphics g, LocalPlayer player) {
        int air = Math.max(0, player.getAirSupply());
        int max = player.getMaxAirSupply();
        int full = Mth.ceil((air - 2) * 10.0 / max);
        int bursting = Mth.ceil(air * 10.0 / max) - full;
        int left = g.guiWidth() / 2 + 91;
        int top = g.guiHeight() - 49;
        for (int i = 0; i < full + bursting; i++) {
            g.blitSprite(i < full ? AIR_SPRITE : AIR_BURSTING_SPRITE, left - i * 8 - 9, top, 9, 9);
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
                && player.getEyeY() < Config.breathHeight(player.level()) + Config.PLANE_SURFACE_OFFSET;
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

    private BacktankAirOverlay() {
    }
}
