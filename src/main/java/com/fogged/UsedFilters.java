package com.fogged;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fogged.block.UsedFilterBlock;
import com.fogged.registry.ModBlocks;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * The used filters that stand in for particular blocks, made as the game starts from the filters it is
 * set up with: {@code fogged:used/<namespace>/<path>} for each, a copy of its block's properties, named
 * and itemed as it is, and in every tag it is in (UsedFilterTags) -- so a dirty filter set down is that
 * block to everything but the murk. Read from the startup config ({@code fogged-startup.toml}) and the
 * plain block ids among {@link Config#INTAKE_FILTERS}: tags (#...) are not loaded yet at startup, so
 * blocks that are filters only by a tag are listed by id in the startup config. Any other filter set
 * down uses the generic {@link ModBlocks#USED_FILTER}.
 *
 * <p>These are blocks, so a server and its players need the same list, and a change needs a restart.
 */
public final class UsedFilters {

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.ConfigValue<List<? extends String>> BLOCKS;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        List<String> wools = new ArrayList<>();
        for (DyeColor color : DyeColor.values()) {
            wools.add("minecraft:" + color.getSerializedName() + "_wool");
        }
        BLOCKS = b.comment("Blocks that get a used filter of their own, by id: set down dirty, such a filter stays",
                        "that block to the game and to other mods -- its name, item and every tag it is in -- while",
                        "it holds its pollution. Every plain block id in intakeFilters gets one too; list here the",
                        "blocks that are filters only through a tag (#...), as tags are not loaded yet when blocks",
                        "are made. Any other filter set down becomes the generic used filter. A server and its",
                        "players must have the same list; a change needs a restart.")
                .defineListAllowEmpty("usedFilterBlocks", () -> wools, () -> "minecraft:white_wool",
                        o -> o instanceof String s && ResourceLocation.tryParse(s) != null);
        SPEC = b.build();
    }

    // Each original block's id -> its used filter.
    private static final Map<ResourceLocation, UsedFilterBlock> BY_ORIGINAL = new LinkedHashMap<>();

    public static void register(IEventBus modEventBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.STARTUP, SPEC, "fogged-startup.toml");
        modEventBus.addListener(UsedFilters::onRegister);
    }

    private static void onRegister(RegisterEvent event) {
        if (!event.getRegistryKey().equals(Registries.BLOCK)) {
            return;
        }
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (String s : BLOCKS.get()) {
            ResourceLocation id = ResourceLocation.tryParse(s.trim());
            if (id != null) {
                ids.add(id);
            }
        }
        if (Config.COMMON_SPEC.isLoaded()) {
            for (String raw : Config.INTAKE_FILTERS.get()) {
                String s = raw.trim();
                int eq = s.lastIndexOf('=');
                ResourceLocation id = s.startsWith("#") ? null : ResourceLocation.tryParse(eq > 0 ? s.substring(0, eq).trim() : s);
                if (id != null) {
                    ids.add(id);
                }
            }
        }
        for (ResourceLocation id : ids) {
            if (id.getNamespace().equals(Fogged.MODID) && id.getPath().startsWith("used")) {
                continue;
            }
            // A block already made (the game's own, or a mod's ahead of this one) is copied whole; one not
            // made yet gets wool's, and the used filter asks it for the rest once it is.
            Block original = BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : null;
            BlockBehaviour.Properties props = original != null ? BlockBehaviour.Properties.ofFullCopy(original)
                    : BlockBehaviour.Properties.of().mapColor(MapColor.WOOL).strength(0.8F).sound(SoundType.WOOL);
            UsedFilterBlock used = new UsedFilterBlock(props, id);
            event.register(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "used/" + id.getNamespace() + "/" + id.getPath()),
                    () -> used);
            BY_ORIGINAL.put(id, used);
        }
    }

    /** The used filter that stands in for {@code original}: its own, or the generic one. */
    public static Block usedFor(Block original) {
        UsedFilterBlock used = BY_ORIGINAL.get(BuiltInRegistries.BLOCK.getKey(original));
        return used != null ? used : ModBlocks.USED_FILTER.get();
    }

    /** Each original block's id and the used filter that stands in for it. */
    public static Map<ResourceLocation, UsedFilterBlock> byOriginal() {
        return Collections.unmodifiableMap(BY_ORIGINAL);
    }

    /** Every used filter block: the generic one and each block's own. */
    public static Block[] blocks() {
        List<Block> all = new ArrayList<>(BY_ORIGINAL.size() + 1);
        all.add(ModBlocks.USED_FILTER.get());
        all.addAll(BY_ORIGINAL.values());
        return all.toArray(Block[]::new);
    }

    private UsedFilters() {}
}
