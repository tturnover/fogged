package com.fogged;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

// Makes players whose eyes are below the configured breathing boundary breathe as if underwater
// (air bar drains, then drowning) even when there is no water.
//
// We do this through NeoForge's LivingBreatheEvent rather than by editing the air bar directly: that
// is the same pipeline vanilla and diving-gear mods (e.g. Create's backtank) hook into. By forcing
// canBreathe=false here, the drowning behaviour kicks in AND any gear that supplies air -- which only
// acts when the pipeline reports "can't breathe" -- gets a chance to refill the bar. We run at HIGH
// priority so that gear (listening at the default priority) runs after us and can override the result.
@EventBusSubscriber(modid = Fogged.MODID)
public class BreathHandler {

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onBreathe(LivingBreatheEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // Leave the event untouched whenever the player should be breathing normally or vanilla already
        // handles it -- only force the underwater pipeline for a player standing in dry air below the
        // boundary. Touching any of these would wrongly drown a creative/spectator player, cancel a
        // water-breathing potion, or change how real-water drowning behaves.
        boolean handledElsewhere = player.isCreative()
                || player.isSpectator()
                || player.canBreatheUnderwater()
                || MobEffectUtil.hasWaterBreathing(player)
                || player.isEyeInFluid(FluidTags.WATER)
                || player.getEyeY() >= Config.breathHeight(player.level()) + Config.PLANE_SURFACE_OFFSET
                || BreatheSpheres.isBreathable(player.level(), player.getEyePosition()); // nozzle-filter sphere
        if (handledElsewhere) {
            return;
        }

        // A puff bush leaf pocket only pauses the clock -- it is a gap in the fog, not a source of air,
        // so it should not actively refill the bar the way a real breathable zone (above, or a
        // nozzle-filter sphere) does. Stay on the "can't breathe" branch (so nothing else tries to
        // regenerate air) but consume none of it.
        if (PuffLeafAir.isBreathable(player.level(), player.getEyePosition())) {
            event.setCanBreathe(false);
            event.setConsumeAirAmount(0);
            return;
        }

        // Below the dry boundary: drown as if underwater. NeoForge handles the air-bar decrement,
        // drowning damage and bubble particles from here. Respiration gives the vanilla chance to skip
        // the loss this tick; otherwise we drain at the configured rate.
        event.setCanBreathe(false);
        event.setConsumeAirAmount(skipLossFromRespiration(player) ? 0 : Config.AIR_LOSS_PER_TICK.getAsInt());
    }

    // The drowning we induce above uses vanilla's drown damage (so gear/air handling stays vanilla),
    // which would read "drowned". Re-stamp it as our fog suffocation -- but only for a player who is dry
    // and under the plane, never for genuine underwater drowning -- so the death message is the fog one
    // (and, if the lurker hit them recently, the "while fleeing the fog lurker" variant).
    @SubscribeEvent
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!event.getSource().is(DamageTypes.DROWN)) {
            return;
        }
        Level level = player.level();
        if (player.isEyeInFluid(FluidTags.WATER)
                || player.getEyeY() >= Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET) {
            return; // real water, or above the boundary -> leave vanilla drowning alone
        }
        event.setCanceled(true);
        player.hurt(ModDamageTypes.fogSuffocation(level), event.getAmount());
    }

    private static boolean skipLossFromRespiration(Player player) {
        Holder<Enchantment> respiration = player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.RESPIRATION);
        int level = EnchantmentHelper.getEnchantmentLevel(respiration, player);
        return level > 0 && player.getRandom().nextInt(level + 1) > 0;
    }
}
