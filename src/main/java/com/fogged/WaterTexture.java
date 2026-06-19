package com.fogged;

import org.lwjgl.opengl.GL11;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.renderer.texture.DynamicTexture;

// Procedurally builds a single, seamlessly-tiling water/foam texture once and keeps it on the GPU.
// It is sampled NEAREST with world-space UVs (see fog_plane.fsh) so its texels line up with the block
// grid: TILE_BLOCKS blocks span SIZE texels => 16 texels per block.
//
//   R channel = water shading (animated surface shimmer)
//   G channel = sparse foam highlights (used to break up the edge foam into world-aligned pixels)
public final class WaterTexture {

    public static final int SIZE = 64;       // texels per tile edge
    public static final int TILE_BLOCKS = 4; // SIZE / TILE_BLOCKS = 16 texels per block

    private static DynamicTexture texture;

    private WaterTexture() {
    }

    // Lazily builds (on the render thread) and returns the GL texture id.
    public static int textureId() {
        if (texture == null) {
            build();
        }
        return texture.getId();
    }

    private static void build() {
        NativeImage img = new NativeImage(NativeImage.Format.RGBA, SIZE, SIZE, false);
        int cells = 8; // lowest-frequency lattice cells across the tile
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                // R = smooth water shading (animated shimmer); contrast is applied in the shader.
                float water = fbm(x, y, cells, 4);
                // G = per-texel white noise so the foam breaks into crisp, individual block-grid pixels.
                float foam = hash(x, y, SIZE);
                int r = clamp255(water);
                int g = clamp255(foam);
                // NativeImage packs as ABGR (0xAABBGGRR).
                img.setPixelRGBA(x, y, 0xFF000000 | (g << 8) | r);
            }
        }

        texture = new DynamicTexture(img);
        texture.setFilter(false, false); // NEAREST, no mipmaps -> crisp pixels

        // Wrap REPEAT so the world-space UVs tile seamlessly across the whole plane.
        GlStateManager._bindTexture(texture.getId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
    }

    // --- seamlessly-tiling value-noise fbm (period doubles with each octave so edges always match) ---

    private static float hash(int x, int y, int period) {
        x = Math.floorMod(x, period);
        y = Math.floorMod(y, period);
        int h = x * 374761393 + y * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        h = h ^ (h >>> 16);
        return (h & 0xFFFF) / 65535.0F;
    }

    private static float vnoise(float fx, float fy, int period) {
        int x0 = (int) Math.floor(fx);
        int y0 = (int) Math.floor(fy);
        float a = hash(x0, y0, period);
        float b = hash(x0 + 1, y0, period);
        float c = hash(x0, y0 + 1, period);
        float d = hash(x0 + 1, y0 + 1, period);
        float ux = smooth(fx - x0);
        float uy = smooth(fy - y0);
        return lerp(lerp(a, b, ux), lerp(c, d, ux), uy);
    }

    private static float fbm(int px, int py, int cells, int octaves) {
        float v = 0.0F;
        float amp = 0.5F;
        float norm = 0.0F;
        int period = cells;
        for (int i = 0; i < octaves; i++) {
            float fx = (px / (float) SIZE) * period;
            float fy = (py / (float) SIZE) * period;
            v += amp * vnoise(fx, fy, period);
            norm += amp;
            amp *= 0.5F;
            period *= 2;
        }
        return v / norm;
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    private static int clamp255(float v) {
        int i = (int) (v * 255.0F + 0.5F);
        return i < 0 ? 0 : Math.min(i, 255);
    }
}
