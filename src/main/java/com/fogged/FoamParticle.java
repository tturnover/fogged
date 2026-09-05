package com.fogged;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ColorParticleOption;

/**
 * Client-side render for the {@code fogged:foam} particle: vanilla's cosy campfire smoke
 * ({@link net.minecraft.client.particle.CampfireSmokeParticle}) in shape and motion -- triple scale,
 * a slow wander, a fade at the end -- and the ballistics of Particular's waterfall spray (see the
 * constants below), tinted from the spawned {@link ColorParticleOption} so it carries the foam colour.
 *
 * <p>Its {@code foam_0..11} sprites are vanilla's campfire smoke frames, desaturated and with the
 * set's brightest pixel normalised to white (they run 102-136 luma originally, 191-254 after). The
 * shape is entirely in the alpha channel -- every drawn pixel is fully opaque -- so this keeps the
 * round, lumpy cloud and its internal shading exactly, and only lifts the grey a tint would otherwise
 * multiply into soot. Neither vanilla set works untouched: {@code big_smoke_*} is the right shape but
 * too dark to tint, and the explosion set's {@code generic_*} is bright but is not a cloud at all --
 * scattered single pixels that read as spray specks however large the quad. Cycled over the
 * particle's life, so the puff swells and thins as campfire smoke does.
 *
 * <p>Vanilla's campfire smoke is a {@code SimpleParticleType} with nowhere to put a colour, which is
 * why this type exists at all.
 *
 * <p>Distinct from {@code fogged:puff} (see {@link PuffParticle}), which stays a tintable POOF for
 * the nozzle filter: this is bigger, slower and much longer-lived, which suits foam lying on the
 * surface but would be wrong for a filter's breath.
 *
 * <p>Only referenced from the client provider registration (see {@code FoggedClient}); never loaded
 * on a dedicated server.
 */
public class FoamParticle extends TextureSheetParticle {

    private final SpriteSet sprites;

    // Campfire smoke lives 80-130 ticks, which for foam would leave the waterline permanently under
    // a bank of smoke. Short enough here that a wake reads as spray thrown and gone.
    private static final int LIFETIME_BASE = 30;
    private static final int LIFETIME_SPREAD = 20;
    // Ticks of alpha ramp at the end of life; vanilla fades over its last 60.
    private static final int FADE_TICKS = 20;

    // --- ballistics, from Particular's waterfall spray -------------------------------------------
    // That particle is a WaterDropParticle: it pops upward, arcs back down under gravity, sheds speed
    // to drag every tick, loses more of it and often dies on landing, and is absorbed the moment it
    // sinks below the surface it fell onto. Foam gets the same shape of motion at a fraction of the
    // energy -- a droplet may fling itself a block into the air, a bank of mist should barely lift.
    private static final double LIFT_MIN = 0.02;     // waterfall: 0.075
    private static final double LIFT_RANGE = 0.04;   // waterfall: 0.15
    private static final float ARC_GRAVITY = 0.004F; // waterfall: 0.06
    private static final double DRAG = 0.98;         // as the waterfall
    private static final double LANDED_DRAG = 0.7;   // as the waterfall
    private static final double SINK_DEPTH = 0.05;   // how far under the surface counts as absorbed

    protected FoamParticle(ClientLevel level, double x, double y, double z,
            double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        scale(3.0F);
        setSize(0.25F, 0.25F);
        this.lifetime = LIFETIME_BASE + this.random.nextInt(LIFETIME_SPREAD);
        this.gravity = ARC_GRAVITY;
        // The thrower's velocity is ADDED to the pop rather than replacing it, so a wake's outward
        // push and the lift compose -- the same way the waterfall spray takes its emitter's speed.
        this.xd = xd;
        this.yd = yd + LIFT_MIN + this.random.nextDouble() * LIFT_RANGE;
        this.zd = zd;
        this.sprites = sprites;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime || this.alpha <= 0.0F) {
            remove();
            return;
        }
        // Wander, so a bank of foam churns instead of drifting as one rigid sheet.
        this.xd += this.random.nextFloat() / 5000.0F * (this.random.nextBoolean() ? 1 : -1);
        this.zd += this.random.nextFloat() / 5000.0F * (this.random.nextBoolean() ? 1 : -1);
        this.yd -= this.gravity;
        move(this.xd, this.yd, this.zd);
        this.xd *= DRAG;
        this.yd *= DRAG;
        this.zd *= DRAG;
        if (this.onGround) {
            this.xd *= LANDED_DRAG;
            this.zd *= LANDED_DRAG;
        }
        setSpriteFromAge(this.sprites); // big cloud when thrown, thinning as it drifts
        if (this.age >= this.lifetime - FADE_TICKS && this.alpha > 0.01F) {
            this.alpha -= 1.0F / FADE_TICKS;
        }
        if (absorbed()) {
            remove();
        }
    }

    // Where the waterfall spray dies once it sinks into whatever it landed in, foam dies once it
    // settles back through the murk surface it was thrown off -- and, as there, once it falls inside
    // a block or a liquid, so a puff thrown against a cliff does not hang inside the stone.
    private boolean absorbed() {
        if (this.y < Config.effectSurfaceY(this.level) - SINK_DEPTH) {
            return true;
        }
        BlockPos pos = BlockPos.containing(this.x, this.y, this.z);
        double top = Math.max(
                this.level.getBlockState(pos).getCollisionShape(this.level, pos)
                        .max(Direction.Axis.Y, this.x - pos.getX(), this.z - pos.getZ()),
                this.level.getFluidState(pos).getHeight(this.level, pos));
        return top > 0.0 && this.y < pos.getY() + top;
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    public static class Provider implements ParticleProvider<ColorParticleOption> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(ColorParticleOption options, ClientLevel level,
                double x, double y, double z, double xd, double yd, double zd) {
            FoamParticle particle = new FoamParticle(level, x, y, z, xd, yd, zd, this.sprites);
            particle.setColor(options.getRed(), options.getGreen(), options.getBlue());
            particle.setAlpha(0.9F); // as vanilla's cosy smoke does
            return particle;
        }
    }
}
