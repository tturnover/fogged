package com.fogged.data;

import java.util.Set;

import com.fogged.registry.ModBlocks;
import com.fogged.registry.ModItems;
import com.fogged.NozzleFilterEvents;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
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
            } else if (block == ModBlocks.FOG_EYE_STEM.get()) {
                // Unobtainable: the stem has no item and drops nothing when broken.
                add(block, LootTable.lootTable());
            } else if (block == ModBlocks.NOZZLE_FILTER.get()) {
                // Breaking a filter returns the puff balls that made it (the nozzle itself is gone).
                add(block, createSingleItemTable(ModItems.PUFF_BALL.get(),
                        ConstantValue.exactly(NozzleFilterEvents.REQUIRED)));
            } else if (block == ModBlocks.PUFF_BUSH_LEAVES.get()) {
                // Standard leaves drops (sapling + sticks), plus a rare puff ball when broken by hand.
                add(block, createLeavesDrops(block, ModBlocks.PUFF_BUSH_SAPLING.get(), NORMAL_LEAVES_SAPLING_CHANCES)
                        .withPool(LootPool.lootPool()
                                .setRolls(ConstantValue.exactly(1.0F))
                                .when(HAS_SHEARS.invert())
                                .when(doesNotHaveSilkTouch())
                                .add(applyExplosionCondition(block, LootItem.lootTableItem(ModItems.PUFF_BALL.get()))
                                        .when(LootItemRandomChanceCondition.randomChance(0.02F)))));
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
