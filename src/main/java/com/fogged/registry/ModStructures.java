package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.KarstMarkerStructure;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Structure types. The only one is {@code karst_marker}, which builds nothing -- it marks where
 * {@code KarstPillarsFeature} raises its towers so {@code /locate} can find them. See
 * {@link KarstMarkerStructure}.
 */
public final class ModStructures {
    public static final DeferredRegister<StructureType<?>> STRUCTURE_TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, Fogged.MODID);

    public static final DeferredHolder<StructureType<?>, StructureType<KarstMarkerStructure>> KARST_MARKER =
            STRUCTURE_TYPES.register("karst_marker", () -> () -> KarstMarkerStructure.CODEC);

    public static void register(IEventBus modEventBus) {
        STRUCTURE_TYPES.register(modEventBus);
    }

    private ModStructures() {}
}
