package com.fogged;

import com.fogged.block.AirCompressorIntakeBlock;
import com.fogged.block.AirCompressorIntakeBlockEntity;
import com.fogged.block.AirIntakeShapes;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws an air intake's filter: the plane behind its mesh (AirIntakeShapes#HOOD_PLANE / FRAME_PLANE),
 * in the fitted block's own texture -- any full block the config lists can be a filter -- shaded toward
 * the configured grime colour as it fills with murk (Config#filterPollutionColor).
 */
public class AirIntakeRenderer implements BlockEntityRenderer<AirCompressorIntakeBlockEntity> {

    public AirIntakeRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(AirCompressorIntakeBlockEntity be, float partialTick, PoseStack pose, MultiBufferSource buffers,
            int packedLight, int packedOverlay) {
        ItemStack filter = be.filter();
        BlockState state = be.getBlockState();
        if (filter.isEmpty() || !(filter.getItem() instanceof BlockItem item)
                || !state.getValue(AirCompressorIntakeBlock.FILTERED)) {
            return;
        }
        TextureAtlasSprite sprite = Minecraft.getInstance().getBlockRenderer()
                .getBlockModel(item.getBlock().defaultBlockState()).getParticleIcon(ModelData.EMPTY);
        Direction feeds = state.getValue(AirCompressorIntakeBlock.FACING);
        boolean vertical = feeds.getAxis() == Direction.Axis.Y;
        AirIntakeShapes.Plane plane = vertical ? AirIntakeShapes.FRAME_PLANE : AirIntakeShapes.HOOD_PLANE;

        // White when clean, all the way to the grime colour clogged.
        float t = (float) be.worn();
        int grime = Config.filterPollutionColor();
        int r = (int) (255 + (((grime >> 16) & 0xFF) - 255) * t);
        int g = (int) (255 + (((grime >> 8) & 0xFF) - 255) * t);
        int b = (int) (255 + ((grime & 0xFF) - 255) * t);

        pose.pushPose();
        // Turned as the blockstate turns the model (see ModBlockStateProvider#airIntake): about y for the
        // hood, by 180 about x for a frame feeding up.
        pose.translate(0.5F, 0.5F, 0.5F);
        if (vertical) {
            if (feeds == Direction.UP) {
                pose.mulPose(Axis.XP.rotationDegrees(-180.0F));
            }
        } else {
            pose.mulPose(Axis.YP.rotationDegrees(-feeds.toYRot()));
        }
        pose.translate(-0.5F, -0.5F, -0.5F);
        // The element's own turn, about its origin.
        if (plane.angle() != 0.0F) {
            float[] o = plane.origin();
            pose.translate(o[0] / 16, o[1] / 16, o[2] / 16);
            Axis axis = switch (plane.axis()) {
                case X -> Axis.XP;
                case Y -> Axis.YP;
                case Z -> Axis.ZP;
            };
            pose.mulPose(axis.rotationDegrees(plane.angle()));
            pose.translate(-o[0] / 16, -o[1] / 16, -o[2] / 16);
        }

        float[] f = plane.from();
        float[] to = plane.to();
        float[] uv = plane.uv();
        float x0 = f[0] / 16, y0 = f[1] / 16, z0 = f[2] / 16, x1 = to[0] / 16, y1 = to[1] / 16, z1 = to[2] / 16;
        float u0 = sprite.getU(uv[0] / 16), v0 = sprite.getV(uv[1] / 16);
        float u1 = sprite.getU(uv[2] / 16), v1 = sprite.getV(uv[3] / 16);
        VertexConsumer vc = buffers.getBuffer(RenderType.cutout());
        PoseStack.Pose last = pose.last();
        // Corners counter-clockwise as seen from the face's side, as the game bakes model faces.
        if (plane.face() == Direction.UP) {
            vertex(vc, last, x0, y1, z0, u0, v0, r, g, b, packedLight, packedOverlay, 0, 1, 0);
            vertex(vc, last, x0, y1, z1, u0, v1, r, g, b, packedLight, packedOverlay, 0, 1, 0);
            vertex(vc, last, x1, y1, z1, u1, v1, r, g, b, packedLight, packedOverlay, 0, 1, 0);
            vertex(vc, last, x1, y1, z0, u1, v0, r, g, b, packedLight, packedOverlay, 0, 1, 0);
        } else { // NORTH
            vertex(vc, last, x1, y1, z0, u0, v0, r, g, b, packedLight, packedOverlay, 0, 0, -1);
            vertex(vc, last, x1, y0, z0, u0, v1, r, g, b, packedLight, packedOverlay, 0, 0, -1);
            vertex(vc, last, x0, y0, z0, u1, v1, r, g, b, packedLight, packedOverlay, 0, 0, -1);
            vertex(vc, last, x0, y1, z0, u1, v0, r, g, b, packedLight, packedOverlay, 0, 0, -1);
        }
        pose.popPose();
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float x, float y, float z, float u, float v,
            int r, int g, int b, int light, int overlay, float nx, float ny, float nz) {
        vc.addVertex(pose, x, y, z).setColor(r, g, b, 255).setUv(u, v).setOverlay(overlay).setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
