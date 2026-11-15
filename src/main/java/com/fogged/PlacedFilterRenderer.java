package com.fogged;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;

import it.unimi.dsi.fastutil.longs.Long2FloatMap;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;

import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Shades a polluted filter set down as a block toward the grime colour, as worn as it is, the way a
 * filter in an intake is shaded (AirIntakeRenderer): the block is drawn again over itself, its own
 * model, lighting and shading, every texel multiplied toward the murk's colour. Pulled just in front
 * of the block by polygon offset, so the copy covers it at any distance without being any bigger.
 * Which blocks are filters and how worn comes from the server ({@link FilterPollution.PlacedTint}).
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class PlacedFilterRenderer {

    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");

    /** How far from the camera a placed filter is shaded, in blocks. */
    private static final double RANGE = 96.0;

    private static final RenderType TINT = RenderType.create("fogged_placed_filter",
            DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 4096, true, false,
            RenderType.CompositeState.builder()
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setShaderState(RenderStateShard.RENDERTYPE_CUTOUT_SHADER)
                    .setTextureState(RenderStateShard.BLOCK_SHEET)
                    .setLayeringState(RenderStateShard.POLYGON_OFFSET_LAYERING)
                    .createCompositeState(true));

    private static ClientLevel shownFor;

    // With the block entities, before water and the translucent pass: a filter under a lake is shaded
    // and then covered by the water like the block itself.
    @SubscribeEvent
    static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != shownFor) {
            FilterPollution.CLIENT_TINT.clear(); // another world: what was told of the last one is gone
            shownFor = level;
        }
        if (level == null || FilterPollution.CLIENT_TINT.isEmpty() || IrisCompatibility.renderingShadowPass()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        int grime = Config.filterPollutionColor();
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        RandomSource random = RandomSource.create();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // As AirtightDebugRenderer: the view onto RenderSystem's modelview, camera-relative positions.
        Matrix4fStack view = RenderSystem.getModelViewStack();
        view.pushMatrix();
        view.set(event.getModelViewMatrix());
        RenderSystem.applyModelViewMatrix();
        VertexConsumer out = buffers.getBuffer(TINT);
        PoseStack pose = new PoseStack();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<Long2FloatOpenHashMap> chunk : FilterPollution.CLIENT_TINT.long2ObjectEntrySet()) {
            for (Long2FloatMap.Entry e : chunk.getValue().long2FloatEntrySet()) {
                pos.set(e.getLongKey());
                BlockState state = level.getBlockState(pos);
                if (state.getRenderShape() != RenderShape.MODEL) {
                    continue;
                }
                Matrix4d m = new Matrix4d().translation(-cam.x, -cam.y, -cam.z);
                Matrix4d ship = SABLE ? SableClientCompat.plotToWorld(level, pos, partial) : null;
                if (ship != null) {
                    m.mul(ship); // set down on a Sable ship: where the ship has it this frame
                }
                m.translate(pos.getX(), pos.getY(), pos.getZ());
                double dx = m.m30(), dy = m.m31(), dz = m.m32();
                if (dx * dx + dy * dy + dz * dz > RANGE * RANGE) {
                    continue;
                }
                // White when clean, all the way to the grime colour clogged, as in an intake.
                float t = e.getFloatValue();
                int r = (int) (255 + (((grime >> 16) & 0xFF) - 255) * t);
                int g = (int) (255 + (((grime >> 8) & 0xFF) - 255) * t);
                int b = (int) (255 + ((grime & 0xFF) - 255) * t);
                pose.last().pose().set(new Matrix4f(m));
                blocks.getModelRenderer().tesselateBlock(level, blocks.getBlockModel(state), state, pos, pose,
                        new Tinted(out, r, g, b), true, random, state.getSeed(pos), OverlayTexture.NO_OVERLAY,
                        ModelData.EMPTY, null);
            }
        }
        buffers.endBatch(TINT);
        view.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    // Unloaded on the client: the server sends it again with the chunk.
    @SubscribeEvent
    static void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            FilterPollution.CLIENT_TINT.remove(event.getChunk().getPos().toLong());
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        FilterPollution.CLIENT_TINT.clear();
        shownFor = null;
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

    private PlacedFilterRenderer() {}
}
