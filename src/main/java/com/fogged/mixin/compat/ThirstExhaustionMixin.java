package com.fogged.mixin.compat;

import com.fogged.Config;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.world.entity.player.Player;

/**
 * Thirst Was Taken's thirst, under the murk. The murk is a wet place, and a player breathing it loses
 * water at a fraction of the usual rate (Config.murkThirstScale).
 *
 * <p>{@code PlayerThirst.addExhaustion(Player, float)} is the one door every source of thirst goes
 * through -- movement, healing, the biome and fire-protection multipliers are all applied inside it
 * to the amount handed in -- so scaling that amount on the way in scales the lot, and nothing the mod
 * does to it afterwards is disturbed. A single modifier on a single argument; there is no event.
 *
 * <p>Lives in {@code fogged.thirst.mixins.json}, which is not required: with the mod absent the target
 * class is never loaded and this never applies.
 */
@Mixin(targets = "dev.ghen.thirst.content.thirst.PlayerThirst", remap = false)
public abstract class ThirstExhaustionMixin {

    @ModifyVariable(method = "addExhaustion", at = @At("HEAD"), argsOnly = true, remap = false)
    private float fogged$murkThirst(float amount, Player player) {
        if (!Config.eyesUnderSurface(player.level(), player.getEyeY())) {
            return amount;
        }
        return amount * (float) (double) Config.MURK_THIRST_SCALE.get();
    }
}
