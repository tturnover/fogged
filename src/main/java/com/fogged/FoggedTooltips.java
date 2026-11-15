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
        ItemStack stack = event.getItemStack();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());

        if (CREATE_NOZZLE.equals(id)) {
            // Under Create's own summary and sections, as one more of them.
            FoggedTooltip.appendShiftSections(event.getToolTip(), id, true, null,
                    "fogged.tooltip.nozzle.condition1", "fogged.tooltip.nozzle.behaviour1");
        } else if (stack.is(ModBlocks.FOG_DETECTOR.get().asItem())) {
            FoggedTooltip.appendShiftSections(event.getToolTip(), id, false, "fogged.tooltip.fog_detector.summary",
                    "fogged.tooltip.fog_detector.condition1", "fogged.tooltip.fog_detector.behaviour1",
                    "fogged.tooltip.fog_detector.condition2", "fogged.tooltip.fog_detector.behaviour2");
        } else if (stack.is(ModBlocks.AIR_COMPRESSOR.get().asItem())) {
            FoggedTooltip.appendShiftSections(event.getToolTip(), id, false, "fogged.tooltip.air_compressor.summary",
                    "fogged.tooltip.air_compressor.condition1", "fogged.tooltip.air_compressor.behaviour1",
                    "fogged.tooltip.air_compressor.condition2", "fogged.tooltip.air_compressor.behaviour2",
                    "fogged.tooltip.air_compressor.condition3", "fogged.tooltip.air_compressor.behaviour3");
        } else if (stack.is(ModBlocks.AIR_COMPRESSOR_INTAKE.get().asItem())) {
            FoggedTooltip.appendShiftSections(event.getToolTip(), id, false, "fogged.tooltip.air_intake.summary",
                    "fogged.tooltip.air_intake.condition1", "fogged.tooltip.air_intake.behaviour1",
                    "fogged.tooltip.air_intake.condition2", "fogged.tooltip.air_intake.behaviour2");
        } else if (stack.is(ModBlocks.AIR_DUCT.get().asItem())) {
            FoggedTooltip.appendShiftSections(event.getToolTip(), id, false, "fogged.tooltip.air_duct.summary",
                    "fogged.tooltip.air_duct.condition1", "fogged.tooltip.air_duct.behaviour1",
                    "fogged.tooltip.air_duct.condition2", "fogged.tooltip.air_duct.behaviour2");
        } else if (stack.is(com.fogged.registry.ModItems.FOG_WALKER.get())) {
            FoggedTooltip.appendShiftSections(event.getToolTip(), id, false, "fogged.tooltip.fog_walker.summary",
                    "fogged.tooltip.fog_walker.condition1", "fogged.tooltip.fog_walker.behaviour1",
                    "fogged.tooltip.fog_walker.condition2", "fogged.tooltip.fog_walker.behaviour2",
                    "fogged.tooltip.fog_walker.condition3", "fogged.tooltip.fog_walker.behaviour3",
                    "fogged.tooltip.fog_walker.condition4", "fogged.tooltip.fog_walker.behaviour4");
        }
    }

    private FoggedTooltips() {}
}
