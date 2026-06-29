package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.entity.FogLurker;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Mod entities. Currently just the {@link FogLurker}. Attributes are supplied on the mod event bus via
 * {@link #onAttributes}; the renderer/model are wired client-side in {@code com.fogged.entity.FogLurkerClient}.
 */
@EventBusSubscriber(modid = Fogged.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Fogged.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<FogLurker>> FOG_LURKER =
            ENTITIES.register("fog_lurker", () -> EntityType.Builder.of(FogLurker::new, MobCategory.MONSTER)
                    .sized(1.6F, 0.8F)
                    .clientTrackingRange(12)
                    .updateInterval(1)   // sync position every tick so the glide stays smooth
                    .build("fog_lurker"));

    public static void register(IEventBus modEventBus) {
        ENTITIES.register(modEventBus);
    }

    @SubscribeEvent
    static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(FOG_LURKER.get(), FogLurker.createAttributes().build());
    }

    private ModEntities() {}
}
