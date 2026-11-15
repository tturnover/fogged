package com.fogged.item;

import java.util.List;

import com.fogged.Config;
import com.fogged.FogWalker;
import com.fogged.FogWalker.SuitFilter;
import com.fogged.registry.ModArmorMaterials;
import com.fogged.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * The fog walker suit (see {@link FogWalker}). A filter goes in the way a bundle takes items: right-click
 * the suit with one on the cursor fits it, swapping out the one already in when the cursor holds just the
 * one; right-click with an empty cursor takes it out.
 */
public class FogWalkerItem extends ArmorItem {

    /** The filter's name in the tooltip: the colour of the Shift summary's text (FoggedTooltip's body). */
    private static final net.minecraft.network.chat.Style FILTER_NAME =
            net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.network.chat.TextColor.fromRgb(0x9CC4E4));
    /** "None" in its place: the colour of the Shift summary's highlighted words (FoggedTooltip's highlight). */
    private static final net.minecraft.network.chat.Style NONE =
            net.minecraft.network.chat.Style.EMPTY.withColor(net.minecraft.network.chat.TextColor.fromRgb(0xEAF3FC));

    public FogWalkerItem(Properties properties) {
        super(ModArmorMaterials.FOG_DIVING, Type.CHESTPLATE,
                properties.stacksTo(1).durability(Type.CHESTPLATE.getDurability(15)));
    }

    @Override
    public boolean overrideOtherStackedOnMe(ItemStack suit, ItemStack carried, Slot slot, ClickAction action,
            Player player, SlotAccess cursor) {
        if (action != ClickAction.SECONDARY || !slot.allowModification(player)) {
            return false;
        }
        SuitFilter fitted = FogWalker.filter(suit);
        if (carried.isEmpty()) {
            if (fitted == null) {
                return false;
            }
            suit.remove(ModDataComponents.SUIT_FILTER.get());
            cursor.set(FogWalker.takeOut(fitted));
            player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 0.8F);
            return true;
        }
        if (!Config.fitsFogWalker(carried)) {
            return false; // not a filter it takes: let the click do what it would
        }
        if (fitted != null && carried.getCount() > 1) {
            return true; // the old filter would have nowhere to go; swallow the click
        }
        suit.set(ModDataComponents.SUIT_FILTER.get(), FogWalker.fit(carried));
        if (fitted != null) {
            cursor.set(FogWalker.takeOut(fitted));
        } else {
            carried.shrink(1);
        }
        player.playSound(SoundEvents.ARMOR_EQUIP_LEATHER.value(), 0.8F, 1.0F);
        return true;
    }

    @Override
    public void appendHoverText(ItemStack suit, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        SuitFilter fitted = FogWalker.filter(suit);
        // Which filter only, bracketed the way Create brackets [Shift]: how polluted it is shows as the bar.
        Component name = fitted == null
                ? Component.translatable("fogged.tooltip.fog_walker.none").withStyle(NONE)
                : fitted.stack().getHoverName().copy().withStyle(FILTER_NAME);
        tooltip.add(Component.literal("[").append(name).append("]").withStyle(ChatFormatting.GRAY));
    }
}
