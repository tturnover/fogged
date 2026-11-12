package com.fogged.create;

import com.fogged.AirCompressorRenderer;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;

import net.createmod.catnip.render.CachedBuffers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The kinetic air compressor: a turning half-shaft out of each socket, and the unit's fan faces. Drawn
 * here rather than left to Create's renderer, which steps aside whenever Flywheel is drawing -- and
 * Flywheel has no visual for this block, so nothing would.
 */
public class KineticAirCompressorRenderer extends KineticBlockEntityRenderer<KineticAirCompressorBlockEntity> {

    public KineticAirCompressorRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    protected void renderSafe(KineticAirCompressorBlockEntity be, float partialTick, PoseStack pose,
            MultiBufferSource buffers, int light, int overlay) {
        BlockState state = be.getBlockState();
        Direction.Axis axis = state.getValue(KineticAirCompressorBlock.AXIS);
        VertexConsumer solid = buffers.getBuffer(RenderType.solid());
        for (Direction.AxisDirection sign : Direction.AxisDirection.values()) {
            Direction end = Direction.fromAxisAndDirection(axis, sign);
            if (state.getValue(KineticAirCompressorBlock.end(end))) {
                standardKineticRotationTransform(
                        CachedBuffers.partialFacing(AllPartialModels.SHAFT_HALF, state, end), be, light)
                        .renderInto(pose, solid);
            }
        }
        AirCompressorRenderer.renderFans(be, be.core(), pose, buffers, overlay);
    }

    @Override
    public AABB getRenderBoundingBox(KineticAirCompressorBlockEntity be) {
        return AirCompressorRenderer.unitBounds(be, be.core());
    }
}
