package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlockEntity;
import com.fogged.block.FogEyeBlockEntity;
import com.fogged.block.FogEyeStemBlockEntity;
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

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FogDetectorBlockEntity>> FOG_DETECTOR =
            BLOCK_ENTITIES.register("fog_detector", () -> BlockEntityType.Builder
                    .of(FogDetectorBlockEntity::new, ModBlocks.FOG_DETECTOR.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<NozzleFilterBlockEntity>> NOZZLE_FILTER =
            BLOCK_ENTITIES.register("nozzle_filter", () -> BlockEntityType.Builder
                    .of(NozzleFilterBlockEntity::new, ModBlocks.NOZZLE_FILTER.get())
                    .build(null));

    // Which root a fog eye / stem cell currently considers itself anchored to -- see
    // FogEyeAnchorBlockEntity. Carried by both the head and the stem it leaves behind, since either can
    // end up needing a reroot after losing support.
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FogEyeBlockEntity>> FOG_EYE =
            BLOCK_ENTITIES.register("fog_eye", () -> BlockEntityType.Builder
                    .of(FogEyeBlockEntity::new, ModBlocks.FOG_EYE.get())
                    .build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FogEyeStemBlockEntity>> FOG_EYE_STEM =
            BLOCK_ENTITIES.register("fog_eye_stem", () -> BlockEntityType.Builder
                    .of(FogEyeStemBlockEntity::new, ModBlocks.FOG_EYE_STEM.get())
                    .build(null));

    public static void register(IEventBus modEventBus) {
        BLOCK_ENTITIES.register(modEventBus);
    }

    private ModBlockEntities() {}
}
