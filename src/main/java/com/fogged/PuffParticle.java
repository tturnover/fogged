package com.fogged;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ColorParticleOption;

/**
 * Client-side render for the {@code fogged:puff} particle: a colour-tintable copy of vanilla's
 * {@code POOF} ({@link net.minecraft.client.particle.ExplodeParticle}). Same no-gravity drifting puff
 * that cycles the {@code generic_*} sprites over its life, but its RGB comes from the spawned
 * {@link ColorParticleOption}, so the nozzle filter can emit white above the fog plane and foam-colour
 * below with identical behaviour.
 *
 * <p>Only referenced from the client provider registration (see {@code FoggedClient}); never loaded on
 * a dedicated server.
 */
public class PuffParticle extends TextureSheetParticle {
    private final SpriteSet sprites;

    protected PuffParticle(ClientLevel level, double x, double y, double z,
            double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z, 0.0, 0.0, 0.0);
        this.sprites = sprites;
        this.gravity = 0.0F;
        this.friction = 0.9F;
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.quadSize *= 1.5F;
        this.lifetime = 8 + this.random.nextInt(4);
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        super.tick();
        setSpriteFromAge(sprites);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_OPAQUE;
    }

    /** Builds a {@link PuffParticle} from a {@link ColorParticleOption}, applying its tint. */
    public static class Provider implements ParticleProvider<ColorParticleOption> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(ColorParticleOption options, ClientLevel level,
                double x, double y, double z, double xd, double yd, double zd) {
            PuffParticle particle = new PuffParticle(level, x, y, z, xd, yd, zd, sprites);
            particle.setColor(options.getRed(), options.getGreen(), options.getBlue());
            return particle;
        }
    }
}
