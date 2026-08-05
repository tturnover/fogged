package com.fogged;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

// Keeps the murk under the fog hostile to anything that does not belong there. Mobs whose type is not
// in Config.ALLOWED_MOBS cannot spawn on the fogged side, and any that get there (wandered in, summoned,
// allow-list edited) suffocate: after a delay they take damage every second until they leave or die.
//
// Players are never touched here -- their breathing is handled by BreathHandler. Allowed mobs are left
// entirely alone. Everything runs server-side only (spawns and the authoritative entity tick).
@EventBusSubscriber(modid = Fogged.MODID)
public class MobSuppressor {

    // Per-entity persistent counter (ticks spent submerged in the murk), so the timer survives chunk
    // reloads and rides along with the entity rather than living in a side map.
    private static final String SUBMERGED_TICKS = Fogged.MODID + ":murkTicks";

    // Block natural/structure/spawner spawns of non-allowed mobs on the fogged side of the boundary.
    @SubscribeEvent
    static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!Config.mobSuffocationEnabled()) {
            return;
        }
        Mob mob = event.getEntity();
        Level level = mob.level();
        if (Config.fogged(level, event.getY()) && !Config.mobAllowedUnderFog(mob.getType())) {
            event.setSpawnCancelled(true);
        }
    }

    // Drown out non-allowed mobs that are under the fog: count up while submerged, damage past the delay.
    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (!Config.mobSuffocationEnabled()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob) || mob.level().isClientSide) {
            return;
        }

        Level level = mob.level();
        boolean inMurk = Config.fogged(level, mob.getEyeY())
                && !Config.mobAllowedUnderFog(mob.getType())
                && !BreatheSpheres.isBreathable(level, mob.getEyePosition()) // nozzle-filter sphere shelters mobs too
                && !PuffLeafAir.isBreathable(level, mob.getEyePosition()); // puff bush leaf pocket shelters mobs too
        if (!inMurk) {
            if (mob.getPersistentData().contains(SUBMERGED_TICKS)) {
                mob.getPersistentData().remove(SUBMERGED_TICKS);
            }
            return;
        }

        int ticks = mob.getPersistentData().getInt(SUBMERGED_TICKS) + 1;
        mob.getPersistentData().putInt(SUBMERGED_TICKS, ticks);

        int delayTicks = Config.MOB_SUFFOCATE_DELAY.getAsInt() * 20;
        double damage = Config.MOB_SUFFOCATE_DAMAGE.get();
        // Once past the delay, apply the per-second damage on each whole-second boundary.
        if (ticks > delayTicks && (ticks - delayTicks) % 20 == 0 && damage > 0.0) {
            mob.hurt(ModDamageTypes.fogSuffocation(level), (float) damage);
        }
    }
}
