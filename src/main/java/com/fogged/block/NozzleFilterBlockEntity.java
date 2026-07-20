package com.fogged.block;

import com.fogged.BreatheSpheres;
import com.fogged.Config;
import com.fogged.CreateFan;
import com.fogged.NozzleFilterEvents;
import com.fogged.PlaneSensor;
import com.fogged.registry.ModBlockEntities;
import com.fogged.registry.ModParticles;

import org.joml.Vector3f;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the breathing sphere for a {@link NozzleFilterBlock}. Every {@link #UPDATE_INTERVAL} ticks it
 * reads the attached fan's speed (via the reflection-only {@link CreateFan}) and maps it to a sphere
 * radius, which it publishes to {@link BreatheSpheres}. A stopped/absent fan yields radius 0 (no sphere).
 */
public class NozzleFilterBlockEntity extends BlockEntity {

    /** Sphere radius bounds, in blocks. Range: 3 (slow fan) .. 16, reached at {@link #FULL_SPEED}. */
    public static final double MIN_RADIUS = 3.0;
    public static final double MAX_RADIUS = 16.0;
    /** Fan speed (RPM magnitude) at which the sphere saturates at {@link #MAX_RADIUS} -- half of Create's
     *  256 RPM top tier. */
    private static final float FULL_SPEED = 128.0F;
    /** Above this fan speed the filter overloads: it reverts to a plain nozzle and drops its puff. */
    private static final float OVERLOAD_SPEED = 128.0F;
    /** Recompute cadence. The fan's speed changes rarely, so a coarse interval is plenty. */
    private static final int UPDATE_INTERVAL = 10;

    private double currentRadius = 0.0;

    public NozzleFilterBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NOZZLE_FILTER.get(), pos, state);
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state, NozzleFilterBlockEntity be) {
        if ((level.getGameTime() % UPDATE_INTERVAL) != 0L) {
            return;
        }
        if (!Config.ITEMS_ENABLED.get()) {
            be.currentRadius = 0.0;
            BreatheSpheres.remove(level, pos);
            return;
        }
        // The nozzle points away from its fan, so the fan sits on the opposite side.
        Direction facing = state.getValue(NozzleFilterBlock.FACING);
        CreateFan.FanState fan = CreateFan.attachedFan(level, pos, facing.getOpposite());

        if (isBlowing(fan, facing)) {
            // Fan pushing air out through the filter blows the puff straight off: revert to a nozzle.
            NozzleFilterEvents.revertToNozzle(level, pos, facing);
            return;
        }

        // Otherwise only a sucking fan does anything; an idle/absent fan leaves the filter inert.
        if (!isSucking(fan, facing)) {
            be.currentRadius = 0.0;
            BreatheSpheres.remove(level, pos);
            return;
        }

        if (fan.speed() > OVERLOAD_SPEED) {
            // Too much airflow: the filter blows out, reverting to a plain nozzle and shedding its puff.
            NozzleFilterEvents.revertToNozzle(level, pos, facing);
            return;
        }

        double radius = radiusFor(fan.speed());
        be.currentRadius = radius;
        // World-space centre (handles Sable contraptions, whose blocks sit in a far-off plot while their
        // riders are ordinary world-space entities).
        BreatheSpheres.set(level, pos, PlaneSensor.worldCenter(level, pos), radius);
    }

    /** Breathing-sphere radius for a given (sucking) fan speed. */
    private static double radiusFor(float speed) {
        double t = Math.min(speed, FULL_SPEED) / FULL_SPEED;
        return Mth.clamp(MIN_RADIUS + t * (MAX_RADIUS - MIN_RADIUS), MIN_RADIUS, MAX_RADIUS);
    }

    /**
     * True when the fan is sucking air in through the filter. The filter's FACING points away from its
     * fan (out the nozzle mouth), so air flowing toward the fan -- i.e. in the {@code FACING.opposite()}
     * direction -- means it is being pulled inward. A blowing fan flows the other way and is ignored.
     */
    private static boolean isSucking(CreateFan.FanState fan, Direction facing) {
        return fan.speed() > 0.0F && fan.airFlow() == facing.getOpposite();
    }

    /**
     * True when the fan is actively pushing air out through the filter (airflow along the filter's
     * FACING, away from the fan). This blows the puff off, so the filter reverts to a bare nozzle. An
     * idle fan (speed 0, no airflow) is neither sucking nor blowing and leaves the filter alone.
     */
    private static boolean isBlowing(CreateFan.FanState fan, Direction facing) {
        return fan.speed() > 0.0F && fan.airFlow() == facing;
    }

    /** How far from the block the air particles spawn (blocks). Kept close, as Create's nozzle does. */
    private static final double PARTICLE_SPREAD = 1.6;
    /** Inward drift speed of each particle -- they get sucked toward the filter. */
    private static final double PARTICLE_PULL = 0.18;
    /** Tint used above the fog plane (below the plane uses the foam colour). */
    private static final float[] WHITE = {1.0F, 1.0F, 1.0F};

    /** How near a player must be to the sphere surface (blocks) before the edge is dusted in. */
    private static final double EDGE_APPROACH = 4.0;
    /** Size of the little edge marker particles (smaller than the body puffs). */
    private static final float EDGE_SCALE = 0.6F;
    /** Edge markers spawned per nearby player per tick. */
    private static final int EDGE_MARKERS = 3;

    /**
     * Client-side: spawn the air puffs the nozzle used to make, pulled inward toward the block (the fan
     * is sucking). Density scales with fan speed; the puff colour is white above the fog plane and the
     * foam colour below. Also dusts the sphere edge for any nearby player (see {@link #edgeMarkers}).
     * Silent while the fan is stopped or over the overload threshold (the filter is about to revert).
     */
    public static void clientTick(Level level, BlockPos pos, BlockState state, NozzleFilterBlockEntity be) {
        if (!Config.ITEMS_ENABLED.get()) {
            return;
        }
        Direction facing = state.getValue(NozzleFilterBlock.FACING);
        CreateFan.FanState fan = CreateFan.attachedFan(level, pos, facing.getOpposite());
        if (!isSucking(fan, facing) || fan.speed() > OVERLOAD_SPEED) {
            return;
        }

        // World-space centre so particles appear on the contraption (not at its far-off Sable plot).
        Vec3 center = PlaneSensor.worldCenter(level, pos);
        // Our POOF-like puff, so it looks and behaves like the nozzle's own particle -- the ONLY
        // difference between over/under the plane is colour: plain white above, foam colour below (so it
        // reads as drawn-in murk). Compare against the true world height.
        float[] c = Config.fogged(level, PlaneSensor.worldY(level, pos)) ? Config.foamColor() : WHITE;
        ParticleOptions particle = ColorParticleOption.create(ModParticles.PUFF.get(), c[0], c[1], c[2]);

        RandomSource rand = level.random;
        int attempts = 1 + (int) (Math.min(fan.speed(), FULL_SPEED) / 48.0F); // faster fan -> more puffs
        for (int i = 0; i < attempts; i++) {
            if (rand.nextInt(2) != 0) {
                continue; // sparse, like the nozzle's own emission
            }
            Vec3 at = center.add(
                    (rand.nextDouble() - 0.5) * 2.0 * PARTICLE_SPREAD,
                    (rand.nextDouble() - 0.5) * 2.0 * PARTICLE_SPREAD,
                    (rand.nextDouble() - 0.5) * 2.0 * PARTICLE_SPREAD);
            Vec3 pull = center.subtract(at).normalize().scale(PARTICLE_PULL); // sucked inward
            level.addParticle(particle, at.x, at.y, at.z, pull.x, pull.y, pull.z);
        }

        // Trace the sphere edge for any player who has come near it, so the breathable boundary shows.
        edgeMarkers(level, center, radiusFor(fan.speed()), c, rand);
    }

    /**
     * Dust little marker particles onto the sphere surface in front of each player who is within
     * {@link #EDGE_APPROACH} of the boundary, revealing where the breathable zone ends as they approach.
     */
    private static void edgeMarkers(Level level, Vec3 center, double radius, float[] color, RandomSource rand) {
        ParticleOptions marker = new DustParticleOptions(new Vector3f(color[0], color[1], color[2]), EDGE_SCALE);
        for (Player player : level.players()) {
            Vec3 toPlayer = player.getEyePosition().subtract(center);
            double dist = toPlayer.length();
            if (dist < 1.0E-3 || Math.abs(dist - radius) > EDGE_APPROACH) {
                continue; // not near the boundary
            }
            Vec3 dir = toPlayer.scale(1.0 / dist);
            for (int k = 0; k < EDGE_MARKERS; k++) {
                // Jitter the direction slightly, then project back onto the surface so markers sit on the
                // sphere just around the player's line to the centre.
                Vec3 jittered = dir.add(
                        (rand.nextDouble() - 0.5) * 0.4,
                        (rand.nextDouble() - 0.5) * 0.4,
                        (rand.nextDouble() - 0.5) * 0.4).normalize();
                Vec3 on = center.add(jittered.scale(radius));
                level.addParticle(marker, on.x, on.y, on.z, 0.0, 0.0, 0.0);
            }
        }
    }


    public double currentRadius() {
        return currentRadius;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level != null) {
            BreatheSpheres.remove(level, worldPosition);
        }
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        if (level != null) {
            BreatheSpheres.remove(level, worldPosition);
        }
    }
}
