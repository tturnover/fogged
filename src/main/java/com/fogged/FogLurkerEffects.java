package com.fogged;

import com.fogged.entity.FogLurker;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Client warning effect: a faint camera shake while a {@link FogLurker} is near, growing as it closes
 * in and harder still while it lunges. Purely a nudge to the render camera angles, so it never fights
 * the player's own look input.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class FogLurkerEffects {
    private FogLurkerEffects() {}

    @SubscribeEvent
    static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        double radius = Config.LURKER_SHAKE_RADIUS.get();
        if (radius <= 0.0) {
            return;
        }

        double best = Double.MAX_VALUE;
        boolean lunging = false;
        float pulse = 0.0F;
        AABB area = mc.player.getBoundingBox().inflate(radius);
        for (FogLurker lurker : mc.level.getEntitiesOfClass(FogLurker.class, area)) {
            double d = lurker.distanceTo(mc.player);
            if (d < best) {
                best = d;
            }
            if (lurker.state() == FogLurker.State.LUNGE) {
                lunging = true;
            }
            pulse = Math.max(pulse, lurker.shakePulse((float) event.getPartialTick()));
        }
        if (best == Double.MAX_VALUE) {
            return;
        }

        float prox = (float) Mth.clamp(1.0 - best / radius, 0.0, 1.0);
        // Faint constant proximity warning, plus the one-second per-block pulse (doubling each block).
        float amp = prox * prox * (lunging ? 2.5F : 1.0F) + pulse * prox * 1.6F;
        if (amp < 0.01F) {
            return;
        }
        float t = ((float) mc.level.getGameTime() + (float) event.getPartialTick()) * (lunging ? 1.6F : 0.7F);
        event.setRoll(event.getRoll() + Mth.sin(t * 7.0F) * amp * 0.6F);
        event.setPitch(event.getPitch() + Mth.sin(t * 11.0F) * amp * 0.4F);
        event.setYaw(event.getYaw() + Mth.cos(t * 5.0F) * amp * 0.4F);
    }
}
