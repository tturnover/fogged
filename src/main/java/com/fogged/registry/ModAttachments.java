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
     * On a chunk: the polluted filters set down in it as blocks, packed position -> the murk in it, in
     * blocks (out of its capacity; {@link ModDataComponents#POLLUTION} steps on the item), so one broken comes back
     * as dirty as it still is; out of the murk it sheds it (FilterPollution). Saved with the chunk; not
     * written while empty.
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
