package com.fogged;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fogged.block.UsedFilterBlock;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterColorHandlersEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.common.util.TriState;

/**
 * Bakes each block's own used filter ({@link UsedFilters}) into the chunk as that block's model, every
 * face tinted, and shades it by its pollution step with a block colour handler: white clean, all the
 * way to the grime colour clogged, as a filter in an intake is (AirIntakeRenderer). Baked like any
 * block, it is lit, culled and drawn at any distance as the terrain is, and shaded the moment its block
 * arrives, its step being in its state. Client only.
 */
public final class UsedFilterModels {

    /** The tint index every face of a used filter is baked with. */
    private static final int TINT = 0;

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(UsedFilterModels::onModifyBakingResult);
        modEventBus.addListener(UsedFilterModels::onBlockColors);
    }

    private static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        for (UsedFilterBlock used : UsedFilters.byOriginal().values()) {
            Block original = used.originalBlock();
            if (original == null) {
                continue;
            }
            BlockState originalState = original.defaultBlockState();
            BakedModel model = models.get(BlockModelShaper.stateToModelLocation(originalState));
            if (model == null) {
                continue;
            }
            BakedModel tinted = new Tinted(model, originalState);
            for (BlockState state : used.getStateDefinition().getPossibleStates()) {
                models.put(BlockModelShaper.stateToModelLocation(state), tinted);
            }
        }
    }

    private static void onBlockColors(RegisterColorHandlersEvent.Block event) {
        Block[] blocks = UsedFilters.byOriginal().values().toArray(Block[]::new);
        if (blocks.length == 0) {
            return;
        }
        event.register((state, level, pos, tintIndex) -> {
            float t = UsedFilterBlock.wear(state);
            int grime = Config.filterPollutionColor();
            int r = (int) (255 + (((grime >> 16) & 0xFF) - 255) * t);
            int g = (int) (255 + (((grime >> 8) & 0xFF) - 255) * t);
            int b = (int) (255 + ((grime & 0xFF) - 255) * t);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }, blocks);
    }

    /** The original block's model, asked as that block, with every face given the tint index. */
    private static final class Tinted extends BakedModelWrapper<BakedModel> {
        private final BlockState originalState;
        private final Map<BakedQuad, BakedQuad> tinted = new ConcurrentHashMap<>();

        Tinted(BakedModel original, BlockState originalState) {
            super(original);
            this.originalState = originalState;
        }

        private List<BakedQuad> tint(List<BakedQuad> quads) {
            List<BakedQuad> out = new ArrayList<>(quads.size());
            for (BakedQuad q : quads) {
                out.add(tinted.computeIfAbsent(q, k -> new BakedQuad(k.getVertices(), TINT, k.getDirection(),
                        k.getSprite(), k.isShade(), k.hasAmbientOcclusion())));
            }
            return out;
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand) {
            return tint(originalModel.getQuads(originalState, side, rand));
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data,
                RenderType renderType) {
            return tint(originalModel.getQuads(originalState, side, rand, data, renderType));
        }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
            return originalModel.getRenderTypes(originalState, rand, data);
        }

        @Override
        public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
            return originalModel.useAmbientOcclusion(originalState, data, renderType);
        }
    }

    private UsedFilterModels() {}
}
