package com.fogged.registry;

import java.util.function.Function;
import java.util.function.Supplier;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlock;
import com.fogged.block.FogDetectorExtensionBlock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Single source of truth for every block in the mod.
 *
 * <p>Declare a block with one line via {@link #register}. That single call wires up:
 * <ul>
 *   <li>the block registry entry,</li>
 *   <li>a matching {@link BlockItem} (so the block is obtainable / shows in creative),</li>
 * </ul>
 * and the data generators ({@code com.fogged.data}) iterate {@link #BLOCKS} to emit the
 * blockstate JSON, block model, item model, loot table and lang entry. Add a block here and the
 * folders + JSON links are produced for you on the next {@code runData}.
 */
public final class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Fogged.MODID);

    // --- Blocks -------------------------------------------------------------
    // Add new blocks below. One line each; datagen produces everything else.

    public static final DeferredBlock<FogDetectorBlock> FOG_DETECTOR =
            register("fog_detector", FogDetectorBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.ICE)
                    .strength(0.3F)
                    .sound(SoundType.GLASS)
                    .noOcclusion());

    // No BlockItem: the extension is not obtainable; it is placed by interacting with a detector.
    public static final DeferredBlock<FogDetectorExtensionBlock> FOG_DETECTOR_EXTENSION =
            registerNoItem("fog_detector_extension", FogDetectorExtensionBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.ICE)
                    .strength(0.3F)
                    .sound(SoundType.GLASS)
                    .noOcclusion());

    // ------------------------------------------------------------------------

    /**
     * Registers a block and its matching simple {@link BlockItem} in one call.
     *
     * @param name     registry path, e.g. {@code "fog_glass"}
     * @param factory  block constructor, usually {@code Block::new}
     * @param props    block behaviour/properties
     */
    public static <T extends Block> DeferredBlock<T> register(
            String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties props) {
        DeferredBlock<T> block = BLOCKS.registerBlock(name, factory, props);
        registerBlockItem(name, block);
        return block;
    }

    /** Registers a block with no {@link BlockItem} (not obtainable as a held item). */
    public static <T extends Block> DeferredBlock<T> registerNoItem(
            String name, Function<BlockBehaviour.Properties, T> factory, BlockBehaviour.Properties props) {
        return BLOCKS.registerBlock(name, factory, props);
    }

    private static <T extends Block> void registerBlockItem(String name, Supplier<T> block) {
        ModItems.ITEMS.registerItem(name, props -> new BlockItem(block.get(), props), new Item.Properties());
    }

    public static void register(IEventBus modEventBus) {
        BLOCKS.register(modEventBus);
    }

    private ModBlocks() {}
}
