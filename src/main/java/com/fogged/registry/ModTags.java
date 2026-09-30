package com.fogged.registry;

import com.fogged.Fogged;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * The mod's own tags: what it ships as data rather than as code or as a config default, so another
 * mod or a datapack can join in without a player editing anything.
 *
 * <p>Most of them are named by the config options that read them ({@code scouredBlocks},
 * {@code snuffedDevices}, {@code allowedMobs}, {@code sableFloatBlocks}) and reach the game as tag
 * entries in those lists rather than through this class, so they are kept here only as the one place
 * the names are written down. The two constants are the tags the code asks about directly.
 */
public final class ModTags {
    private ModTags() {}

    /** Blocks the murk takes out under the boundary -- the default of {@code scouredBlocks}. */
    public static final TagKey<Block> SCOURED = blockTag("scoured");

    /**
     * Blocks the scour never touches, whatever else says otherwise: it beats both the
     * {@code scouredBlocks} list and {@code scourAllPlants}, which judges by what a block IS and so
     * cannot be argued with any other way. This is how a pack spares one plant without switching the
     * whole rule off. Transforms are not affected -- those are conversions someone asked for by name.
     */
    public static final TagKey<Block> SCOUR_IMMUNE = blockTag("scour_immune");

    /**
     * Blocks the murk simply puts out: they are removed, nothing drops. Fire and soul fire by default.
     * This and {@link #KNOCKED_DOWN} are the built-in half of the extinguish -- what used to be block
     * names in {@link com.fogged.FogScour} -- so a mod whose flame the murk ought to douse can say so
     * rather than ask for code. Lava is not here: what it freezes to depends on whether it is a source,
     * which a tag cannot say.
     */
    public static final TagKey<Block> DOUSED = blockTag("doused");

    /** Blocks the murk knocks down, dropping what they would when broken. The torches by default. */
    public static final TagKey<Block> KNOCKED_DOWN = blockTag("knocked_down");

    /** Fire-burning devices the murk puts out -- the default of {@code snuffedDevices}. */
    public static final TagKey<Block> SNUFFED_DEVICES = blockTag("snuffed_devices");

    /** Blocks that hold a hull up in the murk -- the default of {@code sableFloatBlocks}. */
    public static final TagKey<Block> FLOATS = blockTag("floats");

    /**
     * What a Create nozzle can be filtered with. Defaults to {@code #minecraft:wool}; the filter
     * remembers a dye colour rather than the item, so media whose id does not end in {@code _wool}
     * makes a white filter and returns white wool when broken.
     */
    public static final TagKey<Item> FILTER_MEDIA = itemTag("filter_media");

    /** Entity types that live under the boundary -- the default of {@code allowedMobs}. */
    public static final TagKey<EntityType<?>> BREATHES_MURK =
            TagKey.create(Registries.ENTITY_TYPE, id("breathes_murk"));

    private static TagKey<Block> blockTag(String path) {
        return TagKey.create(Registries.BLOCK, id(path));
    }

    private static TagKey<Item> itemTag(String path) {
        return TagKey.create(Registries.ITEM, id(path));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(Fogged.MODID, path);
    }
}
