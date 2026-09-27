package com.fogged;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

/**
 * The murk holds back what moves through it: mobs, players and dropped items lose a share of their
 * speed every tick while they are under the surface, the way a ship's hull does
 * ({@link SableBuoyancy}). Both read {@link Config#MURK_DRAG}, so a mob and a ship alongside it are
 * slowed alike.
 *
 * <p>Runs on both sides. The drag is a plain multiplier on delta movement, which is what vanilla water
 * does in {@code Entity.travel}, and the client has to apply it too or the player's own movement is
 * predicted without it and snaps back every time the server corrects them.
 *
 * <p>Drag only -- nothing here floats anything. What the murk lifts is the hull's float blocks, and a
 * mob has none.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class MurkDrag {
    private MurkDrag() {}

    // Below this the multiplier is not worth the write: it leaves the motion where it was, and writing
    // delta movement every tick for every entity in the world is not free.
    private static final double MIN_DRAG = 1.0e-4;

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        double perSecond = Config.MURK_DRAG.get();
        if (perSecond <= 0.0) {
            return;
        }
        Entity entity = event.getEntity();
        Level level = entity.level();
        if (!Config.dimensionEnabled(level)) {
            return;
        }
        // Water does its own dragging, and something swimming in a lake under the murk should be held
        // by the lake rather than by both at once.
        if (entity.isInWater() || entity.isInLava()) {
            return;
        }
        // A spectator is not in the world enough to be held by it, and a creative flier has asked not
        // to be: both would only feel this as the controls going vague.
        if (entity instanceof Player player && (player.isSpectator() || player.getAbilities().flying)) {
            return;
        }

        double submerged = submergedShare(entity.getBoundingBox(), FogBand.surfaceY(level));
        if (submerged <= 0.0) {
            return;
        }
        double drag = Math.min(1.0, perSecond * submerged / 20.0); // per tick, from the per-second value
        if (drag <= MIN_DRAG) {
            return;
        }
        Vec3 motion = entity.getDeltaMovement();
        entity.setDeltaMovement(motion.scale(1.0 - drag));
    }

    // How much of the box is inside the murk, 0 to 1 -- under the surface, or above it while flipFog has
    // put the murk overhead. A flat box (an item resting on the ground, a display) is either in or out,
    // with nothing in between to interpolate over.
    private static double submergedShare(AABB box, double surfaceY) {
        boolean flipped = Config.FLIP_FOG.getAsBoolean();
        double height = box.maxY - box.minY;
        if (height <= 1.0e-6) {
            return (flipped ? box.minY > surfaceY : box.minY < surfaceY) ? 1.0 : 0.0;
        }
        double inside = flipped ? box.maxY - surfaceY : surfaceY - box.minY;
        return inside <= 0.0 ? 0.0 : Math.min(1.0, inside / height);
    }
}
