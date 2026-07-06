package com.fogged.registry;

import com.fogged.Config;
import com.fogged.Fogged;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * One creative tab holding every item in the mod. The {@code displayItems} builder iterates
 * {@link ModItems#ITEMS}, so blocks/items added there show up here automatically — no per-item edit.
 */
public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Fogged.MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> FOGGED_TAB = TABS.register(
            "fogged",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.fogged"))
                    .icon(() -> new ItemStack(ModBlocks.FOG_DETECTOR.get()))
                    .displayItems((params, output) ->
                            ModItems.ITEMS.getEntries().forEach(item -> {
                                // The items master switch hides the (now inert) fog detector device.
                                if (!Config.ITEMS_ENABLED.get()
                                        && item.get() instanceof BlockItem bi
                                        && bi.getBlock() == ModBlocks.FOG_DETECTOR.get()) {
                                    return;
                                }
                                output.accept(item.get());
                            }))
                    .build());

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }

    private ModCreativeTabs() {}
}
