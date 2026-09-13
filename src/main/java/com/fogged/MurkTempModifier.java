package com.fogged;

import java.util.function.Function;

import com.momosoftworks.coldsweat.api.temperature.modifier.TempModifier;
import com.momosoftworks.coldsweat.api.util.Temperature;
import com.momosoftworks.coldsweat.config.ConfigSettings;

import net.minecraft.world.entity.LivingEntity;

/**
 * Cold Sweat's world temperature, as felt under the murk. Names Cold Sweat classes, so it is only
 * ever loaded through {@link ColdSweatCompatibility}, behind its loaded check.
 *
 * <p>The murk is a cold, wet place, but not a freezer: what is taken is a share of the warmth above
 * the line where Cold Sweat starts to hurt (Config.murkColdness), not the temperature outright. A
 * world at {@code t} comes out at {@code min + (1 - coldness) * (t - min)}, {@code min} being Cold
 * Sweat's own minimum habitable temperature, so a tundra is exactly as cold as it was and everything
 * warmer loses that share of its lead over it -- never colder than the world already is, and never
 * warmer. Scaling the raw value instead would have warmed anything below zero, which Cold Sweat's
 * coldest biomes are.
 *
 * <p>Lives on every player, and does nothing while their eyes are above the surface: Cold Sweat keeps
 * a modifier list per entity and saves it, so one that is always present and decides for itself is
 * simpler and cheaper than adding and removing one as players cross the line.
 */
public final class MurkTempModifier extends TempModifier {

    private static final Function<Double, Double> UNCHANGED = t -> t;

    @Override
    protected Function<Double, Double> calculate(LivingEntity entity, Temperature.Trait trait) {
        if (trait != Temperature.Trait.WORLD || !Config.eyesUnderSurface(entity.level(), entity.getEyeY())) {
            return UNCHANGED;
        }
        double keep = 1.0 - Config.MURK_COLDNESS.get();
        if (keep >= 1.0) {
            return UNCHANGED;
        }
        double min = ConfigSettings.MIN_TEMP.get();
        return t -> t <= min ? t : min + keep * (t - min);
    }
}
