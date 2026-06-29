package com.fogged.entity;

import com.fogged.Fogged;
import com.fogged.entity.part.LurkerHeadModel;
import com.fogged.entity.part.LurkerPart;
import com.fogged.entity.part.LurkerSegmentAModel;
import com.fogged.entity.part.LurkerSegmentBModel;
import com.fogged.entity.part.LurkerSegmentCModel;
import com.fogged.entity.part.LurkerTailModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Renders the {@link FogLurker} as a chain of separate piece models -- head, several body-segment
 * variants and a tail -- placed along the worm's recorded position trail. Each piece is oriented to
 * point at the piece ahead of it, so the body bends and digs as one creature even though it is a single
 * entity. Swap or re-shape any piece by editing its model class (see {@code com.fogged.entity.part}).
 */
public class FogLurkerRenderer extends EntityRenderer<FogLurker> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker.png");

    private static final float SCALE = 1.35F;       // overall size
    private static final int SEGMENT_COUNT = 6;     // body segments between head and tail
    private static final double HEAD_DIST = 0.6;    // world-space gap (blocks) from head to first segment
    private static final double SEGMENT_DIST = 0.6; // world-space gap (blocks) between consecutive pieces
    private static final double WALK_STEP = 0.5;    // history-clock step (ticks) used to measure arc length
    private static final double WALK_MAX = 80.0;    // furthest back (ticks) the body walk will look

    private final LurkerHeadModel head;
    private final LurkerPart[] segmentVariants; // cycled along the body
    private final LurkerTailModel tail;

    public FogLurkerRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.7F;
        this.head = new LurkerHeadModel(context.bakeLayer(LurkerHeadModel.LAYER));
        this.segmentVariants = new LurkerPart[] {
                new LurkerSegmentAModel(context.bakeLayer(LurkerSegmentAModel.LAYER)),
                new LurkerSegmentBModel(context.bakeLayer(LurkerSegmentBModel.LAYER)),
                new LurkerSegmentCModel(context.bakeLayer(LurkerSegmentCModel.LAYER)),
        };
        this.tail = new LurkerTailModel(context.bakeLayer(LurkerTailModel.LAYER));
    }

    @Override
    public ResourceLocation getTextureLocation(FogLurker entity) {
        return TEXTURE;
    }

    @Override
    public void render(FogLurker entity, float entityYaw, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight) {
        // Every piece is sampled from the same continuous history clock (delay 0 = head), so the whole
        // chain shares one sub-tick timeline and never creeps forward-and-back between frames. The render
        // origin the dispatcher set is exactly historyLerp(partialTick, 0), so offsets are relative to it.
        Vec3 headPos = entity.historyLerp(partialTick, 0.0);
        head.setupAnim(entity, 0.0F, 0.0F, entity.tickCount + partialTick, 0.0F, 0.0F); // burrow / lunge anim
        renderPiece(head, headPos, headForward(entity, partialTick, headPos), headPos, poseStack, buffers, lightAt(entity, headPos));

        // Place the body pieces at constant WORLD-DISTANCE spacing by walking the smooth history path and
        // accumulating arc length. Distance-based (not tick-based) keeps the segments touching no matter
        // how fast the worm moves -- so they don't fan apart during the lunge.
        int pieces = SEGMENT_COUNT + 1; // segments + tail
        double nextTarget = HEAD_DIST;
        double acc = 0.0;
        int placed = 0;
        Vec3 prev = headPos;
        for (double s = WALK_STEP; placed < pieces && s <= WALK_MAX; s += WALK_STEP) {
            Vec3 cur = entity.historyLerp(partialTick, s);
            double d = prev.distanceTo(cur);
            while (placed < pieces && d > 1.0e-6 && acc + d >= nextTarget) {
                Vec3 pos = prev.lerp(cur, (nextTarget - acc) / d);
                Vec3 fwd = pos.add(prev.subtract(cur)); // tangent pointing back toward the head
                renderPiece(pieceFor(placed), pos, fwd, headPos, poseStack, buffers, lightAt(entity, pos));
                placed++;
                nextTarget += SEGMENT_DIST;
            }
            acc += d;
            prev = cur;
        }
        // Out of recorded path (worm nearly still / fresh spawn): pile any leftovers on the last point.
        while (placed < pieces) {
            renderPiece(pieceFor(placed), prev, headPos, headPos, poseStack, buffers, lightAt(entity, prev));
            placed++;
        }

        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    private LurkerPart pieceFor(int index) {
        return index < SEGMENT_COUNT ? segmentVariants[index % segmentVariants.length] : tail;
    }

    // Light for a piece: sampled in the open air above its column (or at the piece itself when it is
    // above ground), never inside the blocks it burrows through -- so it isn't rendered pitch black
    // when buried, and the breaching fin reads as lit by the surface it cuts through.
    private int lightAt(FogLurker entity, Vec3 pos) {
        Level level = entity.level();
        int x = Mth.floor(pos.x);
        int z = Mth.floor(pos.z);
        int surfaceAir = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        int y = Math.max(surfaceAir, Mth.floor(pos.y) + 1);
        return LevelRenderer.getLightColor(level, new BlockPos(x, y, z));
    }

    // Forward reference for the head: extrapolate along the path it just travelled (same history the body
    // uses), falling back to its synced facing when it is nearly still.
    private Vec3 headForward(FogLurker entity, float partialTick, Vec3 headPos) {
        Vec3 back = entity.historyLerp(partialTick, 1.5);
        if (headPos.distanceToSqr(back) > 1.0e-6) {
            return headPos.add(headPos.subtract(back)); // mirror the just-travelled segment forward
        }
        double yaw = Math.toRadians(entity.yBodyRot + 90.0F);
        return headPos.add(Math.cos(yaw), 0.0, Math.sin(yaw));
    }

    // Place one piece at `pos`, rotated to look toward `fwd`, relative to the head's render origin. Each
    // piece is drawn with its own texture (the head has a separate one from the body pieces).
    private void renderPiece(LurkerPart part, Vec3 pos, Vec3 fwd, Vec3 head,
                             PoseStack poseStack, MultiBufferSource buffers, int light) {
        VertexConsumer buffer = buffers.getBuffer(part.renderType(part.texture()));
        poseStack.pushPose();
        poseStack.translate(pos.x - head.x, pos.y - head.y, pos.z - head.z);

        double dx = fwd.x - pos.x;
        double dy = fwd.y - pos.y;
        double dz = fwd.z - pos.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yawDeg = (float) Math.toDegrees(Mth.atan2(dz, dx)) - 90.0F;
        float pitchDeg = (float) Math.toDegrees(Mth.atan2(dy, horiz));

        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yawDeg));
        poseStack.mulPose(Axis.XP.rotationDegrees(pitchDeg));
        poseStack.scale(SCALE, SCALE, SCALE);
        poseStack.scale(-1.0F, -1.0F, 1.0F); // standard model space (Y down, mirrored)
        poseStack.translate(0.0F, -1.501F, 0.0F);

        part.renderToBuffer(poseStack, buffer, light, OverlayTexture.NO_OVERLAY, 0xFFFFFFFF);
        poseStack.popPose();
    }
}
