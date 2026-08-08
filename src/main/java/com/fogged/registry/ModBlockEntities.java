package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlockEntity;
import com.fogged.block.NozzleFilterBlockEntity;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Block entities. The base {@code fog_detector} carries one so it can re-evaluate its redstone
 * output as the fog plane drifts over the day (extensions have no block entity).
 */
public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Fogged.MODID);

    // Valid for the base and its extensions -- extensions carry a (non-ticking) entity only to supply the
    // dynamic pole's model data.
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FogDetectorBlockEntity>> FOG_DETECTOR =
            BLOCK_ENTITIES.register("fog_detector", () -> BlockEntityType.Builder
                    .of(FogDetectorBlockEntity::new, ModBlocks.FOG_DETECTOR.get(), ModBlocks.FOG_DETECTOR_EXTENSION.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NozzleFilterBlockEntity>> NOZZLE_FILTER =
            BLOCK_ENTITIES.register("nozzle_filter", () -> BlockEntityType.Builder
                    .of(NozzleFilterBlockEntity::new, ModBlocks.NOZZLE_FILTER.get())
                    .build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }

    private ModBlockEntities() {}
}
