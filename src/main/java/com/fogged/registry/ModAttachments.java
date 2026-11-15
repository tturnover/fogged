package com.fogged.registry;

import java.util.HashMap;
import java.util.Map;

import com.fogged.Fogged;
import com.mojang.serialization.Codec;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Data the mod keeps on game objects. */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, Fogged.MODID);

    /**
     * On a chunk, from worlds before used filters: the polluted filters set down in it, packed position ->
     * the murk in it, in blocks. Only read, to turn each into a used filter when its chunk loads
     * (FilterPollution); a used filter keeps its pollution in its own NBT. Not written while empty.
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<Long, Integer>>> PLACED_POLLUTION =
            ATTACHMENTS.register("placed_pollution", () -> AttachmentType.<Map<Long, Integer>>builder(() -> new HashMap<>())
                    .serialize(Codec.unboundedMap(Codec.STRING.xmap(Long::parseLong, String::valueOf), Codec.INT)
                            .xmap(m -> (Map<Long, Integer>) new HashMap<>(m), m -> m), m -> !m.isEmpty())
                    .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    private ModAttachments() {}
}
