package com.fogged.registry;

import com.fogged.Fogged;

import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * Puts this mod's items into vanilla's creative tabs, rather than giving it a tab of its own.
 *
 * <p>One tab per mod is worth it for a mod with a shelf of blocks; this one has a single item, and a
 * whole tab for it is a page the player has to learn instead of finding it where its kind already
 * lives. The fog detector reads a redstone signal out of the world, so it goes with the rest of the
 * redstone.
 *
 * <p>The other two blocks are deliberately item-less (see {@link ModBlocks}): the detector extension
 * only exists by stacking a detector, and the nozzle filter only by putting wool on a nozzle.
 */
@EventBusSubscriber(modid = Fogged.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModCreativeTabs {

    @SubscribeEvent
    static void onBuildTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(ModBlocks.FOG_DETECTOR.get());
        }
    }

    private ModCreativeTabs() {}
}
