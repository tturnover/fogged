package com.fogged.data;

import com.fogged.Fogged;
import com.fogged.registry.ModItems;

import net.minecraft.data.PackOutput;
import net.minecraft.world.item.BlockItem;
import net.neoforged.neoforge.client.model.generators.ItemModelProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Emits {@code assets/fogged/models/item/<name>.json} for every standalone item. {@link BlockItem}s
 * are skipped here — their item model is produced by {@link ModBlockStateProvider} via
 * {@code simpleBlockWithItem}. Plain items get an {@code item/generated} model textured at
 * {@code assets/fogged/textures/item/<name>.png}.
 */
public class ModItemModelProvider extends ItemModelProvider {
    public ModItemModelProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, Fogged.MODID, exFileHelper);
    }

    @Override
    protected void registerModels() {
        ModItems.ITEMS.getEntries().forEach(holder -> {
            if (holder.get() instanceof BlockItem) {
                return; // handled by the block state provider
            }
            basicItem(holder.get());
        });
    }
}
