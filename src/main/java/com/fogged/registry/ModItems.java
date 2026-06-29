package com.fogged.registry;

import java.util.function.Function;

import com.fogged.Fogged;

import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Single source of truth for every standalone item in the mod.
 *
 * <p>{@link BlockItem}s for blocks are registered automatically by {@link ModBlocks}; they live in
 * the same {@link #ITEMS} register so the data generators see them too. Declare a plain item with
 * one line via {@link #register} and the generators emit the item model (a simple
 * {@code item/generated} model) and lang entry for it.
 */
public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Fogged.MODID);

    // --- Items --------------------------------------------------------------
    // Add new items below. One line each; datagen produces the model + lang.

    // Debug/test item. It is in the fogged:lurker_ward tag, so holding or wearing it stops the carrier
    // from provoking a fog lurker -- handy for digging next to one without triggering the attack.
    public static final DeferredItem<Item> LURKER_DEBUG =
            register("lurker_debug", Item::new, new Item.Properties());

    // ------------------------------------------------------------------------

    public static <T extends Item> DeferredItem<T> register(
            String name, Function<Item.Properties, T> factory, Item.Properties props) {
        return ITEMS.registerItem(name, factory, props);
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }

    private ModItems() {}
}
