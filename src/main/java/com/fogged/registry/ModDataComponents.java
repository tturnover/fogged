package com.fogged.registry;

import com.fogged.Fogged;
import com.mojang.serialization.Codec;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Data components the mod puts on item stacks. */
public final class ModDataComponents {
    public static final DeferredRegister.DataComponents COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Fogged.MODID);

    /** The most a filter's {@link #POLLUTION} goes: clogged. The same steps the intake shows. */
    public static final int MAX_POLLUTION = 7;

    /**
     * How polluted a filter taken out of an air intake is, in steps 1..{@link #MAX_POLLUTION} (clean
     * filters carry none). Steps rather than an exact figure so filters stack: rounded up as one comes
     * out, and two stacks merged take their average, rounded up (FilterPollution). It goes back in as dirty as it
     * says, and shows as a bar in the slot and a tooltip line.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> POLLUTION =
            COMPONENTS.registerComponentType("pollution", b -> b
                    .persistent(Codec.intRange(1, MAX_POLLUTION))
                    .networkSynchronized(ByteBufCodecs.VAR_INT));

    public static void register(IEventBus modEventBus) {
        COMPONENTS.register(modEventBus);
    }

    private ModDataComponents() {}
}
