package com.fogged.data;

import java.util.Set;

import com.fogged.registry.ModBlocks;
import com.fogged.registry.ModItems;
import com.fogged.NozzleFilterEvents;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Emits {@code data/fogged/loot_table/blocks/<name>.json} for every block in {@link ModBlocks}.
 * Most blocks drop themselves; the (item-less) extension drops a {@code fog_detector} instead, so a
 * broken/cascaded tower returns one detector per segment.
 */
public class ModBlockLoot extends BlockLootSubProvider {
    public ModBlockLoot(HolderLookup.Provider registries) {
        super(Set.of(), FeatureFlags.REGISTRY.allFlags(), registries);
    }

    @Override
    protected void generate() {
        ModBlocks.BLOCKS.getEntries().forEach(holder -> {
            Block block = holder.get();
            if (block == ModBlocks.FOG_DETECTOR_EXTENSION.get()) {
                add(block, createSingleItemTable(ModBlocks.FOG_DETECTOR.get()));
            } else if (block == ModBlocks.NOZZLE_FILTER.get()) {
                // Breaking a filter returns the fluff balls that made it (the nozzle itself is gone).
                add(block, createSingleItemTable(ModItems.FLUFF_BALL.get(),
                        ConstantValue.exactly(NozzleFilterEvents.REQUIRED)));
            } else {
                dropSelf(block);
            }
        });
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ModBlocks.BLOCKS.getEntries().stream().map(holder -> (Block) holder.get())::iterator;
    }
}
