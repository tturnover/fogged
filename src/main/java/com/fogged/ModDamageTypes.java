package com.fogged;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.level.Level;

/**
 * The mod's custom {@link DamageType}s and the {@link DamageSource}s built from them. The types
 * themselves are data-driven (JSON under {@code data/fogged/damage_type/}); these keys reference them
 * and the helpers below produce a source for dealing the damage, which is what drives the death message
 * ({@code death.attack.<message_id>} in the lang file).
 */
public final class ModDamageTypes {
    private ModDamageTypes() {}

    /** Drowning/suffocating in the dry fog (no attacker) -- replaces vanilla drown under the plane. */
    public static final ResourceKey<DamageType> FOG_SUFFOCATION = key("fog_suffocation");

    private static ResourceKey<DamageType> key(String name) {
        return ResourceKey.create(Registries.DAMAGE_TYPE, ResourceLocation.fromNamespaceAndPath(Fogged.MODID, name));
    }

    /** Environmental fog suffocation: no entity, so the death message falls back to the "fleeing X"
     *  variant when the victim was recently hurt by a mob. */
    public static DamageSource fogSuffocation(Level level) {
        return new DamageSource(holder(level, FOG_SUFFOCATION));
    }

    private static Holder<DamageType> holder(Level level, ResourceKey<DamageType> key) {
        return level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key);
    }
}
