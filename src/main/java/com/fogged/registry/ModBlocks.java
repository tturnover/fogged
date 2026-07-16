package com.fogged.registry;

import java.util.function.Function;
import java.util.function.Supplier;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlock;
import com.fogged.block.FogDetectorExtensionBlock;
import com.fogged.block.FogEyeBlock;
import com.fogged.block.FogEyeStemBlock;
import com.fogged.block.FoggyGrassBlock;
import com.fogged.block.NozzleFilterBlock;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
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

    // The moss the murk leaves behind: a full block that puddles over natural ground beneath the fog
    // plane wherever vegetation dies or a mob falls. See com.fogged.FogMoss / com.fogged.FogMossEvents.
    //
    // Three temperature variants are chosen by the biome the puddle grows in (see FogMoss#mossFor):
    // warm -> SOFT, cold -> HARSH, temperate -> the plain FOG_MOSS.
    public static final DeferredBlock<Block> FOG_MOSS =
            register("fog_moss", Block::new, mossProps());

    public static final DeferredBlock<Block> SOFT_FOG_MOSS =
            register("soft_fog_moss", Block::new, mossProps());

    public static final DeferredBlock<Block> HARSH_FOG_MOSS =
            register("harsh_fog_moss", Block::new, mossProps());

    // Nozzle filter: a create:nozzle converted by right-clicking it with 3 fluff balls. Not obtainable
    // as an item (registerNoItem) -- it only exists by replacing an in-world nozzle. It carries a block
    // entity that reads the attached fan's speed each tick and opens a breathing sphere (radius 3..16,
    // maxing at half fan power) that lets players/entities breathe under the fog. Too much airflow
    // (over half power) overloads it back into a nozzle. See NozzleFilterBlock / NozzleFilterBlockEntity.
    public static final DeferredBlock<NozzleFilterBlock> NOZZLE_FILTER =
            registerNoItem("nozzle_filter", NozzleFilterBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0F)
                    .sound(SoundType.NETHERITE_BLOCK)
                    .noOcclusion());

    private static BlockBehaviour.Properties mossProps() {
        return BlockBehaviour.Properties.of()
                .mapColor(MapColor.COLOR_GREEN)
                .strength(0.4F)
                .sound(SoundType.MOSS);
    }

    // The wispy tuft that grows on top of fog moss. It is seeded as moss puddles and then grows
    // taller and spreads across the patch over time, capped by biome temperature. See FoggyGrassBlock.
    public static final DeferredBlock<FoggyGrassBlock> FOGGY_GRASS =
            register("foggy_grass", FoggyGrassBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT)
                    .noCollission()
                    .instabreak()
                    .randomTicks()
                    .sound(SoundType.GRASS)
                    .noOcclusion()
                    .pushReaction(net.minecraft.world.level.material.PushReaction.DESTROY)
                    .offsetType(BlockBehaviour.OffsetType.XZ));

    // The fog eye and the stem it rides: a rare plant seeded on warm fog-moss puddles that races the
    // murk, growing a stem fast enough to keep its eye at the fog surface and eating the stem back
    // down when the plane sinks. The eye carries all the logic; the stem is its trail. See FogEyeBlock.
    public static final DeferredBlock<FogEyeStemBlock> FOG_EYE_STEM =
            register("fog_eye_stem", FogEyeStemBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.PLANT)
                    .strength(0.4F)
                    .sound(SoundType.MOSS)
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY));

    public static final DeferredBlock<FogEyeBlock> FOG_EYE =
            register("fog_eye", FogEyeBlock::new, BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GREEN)
                    .strength(0.4F)
                    .randomTicks()
                    .sound(SoundType.MOSS)
                    .noOcclusion()
                    .pushReaction(PushReaction.DESTROY));

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
