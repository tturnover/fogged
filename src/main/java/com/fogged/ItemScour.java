package com.fogged;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The murk works on dropped items as well as on placed blocks: a stack lying on the fogged side turns
 * into whatever its block form would turn into, by the same rules and at the same depths (see
 * {@link ScourRules}). Copper thrown down there oxidises where copper built down there does; moss
 * dropped fifty blocks under the surface goes the way the moss floor does.
 *
 * <p>Only items whose block form has a rule are touched, so a pack that lists no block transforms
 * gets none of this either. Non-block items are not covered at all -- the rules are written in block
 * ids, and there is nothing to look up for a coal or an ingot.
 *
 * <p>Server-side only, gated by {@link Config#SUBMERGE_WORLD} along with the rest of the scour and by
 * {@link Config#TRANSFORM_DROPPED_ITEMS} on its own.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class ItemScour {
    private ItemScour() {}

    // An item entity is checked this often rather than every tick: nothing about a stack lying on the
    // floor changes in between, and the boundary itself moves in whole blocks over minutes.
    private static final int CHECK_INTERVAL = 20;

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemEntity item)) {
            return;
        }
        Level level = item.level();
        if (level.isClientSide || item.tickCount % CHECK_INTERVAL != 0) {
            return;
        }
        if (!Config.SUBMERGE_WORLD.get() || !Config.TRANSFORM_DROPPED_ITEMS.get()) {
            return;
        }
        if (!Config.fogged(level, item.getY())) {
            return;
        }
        ItemStack stack = item.getItem();
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            return;
        }
        Block to = ScourRules.transformForItem(level, blockItem.getBlock(), item.getY());
        if (to == null || to.asItem() == stack.getItem()) {
            return;
        }
        // Count carries over; anything else the stack held does not, because the item it held it as is
        // gone. Enchanted or named blocks are not what this is for.
        item.setItem(new ItemStack(to, stack.getCount()));
    }
}
