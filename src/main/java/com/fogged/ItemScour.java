package com.fogged;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The murk works on dropped items as well as on placed blocks: a stack lying on the fogged side turns
 * into whatever its block form would turn into, by the same rules and at the same depths (see
 * {@link ScourRules}). Copper thrown down there oxidises where copper built down there does; moss
 * dropped fifty blocks under the surface goes the way the moss floor does.
 *
 * <p>One list drives this and the placed-block scour alike (see {@link ScourRules}): an entry whose
 * left side names an item converts stacks of it, and since most block ids are item ids too, a rule
 * written for a block keeps its dropped form in step with the world it is lying in. An entry may also
 * take several of a thing to make something -- "4 diamond=2 dirt" -- and a remainder too small to
 * convert stays what it was.
 *
 * <p>Server-side only, gated by {@link Config#ENABLE_WORLD_CHANGES} along with the rest of the scour.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class ItemScour {
    private ItemScour() {}

    // An item entity is checked this often rather than every tick: nothing about a stack lying on the
    // floor changes in between, and the boundary itself moves in whole blocks over minutes.
    private static final int CHECK_INTERVAL = 20;

    // Ticks left before a stack the murk has taken hold of turns, kept on the entity (see FogScour for
    // the same wait on placed blocks). Lifted back out of the murk, the stack is let go.
    private static final String PENDING = "fogged:scour";

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof ItemEntity item)) {
            return;
        }
        Level level = item.level();
        if (level.isClientSide || !Config.ENABLE_WORLD_CHANGES.get()) {
            return;
        }
        CompoundTag data = item.getPersistentData();
        if (data.contains(PENDING)) {
            if (!Config.fogged(level, item.getY())) {
                data.remove(PENDING);
                return;
            }
            int left = data.getInt(PENDING) - 1;
            if (left > 0) {
                data.putInt(PENDING, left);
                steam((ServerLevel) level, item);
                return;
            }
            data.remove(PENDING);
            convert(level, item);
            return;
        }
        if (item.tickCount % CHECK_INTERVAL != 0 || !Config.fogged(level, item.getY())) {
            return;
        }
        if (ScourRules.convert(level, item.getItem(), item.getY()) == null) {
            return;
        }
        int delay = Config.WORLD_CHANGE_DELAY.getAsInt() * 20;
        if (delay <= 0) {
            convert(level, item);
        } else {
            data.putInt(PENDING, delay);
        }
    }

    // A wisp up from the stack itself.
    private static void steam(ServerLevel level, ItemEntity item) {
        RandomSource rand = level.random;
        if (rand.nextInt(2) != 0) {
            return;
        }
        level.sendParticles(FogScour.steam(),
                item.getX() + (rand.nextDouble() - 0.5) * 0.3, item.getY() + 0.2,
                item.getZ() + (rand.nextDouble() - 0.5) * 0.3,
                0, 0.0, 0.04 + rand.nextDouble() * 0.03, 0.0, 1.0);
    }

    private static void convert(Level level, ItemEntity item) {
        ItemStack stack = item.getItem();
        ScourRules.Conversion conversion = ScourRules.convert(level, stack, item.getY());
        if (conversion == null) {
            return;
        }

        // A rule may take several of a thing to make something -- "4 diamond=2 dirt" -- so it applies
        // as many times as the stack allows, and whatever is left over stays what it was. That
        // remainder is the entity's own stack, so a stack of five diamonds under a four-for-two rule
        // ends up as one diamond lying next to two dirt.
        int uses = stack.getCount() / conversion.takes();
        int remainder = stack.getCount() - uses * conversion.takes();
        int made = uses * conversion.gives();

        int max = Math.max(1, new ItemStack(conversion.to()).getMaxStackSize());
        if (remainder > 0) {
            item.setItem(new ItemStack(stack.getItem(), remainder));
        } else {
            // Nothing left over, so the entity itself becomes the first stack of the result.
            item.setItem(new ItemStack(conversion.to(), Math.min(made, max)));
            made -= Math.min(made, max);
        }
        // The rest is dropped beside it, one entity per stack, carrying its motion: a target that
        // stacks smaller than its source would otherwise leave an oversized stack, which misbehaves
        // the moment it is picked up.
        while (made > 0) {
            int count = Math.min(made, max);
            ItemEntity spill = new ItemEntity(level, item.getX(), item.getY(), item.getZ(),
                    new ItemStack(conversion.to(), count));
            spill.setDeltaMovement(item.getDeltaMovement());
            spill.setDefaultPickUpDelay();
            level.addFreshEntity(spill);
            made -= count;
        }
    }
}
