package com.fogged;

import com.fogged.registry.ModParticles;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Specks adrift inside the murk: the same {@code fogged:puff} the nozzle filter breathes out
 * ({@code NozzleFilterBlockEntity}), cast into the air around the camera while it is on the murk side,
 * so being under the boundary reads as being inside something rather than as a colour over the lens.
 *
 * <p>Client only, and purely decorative -- nothing here is sent anywhere or affects the world.
 *
 * <p>Cast around the camera rather than seeded onto the world, because the murk is a volume and not a
 * surface: there is nothing for a speck to sit on, and what the camera can see of it moves with the
 * camera. Anything inside a block is dropped instead of relocated, so no speck is ever pushed
 * somewhere it was not going to be and the density falls off naturally in tight spaces.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class MurkMotes {
    private MurkMotes() {}

    // Nothing is cast nearer than this: a puff at arm's length is a blot across the screen, and the
    // camera is where the murk is thickest anyway.
    private static final double MIN_RANGE = 1.5;

    // Share of the fog distance specks are kept within. Past roughly this the murk has taken them to
    // its own colour and they cost a particle to draw nothing.
    private static final double FOG_SHARE = 0.6;

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.isPaused()) {
            return;
        }
        int perTick = Config.MOTES_PER_TICK.getAsInt();
        if (perTick <= 0 || !Config.dimensionEnabled(level)) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        if (!Config.fogged(level, cam.y)) {
            return; // the camera is on the dry side: the murk is a surface over there, not a place
        }

        double range = Math.min(Config.MOTES_RANGE.get(), Config.FOG_DISTANCE.getAsInt() * FOG_SHARE);
        if (range <= MIN_RANGE) {
            return;
        }
        float[] colour = Config.foamColor();
        ParticleOptions mote = ColorParticleOption.create(ModParticles.PUFF.get(),
                colour[0], colour[1], colour[2]);
        double drift = Config.MOTES_DRIFT.get();
        RandomSource rand = level.random;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();

        for (int i = 0; i < perTick; i++) {
            double dx = (rand.nextDouble() * 2.0 - 1.0) * range;
            double dy = (rand.nextDouble() * 2.0 - 1.0) * range;
            double dz = (rand.nextDouble() * 2.0 - 1.0) * range;
            if (dx * dx + dy * dy + dz * dz < MIN_RANGE * MIN_RANGE) {
                continue; // in the camera's face
            }
            double x = cam.x + dx;
            double y = cam.y + dy;
            double z = cam.z + dz;
            // The box reaches past the boundary near it, so each speck is checked rather than the
            // camera alone: none of them hangs in clear air above the surface.
            if (!Config.fogged(level, y)) {
                continue;
            }
            at.set((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
            if (!level.getBlockState(at).isAir()) {
                continue; // inside a block, or in water, which has its own murk
            }
            // Mostly sideways, sinking a little: the murk settles rather than boils (the vapour layer
            // is what boils -- see FogVapor).
            level.addParticle(mote, x, y, z,
                    (rand.nextDouble() * 2.0 - 1.0) * drift,
                    -rand.nextDouble() * drift * 0.5,
                    (rand.nextDouble() * 2.0 - 1.0) * drift);
        }
    }
}
