package com.fogged;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import com.fogged.block.MurkBlock;

import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * Lays the murk's textures out in world space, so one texture spans {@link #SPAN} blocks and runs on
 * unbroken from block to block instead of repeating in every cell.
 *
 * <p>Each murk block's model ({@code models/block/murk_*.json}) maps its textures whole onto every
 * face. This wraps it and, for the block at hand, re-maps each face onto that block's piece of the
 * texture: a top, a bottom or a vapour sheet by its world x and z, a wall by its world x or z and its
 * height. A horizontal face's piece is shifted by the block's height as well, so the fog one block up
 * is not a copy of the fog below, and the vapour sheets of a surface stepping up or down do not line up.
 * The position comes through NeoForge's model data, which Sodium honours too.
 *
 * <p>It also adds the foam, which depends on the neighbours: a jagged band of it round the top
 * wherever the surface ends, and drips hanging down every wall that shows ({@code murk_rim},
 * {@code murk_drip}), both in the foam colour and running on along the edge from block to block.
 */
public class MurkModel extends BakedModelWrapper<BakedModel> {

    /** Blocks one texture spans, each way. The textures are {@code 16 * SPAN} pixels square. */
    public static final int SPAN = 4;

    private static final ModelProperty<Long> POS = new ModelProperty<>();
    /** Per side (north, east, south, west): bit 0..3 a foam rim on the top, bit 4..7 drips down the wall. */
    private static final ModelProperty<Integer> EDGES = new ModelProperty<>();

    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final ResourceLocation RIM = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "block/murk_rim");
    private static final ResourceLocation DRIP = ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "block/murk_drip");
    /** How far the foam sits off the face it lies on, so the two never fight for the same depth. */
    private static final float LIFT = 0.003F;
    /** The block colour slot the rim and drips take: the foam colour lifted toward white (FoggedClient). */
    private static final int FOAM_TINT = 2;

    // BLOCK vertex format: 8 ints per vertex, position at 0..2, UV0 at 4..5.
    private static final int VERTEX_INTS = 8;
    private static final int UV_OFFSET = 4;

    // Per piece of the texture (x, y, z each mod SPAN) and side: the re-mapped quads.
    private final Map<Integer, List<BakedQuad>> cache = new ConcurrentHashMap<>();

    public MurkModel(BakedModel original) {
        super(original);
    }

    @Override
    public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData modelData) {
        return modelData.derive().with(POS, pos.asLong()).with(EDGES, edges(level, pos, state)).build();
    }

    // Where the surface ends: a rim of foam on the top wherever it does not run on into murk at least
    // as high (against a block it is the shoreline, against air the brink), and drips down every wall
    // that shows -- a side open to anything but taller murk or a solid block.
    private static int edges(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof MurkBlock) || !state.getValue(MurkBlock.SURFACE)) {
            return 0;
        }
        int mine = state.getValue(MurkBlock.LEVEL);
        int mask = 0;
        BlockPos.MutableBlockPos n = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 4; i++) {
            n.setWithOffset(pos, SIDES[i]);
            BlockState ns = level.getBlockState(n);
            if (ns.getBlock() instanceof MurkBlock && ns.getValue(MurkBlock.LEVEL) >= mine) {
                continue;
            }
            mask |= 1 << i;
            if (!ns.isSolidRender(level, n)) {
                mask |= 1 << (i + 4);
            }
        }
        return mask;
    }

    @Override
    public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType) {
        List<BakedQuad> quads = super.getQuads(state, side, rand, data, renderType);
        Long packed = data.get(POS);
        if (packed == null || quads.isEmpty()) {
            return quads;
        }
        int x = Mth.positiveModulo(BlockPos.getX(packed), SPAN);
        int y = Mth.positiveModulo(BlockPos.getY(packed), SPAN);
        int z = Mth.positiveModulo(BlockPos.getZ(packed), SPAN);
        if (side != null) {
            int key = ((x * SPAN + y) * SPAN + z) * 7 + side.ordinal();
            return cache.computeIfAbsent(key, k -> remap(quads, x, y, z));
        }
        // The unculled quads carry the top and the foam, so they depend on the neighbours too.
        Integer e = data.get(EDGES);
        int edges = e == null ? 0 : e;
        int level = state.getValue(MurkBlock.LEVEL);
        int key = ((((x * SPAN + y) * SPAN + z) * 256 + edges) * 3 + level) * 7 + 6;
        return cache.computeIfAbsent(key, k -> {
            List<BakedQuad> out = remap(quads, x, y, z);
            foam(out, x, y, z, edges, level * 0.5F);
            return out;
        });
    }

    // --- Foam ---

    private void foam(List<BakedQuad> out, int x, int y, int z, int edges, float h) {
        if (edges == 0) {
            return;
        }
        TextureAtlasSprite rim = sprite(RIM), drip = sprite(DRIP);
        for (int i = 0; i < 4; i++) {
            Direction d = SIDES[i];
            if ((edges & 1 << i) != 0) {
                out.add(rimQuad(rim, d, x, z, h));
            }
            if ((edges & 1 << (i + 4)) != 0) {
                out.add(dripQuad(drip, d, x, z, h));
            }
        }
    }

    // A full quad over the top whose texture's foam band (along its v = 0 edge) lies against side d,
    // with u running along that edge in world space so the band carries on into the next block.
    private static BakedQuad rimQuad(TextureAtlasSprite sprite, Direction d, int x, int z, float h) {
        float y = h + LIFT;
        float[][] p = {{0, y, 0}, {0, y, 1}, {1, y, 1}, {1, y, 0}}; // as FaceBakery lays an up face
        float[] uv = new float[8];
        for (int i = 0; i < 4; i++) {
            float lx = p[i][0], lz = p[i][2];
            float u, v;
            switch (d) {
                case NORTH -> { u = (x + lx) / SPAN; v = lz; }
                case SOUTH -> { u = (x + lx) / SPAN; v = 1.0F - lz; }
                case WEST -> { u = (z + lz) / SPAN; v = lx; }
                default -> { u = (z + lz) / SPAN; v = 1.0F - lx; }
            }
            uv[i * 2] = sprite.getU(u);
            uv[i * 2 + 1] = sprite.getV(v);
        }
        return quad(p, uv, Direction.UP, sprite);
    }

    // The wall on side d, just outside it, with the drips hanging from its top to its foot.
    private static BakedQuad dripQuad(TextureAtlasSprite sprite, Direction d, int x, int z, float h) {
        float o = -LIFT, f = 1 + LIFT;
        float[][] p = switch (d) { // as FaceBakery lays each side, seen from outside
            case NORTH -> new float[][] {{1, h, o}, {1, 0, o}, {0, 0, o}, {0, h, o}};
            case SOUTH -> new float[][] {{0, h, f}, {0, 0, f}, {1, 0, f}, {1, h, f}};
            case WEST -> new float[][] {{o, h, 0}, {o, 0, 0}, {o, 0, 1}, {o, h, 1}};
            default -> new float[][] {{f, h, 1}, {f, 0, 1}, {f, 0, 0}, {f, h, 0}};
        };
        float[] uv = new float[8];
        for (int i = 0; i < 4; i++) {
            float across = d.getAxis() == Direction.Axis.Z ? p[i][0] : p[i][2];
            int along = d.getAxis() == Direction.Axis.Z ? x : z;
            uv[i * 2] = sprite.getU((along + across) / SPAN);
            uv[i * 2 + 1] = sprite.getV(1.0F - p[i][1] / h);
        }
        return quad(p, uv, d, sprite);
    }

    private static BakedQuad quad(float[][] p, float[] uv, Direction d, TextureAtlasSprite sprite) {
        int[] verts = new int[4 * VERTEX_INTS];
        int normal = (d.getStepX() * 127 & 0xFF) | (d.getStepY() * 127 & 0xFF) << 8 | (d.getStepZ() * 127 & 0xFF) << 16;
        for (int i = 0; i < 4; i++) {
            int o = i * VERTEX_INTS;
            verts[o] = Float.floatToRawIntBits(p[i][0]);
            verts[o + 1] = Float.floatToRawIntBits(p[i][1]);
            verts[o + 2] = Float.floatToRawIntBits(p[i][2]);
            verts[o + 3] = -1; // white: the tint does the colouring
            verts[o + UV_OFFSET] = Float.floatToRawIntBits(uv[i * 2]);
            verts[o + UV_OFFSET + 1] = Float.floatToRawIntBits(uv[i * 2 + 1]);
            verts[o + 6] = 0;
            verts[o + 7] = normal;
        }
        return new BakedQuad(verts, FOAM_TINT, d, sprite, false, false);
    }

    private static TextureAtlasSprite sprite(ResourceLocation texture) {
        return Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(texture);
    }

    private static List<BakedQuad> remap(List<BakedQuad> quads, int x, int y, int z) {
        List<BakedQuad> out = new ArrayList<>(quads.size());
        for (BakedQuad quad : quads) {
            out.add(remap(quad, x, y, z));
        }
        return out;
    }

    // Each vertex's UV from where it sits in the world: the block's piece of the span plus the vertex's
    // place within the block. Within one face that never crosses a span's edge, so no face wraps.
    private static BakedQuad remap(BakedQuad quad, int x, int y, int z) {
        TextureAtlasSprite sprite = quad.getSprite();
        Direction dir = quad.getDirection();
        int[] verts = quad.getVertices().clone();
        for (int i = 0; i < 4; i++) {
            int o = i * VERTEX_INTS;
            float lx = clamp01(Float.intBitsToFloat(verts[o]));
            float ly = clamp01(Float.intBitsToFloat(verts[o + 1]));
            float lz = clamp01(Float.intBitsToFloat(verts[o + 2]));
            float u, v;
            if (dir.getAxis().isVertical()) {
                // Shifted by height, a different way on each axis, so stacked layers differ.
                u = (Mth.positiveModulo(x + y, SPAN) + lx) / SPAN;
                v = (Mth.positiveModulo(z + 2 * y, SPAN) + lz) / SPAN;
            } else {
                // Height runs down the texture, as v does: the block above takes the piece above.
                float across = dir.getAxis() == Direction.Axis.Z ? lx : lz;
                int along = dir.getAxis() == Direction.Axis.Z ? x : z;
                u = (along + across) / SPAN;
                v = (Mth.positiveModulo(-y - 1, SPAN) + (1.0F - ly)) / SPAN;
            }
            verts[o + UV_OFFSET] = Float.floatToRawIntBits(sprite.getU(u));
            verts[o + UV_OFFSET + 1] = Float.floatToRawIntBits(sprite.getV(v));
        }
        return new BakedQuad(verts, quad.getTintIndex(), dir, sprite, quad.isShade(), quad.hasAmbientOcclusion());
    }

    private static float clamp01(float f) {
        return f < 0.0F ? 0.0F : f > 1.0F ? 1.0F : f;
    }
}
