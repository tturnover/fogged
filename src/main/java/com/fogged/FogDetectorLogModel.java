package com.fogged;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fogged.block.FogDetectorBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Retextures the pole's log quads to the column's chosen stripped log (carried as {@link
 * FogDetectorBlock#LOG_ID} model data), so any stripped log works without a fixed blockstate enum. Quads
 * authored with the {@code stripped_oak_log} placeholder are re-sprited to the stored log's block texture.
 */
public class FogDetectorLogModel extends BakedModelWrapper<BakedModel> {
    private static final ResourceLocation POLE_PLACEHOLDER = ResourceLocation.withDefaultNamespace("block/stripped_oak_log");

    // BLOCK vertex format: 8 ints per vertex, UV0 at int offset 4.
    private static final int VERTEX_INTS = 8;
    private static final int UV_OFFSET = 4;

    private final Map<String, List<BakedQuad>> cache = new ConcurrentHashMap<>();

    public FogDetectorLogModel(BakedModel original) {
        super(original);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        ResourceLocation logId = data.get(FogDetectorBlock.LOG_ID);
        if (logId == null || logId.equals(FogDetectorBlock.DEFAULT_LOG)) {
            return quads; // default oak: the authored sprite already matches
        }
        String key = logId + "|" + (side == null ? "*" : side.getSerializedName());
        return cache.computeIfAbsent(key, k -> retexture(quads, logId));
    }

    private List<BakedQuad> retexture(List<BakedQuad> quads, ResourceLocation logId) {
        TextureAtlasSprite target = sprite(ResourceLocation.fromNamespaceAndPath(logId.getNamespace(), "block/" + logId.getPath()));
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            if (quad.getSprite().contents().name().equals(POLE_PLACEHOLDER)) {
                out.add(reSprite(quad, quad.getSprite(), target));
            } else {
                out.add(quad);
            }
        }
        return out;
    }

    // Remap a quad's UVs from one sprite's atlas region into another's.
    private static BakedQuad reSprite(BakedQuad quad, TextureAtlasSprite from, TextureAtlasSprite to) {
        int[] verts = quad.getVertices().clone();
        for (int i = 0; i < 4; i++) {
            int o = i * VERTEX_INTS + UV_OFFSET;
            float u = Float.intBitsToFloat(verts[o]);
            float v = Float.intBitsToFloat(verts[o + 1]);
            float fu = (u - from.getU0()) / (from.getU1() - from.getU0());
            float fv = (v - from.getV0()) / (from.getV1() - from.getV0());
            verts[o] = Float.floatToRawIntBits(to.getU(fu));
            verts[o + 1] = Float.floatToRawIntBits(to.getV(fv));
        }
        return new BakedQuad(verts, quad.getTintIndex(), quad.getDirection(), to, quad.isShade());
    }

    private static TextureAtlasSprite sprite(ResourceLocation texture) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
    }
}
