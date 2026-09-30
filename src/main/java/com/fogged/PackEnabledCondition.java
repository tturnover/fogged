package com.fogged;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.neoforged.neoforge.common.conditions.ICondition;

/**
 * The condition every recipe in one of the mod's own datapacks carries: it loads only while that
 * pack's switch in {@code [datapacks]} is on.
 *
 * <pre>{@code "neoforge:conditions": [{ "type": "fogged:pack_enabled", "pack": "coal_remover" }]}</pre>
 *
 * <p>Two ways to say no to a set, for two different people. A server or a packmaker turns the pack off
 * in the world's pack list, which outlives any config file and travels with the world; a player turns
 * it off in the mod's config screen, where they were already looking. Both end at the same place --
 * the recipes are simply not loaded -- and since conditions are read while the datapacks load, the
 * clients are sent what is left rather than being told separately.
 *
 * <p>The cost of that is when: a switch here is read once per datapack load, so flipping one shows
 * after a {@code /reload} rather than at once.
 */
public record PackEnabledCondition(String pack) implements ICondition {

    public static final MapCodec<PackEnabledCondition> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(Codec.STRING.fieldOf("pack").forGetter(PackEnabledCondition::pack))
                    .apply(instance, PackEnabledCondition::new));

    @Override
    public boolean test(IContext context) {
        return Config.packEnabled(pack);
    }

    @Override
    public MapCodec<? extends ICondition> codec() {
        return CODEC;
    }
}
