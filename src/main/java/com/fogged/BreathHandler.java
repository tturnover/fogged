package com.fogged;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingBreatheEvent;

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
                || player.getEyeY() >= Config.BREATH_HEIGHT.get() + Config.PLANE_SURFACE_OFFSET;
        if (handledElsewhere) {
            return;
        }

        // Below the dry boundary: drown as if underwater. NeoForge handles the air-bar decrement,
        // drowning damage and bubble particles from here. Respiration gives the vanilla chance to skip
        // the loss this tick; otherwise we drain at the configured rate.
        event.setCanBreathe(false);
        event.setConsumeAirAmount(skipLossFromRespiration(player) ? 0 : Config.AIR_LOSS_PER_TICK.getAsInt());
    }

    private static boolean skipLossFromRespiration(Player player) {
        Holder<Enchantment> respiration = player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.RESPIRATION);
        int level = EnchantmentHelper.getEnchantmentLevel(respiration, player);
        return level > 0 && player.getRandom().nextInt(level + 1) > 0;
    }
}
