package com.fogged;

import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.entity.player.Player;
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

        int loss = Config.AIR_LOSS_PER_TICK.getAsInt();
        int last = ENFORCED_AIR.getOrDefault(player, player.getAirSupply());
        // Ignore any air vanilla added this tick by clamping to our last enforced value.
        int air = Math.min(player.getAirSupply(), last) - loss;

        if (air <= -20) {
            air = 0;
            player.setAirSupply(0);
            player.hurt(player.damageSources().drown(), 2.0F);
        } else {
            player.setAirSupply(air);
        }
        ENFORCED_AIR.put(player, air);
    }
}
