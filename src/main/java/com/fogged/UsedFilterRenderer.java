package com.fogged;

import com.fogged.block.UsedFilterBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws a generic used filter (one with no used filter of its own block; the others are baked into the
 * chunk, UsedFilterModels) as the block it is -- its own model, lighting and shading -- with
 * every texel shaded toward the murk's colour as it wears, the way a filter in an intake is
 * (AirIntakeRenderer): white when clean, all the way to the grime colour clogged.
 */
public class UsedFilterRenderer implements BlockEntityRenderer<UsedFilterBlockEntity> {

    public UsedFilterRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(UsedFilterBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers,
            int packedLight, int packedOverlay) {
        if (be.getBlockState().getBlock() instanceof com.fogged.block.UsedFilterBlock used && used.standsInForOne()) {
            return; // baked into the chunk as its block's own model (UsedFilterModels)
        }
        Level level = be.getLevel();
        BlockState original = be.original();
        if (level == null || original.getRenderShape() != RenderShape.MODEL) {
            return;
        }
        float t = be.wear();
        int grime = Config.filterPollutionColor();
        int r = (int) (255 + (((grime >> 16) & 0xFF) - 255) * t);
        int g = (int) (255 + (((grime >> 8) & 0xFF) - 255) * t);
        int b = (int) (255 + ((grime & 0xFF) - 255) * t);
        BlockRenderDispatcher blocks = Minecraft.getInstance().getBlockRenderer();
        BakedModel model = blocks.getBlockModel(original);
        BlockPos pos = be.getBlockPos();
        long seed = original.getSeed(pos);
        for (RenderType type : model.getRenderTypes(original, RandomSource.create(seed), ModelData.EMPTY)) {
            blocks.getModelRenderer().tesselateBlock(level, model, original, pos, pose,
                    new Tinted(buffers.getBuffer(type), r, g, b), true, RandomSource.create(), seed, packedOverlay,
                    ModelData.EMPTY, type);
        }
    }

    // It stands in for a block of the terrain: drawn as far as the terrain is, not just the 64 blocks a
    // block entity renderer gets, or a hole would open in a wall of them at a distance.
    @Override
    public int getViewDistance() {
        return 512;
    }

    /** Passes vertices on with their colour multiplied by a tint. */
    private record Tinted(VertexConsumer out, int r, int g, int b) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            out.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            out.setColor(red * r / 255, green * g / 255, blue * b / 255, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            out.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            out.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            out.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            out.setNormal(x, y, z);
            return this;
        }
    }
}
