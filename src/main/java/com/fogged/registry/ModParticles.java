package com.fogged.registry;

import com.fogged.Fogged;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Custom particles. {@code puff} is a colour-tintable clone of vanilla's {@code POOF} (it reuses the
 * same {@code generic_*} sprites, see {@code assets/fogged/particles/puff.json}): the nozzle filter
 * emits it white above the fog plane and foam-coloured below, so the two look and behave identically
 * except for colour -- which a plain {@code POOF} could not do, as it carries no colour.
 */
public final class ModParticles {
    public static final DeferredRegister<ParticleType<?>> PARTICLE_TYPES =
            DeferredRegister.create(Registries.PARTICLE_TYPE, Fogged.MODID);

    public static final DeferredHolder<ParticleType<?>, ParticleType<ColorParticleOption>> PUFF =
            PARTICLE_TYPES.register("puff", () -> new ParticleType<ColorParticleOption>(false) {
                @Override
                public MapCodec<ColorParticleOption> codec() {
                    return ColorParticleOption.codec(this);
                }

                @Override
                public StreamCodec<? super RegistryFriendlyByteBuf, ColorParticleOption> streamCodec() {
                    return ColorParticleOption.streamCodec(this);
                }
            });

    public static void register(IEventBus modEventBus) {
        PARTICLE_TYPES.register(modEventBus);
    }

    private ModParticles() {}
}
