package com.fogged;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

// Drains the air bar (and drowns) players whose eyes are below the configured breathing boundary,
// mimicking the vanilla "underwater" behaviour even when there is no water.
@EventBusSubscriber(modid = Fogged.MODID)
public class BreathHandler {

    // The air value we last enforced per player. Vanilla regenerates air during the entity tick
    // (which runs *before* this Post handler) whenever the player is not in water, so we use this
    // to ignore that regen and keep the air strictly decreasing while below the boundary.
    private static final Map<Player, Integer> ENFORCED_AIR = new WeakHashMap<>();

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide) {
            return;
        }

        boolean breathable = player.isCreative()
                || player.isSpectator()
                || player.canBreatheUnderwater()
                || MobEffectUtil.hasWaterBreathing(player)
                // already submerged -> let vanilla drowning take over
                || player.isEyeInFluid(FluidTags.WATER)
                // eyes above the visible surface -> normal air
                || player.getEyeY() >= Config.BREATH_HEIGHT.get() + Config.PLANE_SURFACE_OFFSET;

        if (breathable) {
            ENFORCED_AIR.remove(player);
            return;
        }

        int last = ENFORCED_AIR.getOrDefault(player, player.getAirSupply());
        // Ignore any air vanilla added this tick by clamping to our last enforced value.
        int base = Math.min(player.getAirSupply(), last);

        // Mirror vanilla LivingEntity#decreaseAirSupply: Respiration gives a level/(level+1) chance
        // to skip the loss this tick, so the air bar drains exactly as it would underwater.
        int air;
        if (skipLossFromRespiration(player)) {
            air = base;
        } else {
            air = base - Config.AIR_LOSS_PER_TICK.getAsInt();
        }

        if (air <= -20) {
            air = 0;
            player.setAirSupply(0);
            spawnDrownParticles(player);
            player.hurt(player.damageSources().drown(), 2.0F);
        } else {
            player.setAirSupply(air);
        }
        ENFORCED_AIR.put(player, air);
    }

    private static boolean skipLossFromRespiration(Player player) {
        Holder<Enchantment> respiration = player.level().registryAccess()
                .lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.RESPIRATION);
        int level = EnchantmentHelper.getEnchantmentLevel(respiration, player);
        return level > 0 && player.getRandom().nextInt(level + 1) > 0;
    }

    // Vanilla spawns 8 bubble particles each time the air bar bottoms out and deals drown damage.
    private static void spawnDrownParticles(Player player) {
        if (player.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.BUBBLE,
                    player.getX(), player.getEyeY() - 0.3, player.getZ(),
                    8, 0.25, 0.25, 0.25, 0.0);
        }
    }
}
