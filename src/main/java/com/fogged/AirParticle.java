package com.fogged;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.util.Mth;

/**
 * Client-side render for {@code fogged:air}: a speck of clean air out of an air compressor. Unlike
 * the puff it is small and lives for a couple of seconds, coasting on the velocity it was given with
 * little friction, so a line of them reads as air flowing -- through a room as it fills, or down the
 * way to a leak and out of the hole. It collides with blocks, so it stops against a wall rather than
 * drawing through it; that also keeps the specks inside a sealed room.
 *
 * <p>Fades in and out over its life so a stream of them never pops. Only referenced from the client
 * provider registration (see {@code FoggedClient}).
 */
public class AirParticle extends TextureSheetParticle {
    private final SpriteSet sprites;
    private final float baseAlpha;

    protected AirParticle(ClientLevel level, double x, double y, double z,
            double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        this.sprites = sprites;
        this.gravity = 0.0F;
        this.friction = 0.97F;
        this.hasPhysics = true;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.quadSize = 0.06F + this.random.nextFloat() * 0.04F;
        this.lifetime = 30 + this.random.nextInt(20);
        this.baseAlpha = 0.85F;
        this.alpha = 0.0F;
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        setSpriteFromAge(sprites);
        float life = (float) age / lifetime;
        // In over the first fifth, out over the last third.
        alpha = baseAlpha * Mth.clamp(Math.min(life * 5.0F, (1.0F - life) * 3.0F), 0.0F, 1.0F);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    /** Builds an {@link AirParticle} from a {@link ColorParticleOption}, applying its tint. */
    public static class Provider implements ParticleProvider<ColorParticleOption> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(ColorParticleOption options, ClientLevel level,
                double x, double y, double z, double xd, double yd, double zd) {
            AirParticle particle = new AirParticle(level, x, y, z, xd, yd, zd, sprites);
            particle.setColor(options.getRed(), options.getGreen(), options.getBlue());
            return particle;
        }
    }
}
