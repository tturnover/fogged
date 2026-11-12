package com.fogged;

import com.fogged.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * A used intake filter's pollution ({@link ModDataComponents#POLLUTION}) out of the intake: it stacks
 * with filters as dirty, and dropped onto a stack of the same filter at another step the two merge at
 * the dirtier one; it shows as a bar that fills in the slot, in the grime colour, and as a tooltip line.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FilterPollution {

    /** The step of {@code stack}: 0 clean. */
    public static int step(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.POLLUTION.get(), 0);
    }

    // A whole stack dropped onto the same filter at another pollution step: merged, at the dirtier step.
    // Both sides run it, as the click itself does.
    @SubscribeEvent
    static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        ItemStack carried = event.getCarriedItem();
        ItemStack target = event.getStackedOnItem();
        if (event.getClickAction() != ClickAction.PRIMARY || carried.isEmpty() || target.isEmpty()
                || carried.getItem() != target.getItem() || ItemStack.isSameItemSameComponents(carried, target)
                || (step(carried) == 0 && step(target) == 0)) {
            return;
        }
        ItemStack a = carried.copy();
        a.remove(ModDataComponents.POLLUTION.get());
        ItemStack b = target.copy();
        b.remove(ModDataComponents.POLLUTION.get());
        Slot slot = event.getSlot();
        if (!ItemStack.isSameItemSameComponents(a, b) || !slot.mayPlace(carried)) {
            return;
        }
        int room = Math.min(slot.getMaxStackSize(target), target.getMaxStackSize()) - target.getCount();
        int move = Math.min(carried.getCount(), room);
        if (move <= 0) {
            return;
        }
        ItemStack merged = target.copyWithCount(target.getCount() + move);
        merged.set(ModDataComponents.POLLUTION.get(), Math.max(step(carried), step(target)));
        slot.set(merged);
        SlotAccess cursor = event.getCarriedSlotAccess();
        cursor.set(carried.copyWithCount(carried.getCount() - move));
        event.setCanceled(true);
    }

    /** Client side: the tooltip line. */
    @EventBusSubscriber(modid = Fogged.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        static void onTooltip(ItemTooltipEvent event) {
            int step = step(event.getItemStack());
            if (step > 0) {
                int percent = Math.round(100.0F * step / ModDataComponents.MAX_POLLUTION);
                event.getToolTip().add(Component.translatable("fogged.tooltip.filter_polluted", percent)
                        .withStyle(step >= ModDataComponents.MAX_POLLUTION ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
        }

        // Every item: what is a filter is the config's to say, and a stack only draws a bar with a step.
        static void registerDecorations(RegisterItemDecorationsEvent event) {
            IItemDecorator bar = FilterPollution.Client::drawBar;
            BuiltInRegistries.ITEM.forEach(item -> event.register(item, bar));
        }

        // The vanilla durability bar's place and size, filling left to right with the pollution.
        private static boolean drawBar(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
            int step = step(stack);
            if (step <= 0) {
                return false;
            }
            int width = Math.round(13.0F * step / ModDataComponents.MAX_POLLUTION);
            int left = x + 2;
            int top = y + 13;
            graphics.fill(RenderType.guiOverlay(), left, top, left + 13, top + 2, 0xFF000000);
            graphics.fill(RenderType.guiOverlay(), left, top, left + width, top + 1, 0xFF000000 | barColor());
            return false;
        }

        // The grime colour, lightened so it reads against the bar's black.
        private static int barColor() {
            int c = Config.filterPollutionColor();
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            r += (255 - r) * 45 / 100;
            g += (255 - g) * 45 / 100;
            b += (255 - b) * 45 / 100;
            return (r << 16) | (g << 8) | b;
        }

        private Client() {}
    }

    private FilterPollution() {}
}
