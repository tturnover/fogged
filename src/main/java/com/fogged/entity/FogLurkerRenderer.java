package com.fogged.entity;

import com.fogged.Fogged;
import com.fogged.entity.part.LurkerBodyCenterModel;
import com.fogged.entity.part.LurkerBodyPawsModel;
import com.fogged.entity.part.LurkerHeadModel;
import com.fogged.entity.part.LurkerPart;
import com.fogged.entity.part.LurkerTailStartModel;
import com.fogged.entity.part.LurkerTailMiddleModel;
import com.fogged.entity.part.LurkerTailEndModel;
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
    private static final int SEGMENT_COUNT = 3;     // body pieces between head and tail: paws, center, paws
    private static final double WALK_STEP = 0.5;    // history-clock step (ticks) used to measure arc length
    private static final double WALK_MAX = 80.0;    // furthest back (ticks) the body walk will look

    // Inter-piece spacing comes from the body_parrent / body_child points placed in the Blockbench models:
    // each piece is anchored at its parent seam (the trail placement point) and trails backward to its
    // child seam, where the next piece's parent attaches. So the gap to the next piece equals the piece's
    // own parent->child length, in blocks = (childZ - parentZ) px / 16 * SCALE.
    private static final double PX = SCALE / 16.0;
    private static final double HEAD_DIST = 6.0 * PX;    // head body_child z=6 -> first body piece sits here
    private static final double PAWS_LEN = 7.0 * PX;     // paws parent z=-4 -> child z=3
    private static final double CENTER_LEN = 11.0 * PX;  // center parent z=-4 -> child z=7
    private static final double TAIL_START_LEN = 8.0 * PX;   // tail base parent z=-4 -> child z=4
    private static final double TAIL_MIDDLE_LEN = 11.0 * PX; // tail middle parent z=-6 -> child z=5
    private static final double DEFAULT_LEN = 0.6;       // fallback spacing (only the unused tail-end value)

    private static final int[] PAWS_AT = {0, 2}; // body-piece indices that use the paw'd segment (1st and 3rd)
    private static final int CENTER_AT = 1;      // body piece sitting between the two paw'd segments

    private final LurkerHeadModel head;
    private final LurkerPart paws;
    private final LurkerPart bodyCenter;
    private final LurkerPart[] tail;            // final pieces, in order: base, middle, end

    public FogLurkerRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.7F;
        this.head = new LurkerHeadModel(context.bakeLayer(LurkerHeadModel.LAYER));
        this.paws = new LurkerBodyPawsModel(context.bakeLayer(LurkerBodyPawsModel.LAYER));
        this.bodyCenter = new LurkerBodyCenterModel(context.bakeLayer(LurkerBodyCenterModel.LAYER));
        this.tail = new LurkerPart[] {
                new LurkerTailStartModel(context.bakeLayer(LurkerTailStartModel.LAYER)),
                new LurkerTailMiddleModel(context.bakeLayer(LurkerTailMiddleModel.LAYER)),
                new LurkerTailEndModel(context.bakeLayer(LurkerTailEndModel.LAYER)),
        };
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
        float age = entity.tickCount + partialTick;
        head.setupAnim(entity, 0.0F, 0.0F, age, 0.0F, 0.0F);  // burrow / lunge anim
        paws.setupAnim(entity, 0.0F, 0.0F, age, 0.0F, 0.0F);  // shared paws instance: animate once, reused below
        renderPiece(head, headPos, headForward(entity, partialTick, headPos), headPos, poseStack, buffers, lightAt(entity, headPos));

        // Place the body pieces at constant WORLD-DISTANCE spacing by walking the smooth history path and
        // accumulating arc length. Distance-based (not tick-based) keeps the segments touching no matter
        // how fast the worm moves -- so they don't fan apart during the lunge.
        int pieces = SEGMENT_COUNT + tail.length; // body segments + the three tail pieces

        // Pass 1: collect each piece's parent-seam position at constant world-distance spacing along the
        // smooth history path (distance-based, so the seams touch no matter how fast the worm moves). One
        // EXTRA guide point is collected past the last piece so the tail end gets its own forward direction
        // from the continuing curve, instead of sharing the second-to-last piece's chord (rigid pair).
        Vec3[] pts = new Vec3[pieces + 1];
        double nextTarget = HEAD_DIST;
        double acc = 0.0;
        int placed = 0;
        Vec3 prev = headPos;
        for (double s = WALK_STEP; placed < pts.length && s <= WALK_MAX; s += WALK_STEP) {
            Vec3 cur = entity.historyLerp(partialTick, s);
            double d = prev.distanceTo(cur);
            while (placed < pts.length && d > 1.0e-6 && acc + d >= nextTarget) {
                pts[placed] = prev.lerp(cur, (nextTarget - acc) / d);
                nextTarget += pieceLength(placed); // gap to the next point = this piece's own length
                placed++;
            }
            acc += d;
            prev = cur;
        }
        while (placed < pts.length) { // out of recorded path (still / fresh spawn): pile on the last point
            pts[placed++] = prev;
        }

        // Pass 2: render each piece aligned to the chord toward the piece BEHIND it (its own parent->child
        // span), so the rigid body lies along the curve and its child seam lands on the next parent -- no
        // floating on bends, and every piece bends independently. Its front faces the head side (see
        // renderPiece); the head leads at pts[-1] (headPos), and pts[pieces] is the trailing guide point.
        for (int i = 0; i < pieces; i++) {
            Vec3 self = pts[i];
            Vec3 ahead = i == 0 ? headPos : pts[i - 1];
            Vec3 toward; // direction from this piece toward the head, i.e. away from the piece behind it
            if (self.distanceToSqr(pts[i + 1]) > 1.0e-6) {
                toward = self.subtract(pts[i + 1]);
            } else if (self.distanceToSqr(ahead) > 1.0e-6) {
                toward = ahead.subtract(self); // degenerate (still worm): align with the piece ahead
            } else {
                toward = headPos.subtract(self);
            }
            renderPiece(pieceFor(i), self, self.add(toward), headPos, poseStack, buffers, lightAt(entity, self));
        }

        super.render(entity, entityYaw, partialTick, poseStack, buffers, packedLight);
    }

    private LurkerPart pieceFor(int index) {
        if (index >= SEGMENT_COUNT) return tail[index - SEGMENT_COUNT]; // base, middle, end in order
        for (int p : PAWS_AT) {
            if (index == p) return paws;
        }
        return bodyCenter; // CENTER_AT (the only remaining body index)
    }

    // World-space length (blocks) of the body piece at `index`, used as the gap to the piece behind it so
    // each piece's body_child seam meets the next piece's body_parrent seam. Pieces without seam points
    // (the placeholder segments and tail) fall back to a constant spacing.
    private double pieceLength(int index) {
        if (index >= SEGMENT_COUNT) {
            int t = index - SEGMENT_COUNT;
            if (t == 0) return TAIL_START_LEN;  // gap from tail base to middle
            if (t == 1) return TAIL_MIDDLE_LEN; // gap from tail middle to end
            return DEFAULT_LEN;                 // tail end (last piece; value unused)
        }
        for (int p : PAWS_AT) {
            if (index == p) return PAWS_LEN;
        }
        if (index == CENTER_AT) return CENTER_LEN;
        return DEFAULT_LEN;
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
