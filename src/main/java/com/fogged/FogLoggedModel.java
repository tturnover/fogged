package com.fogged;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fogged.block.MurkBlock;
import com.fogged.registry.ModBlocks;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.MultiPartBakedModel;
import net.minecraft.client.resources.model.SimpleBakedModel;
import net.minecraft.client.resources.model.WeightedBakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import net.neoforged.neoforge.common.util.TriState;

/**
 * "Fog-logging": the murk inside a cell some other block already holds. Murk blocks ({@link MurkLayer})
 * only go into air, so without this a fence, a slab, a flower, a torch standing in the layer would cut
 * a hole in it the shape of its whole cell. Wrapped round the model of every ordinary block, this adds
 * the murk's own quads to the block's when it stands in the layer and does not fill its cell, so the
 * murk runs on through it and the part of the block under the surface is hidden as it would be in
 * the open.
 *
 * <p>Nothing is stored: the world, its block states and its saves are untouched. How full the cell is
 * comes from the murk round it -- full if murk lies over it, else as high as the murk beside it -- and
 * that murk is the server's, so the fog in the cell moves with the swell: every step of a wave changes
 * the murk next to it, which rebuilds its chunk section, which asks this model again.
 *
 * <p>Only vanilla's own model classes are wrapped (see {@link #wraps}). A model of another mod's making
 * may draw itself some other way -- connected textures, Fabric's renderer API -- that a wrapper would
 * quietly lose, and such a block simply goes without fog in its cell.
 */
public class FogLoggedModel extends BakedModelWrapper<BakedModel> {

    private record Fog(BlockState murk, ModelData murkData, int faces) {}

    private static final ModelProperty<Fog> FOG = new ModelProperty<>();
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final Direction[] FACES = Direction.values();

    // Murk quads with the murk's tint baked into their vertex colour: the block they are drawn as part of
    // has its own colour handler (grass, leaves), which would otherwise tint them. Rebuilt on reload.
    private static final Map<BakedQuad, BakedQuad> TINTED = new ConcurrentHashMap<>();

    // Vertex colour sits at int 3 of each vertex in the BLOCK format (8 ints per vertex).
    private static final int VERTEX_INTS = 8;
    private static final int COLOR_OFFSET = 3;

    public FogLoggedModel(BakedModel original) {
        super(original);
    }

    /** Whether a baked block model is one of vanilla's plain kinds, and so safe to wrap. */
    public static boolean wraps(BakedModel model) {
        Class<?> c = model.getClass();
        return c == SimpleBakedModel.class || c == MultiPartBakedModel.class || c == WeightedBakedModel.class;
    }

    /** Called when models are baked again: the tint may have changed with them. */
    public static void clearCache() {
        TINTED.clear();
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        ModelData data = super.getModelData(level, pos, state, modelData);
        Fog fog = fogAt(level, pos, state);
        return fog == null ? data : data.derive().with(FOG, fog).build();
    }

    @Override
    public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource rand, ModelData data) {
        ChunkRenderTypeSet own = super.getRenderTypes(state, rand, data);
        return data.get(FOG) == null ? own : ChunkRenderTypeSet.union(own, ChunkRenderTypeSet.of(RenderType.translucent()));
    }

    @Override
    public TriState useAmbientOcclusion(BlockState state, ModelData data, RenderType renderType) {
        if (data.get(FOG) != null && renderType == RenderType.translucent() && !ownsTranslucent(state, data)) {
            return TriState.FALSE; // the murk's models have none
        }
        return super.useAmbientOcclusion(state, data, renderType);
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
        Fog fog = data.get(FOG);
        if (fog == null || state == null) {
            return super.getQuads(state, side, rand, data, renderType);
        }
        // Only the render types the block itself declares get its own quads: a plain model hands out all
        // of them whatever type it is asked for, so asking it for the murk's would draw it twice.
        boolean mine = renderType == null || super.getRenderTypes(state, rand, data).contains(renderType);
        List<BakedQuad> own = mine ? super.getQuads(state, side, rand, data, renderType) : List.of();
        if (side != null || (renderType != null && renderType != RenderType.translucent())) {
            return own;
        }
        // All of the murk goes in the unculled list, its faces picked here: the renderer would cull them by
        // the host block's shape, not by the murk's.
        BakedModel murkModel = Minecraft.getInstance().getBlockRenderer().getBlockModel(fog.murk());
        List<BakedQuad> out = new ArrayList<>(own);
        for (BakedQuad q : murkModel.getQuads(fog.murk(), null, rand, fog.murkData(), RenderType.translucent())) {
            out.add(tinted(q));
        }
        for (Direction d : FACES) {
            if ((fog.faces() & 1 << d.ordinal()) == 0) {
                continue;
            }
            for (BakedQuad q : murkModel.getQuads(fog.murk(), d, rand, fog.murkData(), RenderType.translucent())) {
                out.add(tinted(q));
            }
        }
        return out;
    }

    private boolean ownsTranslucent(BlockState state, ModelData data) {
        return super.getRenderTypes(state, RandomSource.create(42L), data).contains(RenderType.translucent());
    }

    // --- Where the fog is ---

    private static Fog fogAt(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !Config.dimensionEnabled(mc.level)) {
            return null;
        }
        int calm = (int) Math.round(FogBand.surfaceY(mc.level) * 2.0);
        int y = pos.getY();
        if (y < MurkLayer.bottomY(calm) || y > MurkLayer.topY(calm)) {
            return null;
        }
        Block murk = ModBlocks.MURK.get();
        if (state.isAir() || state.is(murk) || state.getBlock() instanceof LiquidBlock
                || state.isCollisionShapeFullBlock(level, pos)) {
            return null;
        }
        int fill = fillAt(level, pos, murk);
        if (fill == 0) {
            return null;
        }
        boolean surface = fill == 1 || fillAt(level, pos.above(), murk) == 0;
        BlockState murkState = murk.defaultBlockState().setValue(MurkBlock.LEVEL, fill).setValue(MurkBlock.SURFACE, surface);

        int faces = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : FACES) {
            n.setWithOffset(pos, d);
            BlockState ns = level.getBlockState(n);
            boolean hidden = ns.is(murk) && (d.getAxis().isVertical() || ns.getValue(MurkBlock.LEVEL) >= fill);
            if (!hidden && !ns.isSolidRender(level, n)) {
                faces |= 1 << d.ordinal();
            }
        }
        BakedModel murkModel = mc.getBlockRenderer().getBlockModel(murkState);
        ModelData murkData = murkModel.getModelData(level, pos, murkState, ModelData.EMPTY);
        return new Fog(murkState, murkData, faces);
    }

    // How full of murk a cell is by the murk round it: all of it under murk, else the highest murk beside
    // it, edges first and then corners.
    private static int fillAt(BlockAndTintGetter level, BlockPos pos, Block murk) {
        if (level.getBlockState(pos.above()).is(murk)) {
            return 2;
        }
        int best = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (Direction d : SIDES) {
            BlockState ns = level.getBlockState(n.setWithOffset(pos, d));
            if (ns.is(murk)) {
                best = Math.max(best, ns.getValue(MurkBlock.LEVEL));
            }
        }
        if (best > 0) {
            return best;
        }
        for (int i = 0; i < 4; i++) {
            Direction a = SIDES[i], b = SIDES[(i + 1) & 3];
            BlockState ns = level.getBlockState(n.setWithOffset(pos, a).move(b));
            if (ns.is(murk)) {
                best = Math.max(best, ns.getValue(MurkBlock.LEVEL));
            }
        }
        return best;
    }

    // --- Tint ---

    private static BakedQuad tinted(BakedQuad quad) {
        if (quad.getTintIndex() < 0) {
            return quad;
        }
        return TINTED.computeIfAbsent(quad, q -> {
            int rgb = Minecraft.getInstance().getBlockColors().getColor(ModBlocks.MURK.get().defaultBlockState(),
                    null, null, q.getTintIndex());
            int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
            int[] verts = q.getVertices().clone();
            for (int i = 0; i < 4; i++) {
                int o = i * VERTEX_INTS + COLOR_OFFSET;
                int c = verts[o]; // ABGR
                int cr = (c & 0xFF) * r / 255, cg = (c >> 8 & 0xFF) * g / 255, cb = (c >> 16 & 0xFF) * b / 255;
                verts[o] = (c & 0xFF000000) | cb << 16 | cg << 8 | cr;
            }
            return new BakedQuad(verts, -1, q.getDirection(), q.getSprite(), q.isShade(), q.hasAmbientOcclusion());
        });
    }
}
