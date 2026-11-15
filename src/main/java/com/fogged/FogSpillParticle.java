package com.fogged;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.ColorParticleOption;

/**
 * The foam that pours off the edge of the murk layer ({@link com.fogged.block.MurkBlock}), like the fog off
 * liquid nitrogen: heavier than air, it creeps outward along the ground, pours over a drop and slides
 * down it, swelling and thinning as it goes. The foam's campfire-smoke sprites ({@link FoamParticle}),
 * with none of that particle's lift or its death at the murk plane.
 */
public class FogSpillParticle extends TextureSheetParticle {

    private static final float SINK = 0.006F;       // gravity: it falls, slowly, as a heavy mist does
    private static final double AIR_DRAG = 0.96;
    private static final double GROUND_DRAG = 0.93;  // keeps creeping once it lands, unlike spray
    private static final int FADE_TICKS = 25;
    // Faint, and many of them: a bank of mist, not separate puffs.
    private static final float ALPHA = 0.3F;

    private final SpriteSet sprites;
    private final float startScale;

    protected FogSpillParticle(ClientLevel level, double x, double y, double z,
            double xd, double yd, double zd, SpriteSet sprites) {
        super(level, x, y, z);
        this.sprites = sprites;
        this.lifetime = 50 + this.random.nextInt(40);
        this.gravity = SINK;
        this.friction = 1.0F; // drag is applied below
        this.xd = xd;
        this.yd = yd;
        this.zd = zd;
        this.startScale = 0.9F + this.random.nextFloat() * 0.6F;
        this.quadSize *= this.startScale;
        setSize(0.2F, 0.2F);
        setSpriteFromAge(sprites);
    }

    @Override
    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        if (this.age++ >= this.lifetime) {
            remove();
            return;
        }
        this.yd -= this.gravity;
        move(this.xd, this.yd, this.zd);
        double drag = this.onGround ? GROUND_DRAG : AIR_DRAG;
        this.xd *= drag;
        this.zd *= drag;
        this.yd *= AIR_DRAG;
        // Swells as it spreads out.
        this.quadSize = 0.1F * this.startScale * (3.0F + 4.0F * this.age / (float) this.lifetime);
        setSpriteFromAge(this.sprites);
        int left = this.lifetime - this.age;
        if (left < FADE_TICKS) {
            this.alpha = ALPHA * left / FADE_TICKS;
        }
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
            FogSpillParticle p = new FogSpillParticle(level, x, y, z, xd, yd, zd, this.sprites);
            p.setColor(options.getRed(), options.getGreen(), options.getBlue());
            p.setAlpha(ALPHA);
            return p;
        }
    }
}
