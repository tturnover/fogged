package com.fogged;

import org.joml.Vector3f;

import com.fogged.block.AirCompressorBlock;
import com.fogged.block.AirCompressorBlockEntity;
import com.fogged.block.AirCompressorCore;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Draws an air compressor's two fan faces: the outlet (with its red lamp, the side that seals)
 * and the back, where it draws from its intake. While the compressor has power the face is the
 * animated sprite and spins; without, it draws nothing -- the block model already shows the still fan.
 *
 * <p>The art is Factory Blocks' fans (see textures/block/air_compressor/CREDITS.txt). Each face is laid out the way a viewer standing in front of it sees
 * it, up being world up (north for a face pointing up or down), so the intake turns the opposite way
 * to the outlet, as the far side of one fan does.
 */
public class AirCompressorRenderer implements BlockEntityRenderer<AirCompressorBlockEntity> {

    /** How far in front of the block face the fan is drawn, to sit over the block model's own face. */
    private static final float LIFT = 0.006F;

    public AirCompressorRenderer(BlockEntityRendererProvider.Context context) {
    }

    private static TextureAtlasSprite sprite(String name) {
        return Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                .apply(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "block/air_compressor/" + name));
    }

    @Override
    public void render(AirCompressorBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers,
            int packedLight, int packedOverlay) {
        renderFans(be, be.core(), pose, buffers, packedOverlay);
    }

    /** The compressor's two fan faces; shared with the kinetic compressor's renderer. */
    public static void renderFans(BlockEntity be, AirCompressorCore core, PoseStack pose, MultiBufferSource buffers,
            int packedOverlay) {
        if (be.getLevel() == null || !core.running()) {
            return; // stopped: the block model's still fan is all there is
        }
        int size = 1;
        Direction facing = be.getBlockState().getValue(AirCompressorBlock.FACING);
        String base = "fan";
        String still = "";
        VertexConsumer vc = buffers.getBuffer(RenderType.cutout());
        BlockPos pos = be.getBlockPos();
        face(vc, pose, be, facing, size, 0.0F, sprite(base + "_out" + still), pos.relative(facing));
        // The back fan, on the casing's own back face: the intake is a block of its own.
        face(vc, pose, be, facing.getOpposite(), size, 0.0F, sprite(base + still), pos.relative(facing.getOpposite()));
    }

    // One face of the unit, on its `normal` side and `out` blocks beyond it, lit by the cell in front.
    private static void face(VertexConsumer vc, PoseStack pose, BlockEntity be, Direction normal,
            int size, float out, TextureAtlasSprite sprite, BlockPos lightAt) {
        int light = LevelRenderer.getLightColor(be.getLevel(), lightAt);
        Vector3f n = normal.step();
        // Up and right as seen by someone in front of the face, looking at it.
        Vector3f up = normal.getAxis() == Direction.Axis.Y ? new Vector3f(0, 0, -1) : new Vector3f(0, 1, 0);
        Vector3f right = new Vector3f(up).cross(n);

        // The unit spans [0, size] along both plane axes from the controller's corner, and [0, 1] along
        // the facing axis; the face sits on the `normal` side of that, lifted clear of the block.
        float[] lo = new float[3];
        float[] hi = {1, 1, 1};
        for (Direction d : AirCompressorCore.plane(normal)) {
            hi[d.getAxis().ordinal()] = size;
        }
        int ax = normal.getAxis().ordinal();
        float plane = normal.getAxisDirection() == Direction.AxisDirection.POSITIVE ? 1 + out + LIFT : -out - LIFT;
        lo[ax] = plane;
        hi[ax] = plane;

        PoseStack.Pose last = pose.last();
        // Bottom-left, bottom-right, top-right, top-left as the viewer sees them: counter-clockwise.
        float[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
        for (float[] c : corners) {
            float[] p = new float[3];
            for (int i = 0; i < 3; i++) {
                float r = component(right, i), u = component(up, i);
                if (r != 0) {
                    p[i] = r > 0 ? lerp(lo[i], hi[i], c[0]) : lerp(hi[i], lo[i], c[0]);
                } else if (u != 0) {
                    p[i] = u > 0 ? lerp(lo[i], hi[i], c[1]) : lerp(hi[i], lo[i], c[1]);
                } else {
                    p[i] = lo[i];
                }
            }
            vc.addVertex(last, p[0], p[1], p[2])
                    .setColor(0xFFFFFFFF)
                    .setUv(sprite.getU(c[0]), sprite.getV(1 - c[1]))
                    .setLight(light)
                    .setNormal(last, n.x(), n.y(), n.z());
        }
    }

    private static float component(Vector3f v, int i) {
        return i == 0 ? v.x() : i == 1 ? v.y() : v.z();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    @Override
    public AABB getRenderBoundingBox(AirCompressorBlockEntity be) {
        return unitBounds(be, be.core());
    }

    /** Everything a compressor draws: its faces, just clear of its block. */
    public static AABB unitBounds(BlockEntity be, AirCompressorCore core) {
        return new AABB(be.getBlockPos()).inflate(0.6);
    }
}
