package com.fogged.data;

import java.util.Set;

import com.fogged.block.NozzleFilterBlock;
import com.fogged.registry.ModBlocks;

import net.minecraft.advancements.critereon.StatePropertiesPredicate;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.loot.BlockLootSubProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.predicates.LootItemBlockStatePropertyCondition;
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
                // Breaking a filter returns the wool that made it (the nozzle itself is gone). The wool's
                // colour is stored in the block's COLOR state, so one pool per colour drops the match.
                add(block, nozzleFilterLoot(block));
            } else {
                dropSelf(block);
            }
        });
    }

    // Data-driven colour match: a pool per dye colour, each gated on the filter's COLOR state, so a red
    // filter drops red wool and so on. Keeps the drop in the loot table (works for every break path).
    private LootTable.Builder nozzleFilterLoot(Block block) {
        LootTable.Builder table = LootTable.lootTable();
        for (DyeColor color : DyeColor.values()) {
            Item wool = BuiltInRegistries.ITEM.getOptional(
                    ResourceLocation.withDefaultNamespace(color.getSerializedName() + "_wool"))
                    .orElse(Items.WHITE_WOOL);
            table.withPool(LootPool.lootPool()
                    .setRolls(ConstantValue.exactly(1.0F))
                    .add(applyExplosionCondition(block, LootItem.lootTableItem(wool)))
                    .when(LootItemBlockStatePropertyCondition.hasBlockStateProperties(block)
                            .setProperties(StatePropertiesPredicate.Builder.properties()
                                    .hasProperty(NozzleFilterBlock.COLOR, color.getSerializedName()))));
        }
        return table;
    }

    @Override
    protected Iterable<Block> getKnownBlocks() {
        return ModBlocks.BLOCKS.getEntries().stream().map(holder -> (Block) holder.get())::iterator;
    }
}
