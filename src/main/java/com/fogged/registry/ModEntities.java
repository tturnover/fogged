package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.entity.Plodder;
import com.fogged.entity.SnapDog;
import com.fogged.entity.Trudger;
import com.fogged.rig.RigGameplay;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entity types. A rigged entity needs three things besides its class: a line here, its attributes in
 * {@link #onAttributes}, and a {@code RiggedRenderer} line in {@code FoggedClient} naming its model.
 */
public final class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, Fogged.MODID);

    /** Rig test creature; see {@link Plodder}. */
    public static final DeferredHolder<EntityType<?>, EntityType<Plodder>> PLODDER = ENTITY_TYPES.register("plodder",
            () -> EntityType.Builder.of(Plodder::new, MobCategory.CREATURE)
                    .sized(1.2f, 1.8f)
                    .clientTrackingRange(10)
                    .build("plodder"));

    /** Sauropod-scale rig test; see {@link Trudger}. */
    public static final DeferredHolder<EntityType<?>, EntityType<Trudger>> TRUDGER = ENTITY_TYPES.register("trudger",
            () -> EntityType.Builder.of(Trudger::new, MobCategory.CREATURE)
                    .sized(2.6f, 5.5f)
                    .clientTrackingRange(16)
                    .build("trudger"));

    /** The rig's behaviour test; see {@link SnapDog}. */
    public static final DeferredHolder<EntityType<?>, EntityType<SnapDog>> SNAP_DOG = ENTITY_TYPES.register("snap_dog",
            () -> EntityType.Builder.of(SnapDog::new, MobCategory.CREATURE)
                    .sized(0.9f, 0.9f)
                    .clientTrackingRange(10)
                    .build("snap_dog"));

    private static void onAttributes(EntityAttributeCreationEvent event) {
        event.put(PLODDER.get(), rigged(Plodder.createAttributes(), "plodder"));
        event.put(TRUDGER.get(), rigged(Trudger.createAttributes(), "trudger"));
        event.put(SNAP_DOG.get(), rigged(SnapDog.createAttributes(), "snap_dog"));
    }

    /** A rigged mob's model can set its step height through its legs' reach_*_up bones (see RigGameplay). */
    private static AttributeSupplier rigged(AttributeSupplier.Builder attributes, String model) {
        RigGameplay.stepHeight(model).ifPresent(step -> attributes.add(Attributes.STEP_HEIGHT, step));
        return attributes.build();
    }

    public static void register(IEventBus modEventBus) {
        ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(ModEntities::onAttributes);
    }

    private ModEntities() {}
}
