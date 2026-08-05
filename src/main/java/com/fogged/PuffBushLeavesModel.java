package com.fogged;

import java.util.List;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * Puff bush leaves: 1 in 4 blocks render the rare fluff-tuft model instead of the plain cube. Vanilla's
 * per-position weighted-variant blockstate model picks purely off a position hash, so two vertically
 * stacked rare leaves could roll the identical look and z-fight. This wrapper keeps the rare-or-plain
 * roll random (still hashed off {@code rand}, same 1-in-4 odds as before) but forces WHICH rare look --
 * {@link #rare} or its mirror {@link #rareMirrored} -- by the block's Y coordinate parity, so two leaves
 * directly on top of each other always alternate and never draw the same overlapping geometry.
 */
public class PuffBushLeavesModel extends BakedModelWrapper<BakedModel> {
    private static final ModelProperty<Integer> Y_COORD = new ModelProperty<>();

    private final BakedModel rare;
    private final BakedModel rareMirrored;

    public PuffBushLeavesModel(BakedModel plain, BakedModel rare, BakedModel rareMirrored) {
        super(plain);
        this.rare = rare;
        this.rareMirrored = rareMirrored;
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        return modelData.derive().with(Y_COORD, pos.getY()).build();
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction direction, RandomSource rand, ModelData data,
            RenderType renderType) {
        if (rand.nextInt(4) != 0) {
            return originalModel.getQuads(state, direction, rand, data, renderType);
        }
        int y = data.has(Y_COORD) ? data.get(Y_COORD) : 0;
        BakedModel chosen = (y & 1) == 0 ? rare : rareMirrored;
        return chosen.getQuads(state, direction, rand, data, renderType);
    }
}
