package com.fogged;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
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
 * <p>Two ways in. An itemTransforms rule names the item outright, so it can turn anything into
 * anything -- a diamond into dirt, if that is what a pack wants. Failing that, an item that places a
 * block the block rules cover follows those, which is what keeps a dropped stack in step with the
 * world it is lying in.
 *
 * <p>Server-side only, gated by {@link Config#SUBMERGE_WORLD} along with the rest of the scour;
 * {@link Config#TRANSFORM_DROPPED_ITEMS} switches off the block-derived half on its own, leaving the
 * item rules, which are an explicit list and are only there because someone wrote them.
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
        if (!Config.SUBMERGE_WORLD.get()) {
            return;
        }
        if (!Config.fogged(level, item.getY())) {
            return;
        }
        ItemStack stack = item.getItem();
        // An itemTransforms rule first: it names this item outright, where the block path only knows
        // items that happen to place something the block rules cover.
        Item to = ScourRules.itemTransform(level, stack, item.getY());
        if (to == null && Config.TRANSFORM_DROPPED_ITEMS.get()
                && stack.getItem() instanceof BlockItem blockItem) {
            Block block = ScourRules.transformForItem(level, blockItem.getBlock(), item.getY());
            to = block == null ? null : block.asItem();
        }
        if (to == null || to == stack.getItem()) {
            return;
        }
        // Count carries over; anything else the stack held does not, because the item it held it as is
        // gone. Enchanted or named blocks are not what this is for.
        //
        // The new item may not stack as high as the old one -- sixty-four dirt asked to become eggs is
        // sixty-four eggs, four times what an egg stack holds. The entity keeps one full stack and the
        // rest are dropped beside it, carrying its motion, so the player ends up with everything they
        // had rather than an oversized stack that misbehaves the moment it is picked up.
        int max = Math.max(1, new ItemStack(to).getMaxStackSize());
        int left = stack.getCount();
        item.setItem(new ItemStack(to, Math.min(left, max)));
        left -= Math.min(left, max);
        while (left > 0) {
            int count = Math.min(left, max);
            ItemEntity spill = new ItemEntity(level, item.getX(), item.getY(), item.getZ(),
                    new ItemStack(to, count));
            spill.setDeltaMovement(item.getDeltaMovement());
            spill.setDefaultPickUpDelay();
            level.addFreshEntity(spill);
            left -= count;
        }
    }
}
