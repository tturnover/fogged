package com.fogged;

import com.fogged.registry.ModBlocks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * Client-side tooltip additions, all in the same Create-style format via {@link FoggedTooltip}:
 * <ul>
 *   <li>Create's nozzle — appends the "apply a wool block" conversion note under Create's own
 *       Shift description (Create supplies its own prompt, so we add none).</li>
 *   <li>Our fog detector — a full Fogged section with its own "Hold [Shift]" prompt.</li>
 * </ul>
 * Matched by registry id / item, so the nozzle entry simply never fires when Create is absent.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class FoggedTooltips {

    private static final ResourceLocation CREATE_NOZZLE =
            ResourceLocation.fromNamespaceAndPath("create", "nozzle");

    // LOWEST so we run after Create's own tooltip modifier (it also uses ItemTooltipEvent); by then the
    // full tooltip -- summary, Ponder hint, advanced lines -- exists, so we can slot in above the Ponder.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onTooltip(ItemTooltipEvent event) {
        if (!Config.ITEMS_ENABLED.get()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());

        if (CREATE_NOZZLE.equals(id)) {
            FoggedTooltip.appendShiftSection(event.getToolTip(), id, true,
                    "fogged.tooltip.nozzle.desc1", "fogged.tooltip.nozzle.desc2");
        } else if (stack.is(ModBlocks.FOG_DETECTOR.get().asItem())) {
            FoggedTooltip.appendShiftSection(event.getToolTip(), id, false,
                    "fogged.tooltip.fog_detector.desc1", "fogged.tooltip.fog_detector.desc2");
        }
    }

    private FoggedTooltips() {}
}
