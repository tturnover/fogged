package com.fogged.rig;

import java.util.Map;
import java.util.WeakHashMap;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Draws any entity whose model the rig loaded ({@link RigAssets}): plays its idle or walk, fits it to
 * the ground ({@link GroundRig}), then draws every bone's quads with its final matrix. One renderer
 * serves every rigged entity; registering a new one is a single line naming its model.
 */
public class RiggedRenderer<T extends LivingEntity> extends EntityRenderer<T> {

    private final ResourceLocation model;
    private final Map<T, RigState> states = new WeakHashMap<>();

    public RiggedRenderer(EntityRendererProvider.Context context, ResourceLocation model) {
        super(context);
        this.model = model;
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        RigModel m = RigAssets.get(model);
        return m != null ? m.tuning().texture() : ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int light) {
        RigModel m = RigAssets.get(model);
        if (m == null) {
            super.render(entity, entityYaw, partialTick, poseStack, buffers, light);
            return;
        }
        shadowRadius = m.tuning().shadowRadius();

        RigState state = states.computeIfAbsent(entity, e -> new RigState());
        float dt = state.advance(entity, partialTick);
        float bodyYaw = Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot);
        Vec3 origin = entity.getPosition(partialTick);

        state.animate(m, entity, bodyYaw, dt);
        RigPose pose = state.pose;
        GroundRig.apply(m, pose, state, entity, origin, bodyYaw, dt);
        pose.solve(m.geometry());

        poseStack.pushPose();
        poseStack.mulPose(Axis.YP.rotationDegrees(180 - bodyYaw));
        if (entity.deathTime > 0) {
            float fall = Mth.sqrt((entity.deathTime + partialTick - 1) / 20f * 1.6f);
            poseStack.mulPose(Axis.ZP.rotationDegrees(Math.min(1, fall) * 90));
        }
        poseStack.scale(1 / 16f, 1 / 16f, 1 / 16f);

        VertexConsumer out = buffers.getBuffer(RenderType.entityCutoutNoCull(m.tuning().texture()));
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0);
        draw(m.geometry(), pose, poseStack.last(), out, light, overlay);
        poseStack.popPose();

        super.render(entity, entityYaw, partialTick, poseStack, buffers, light);
    }

    private static void draw(RigGeometry g, RigPose pose, PoseStack.Pose base, VertexConsumer out, int light, int overlay) {
        Matrix4f matrix = new Matrix4f();
        Matrix3f normalMatrix = new Matrix3f();
        Vector3f n = new Vector3f();
        for (int i = 0; i < g.bones.length; i++) {
            RigGeometry.Quad[] quads = g.bones[i].quads;
            if (quads.length == 0) {
                continue;
            }
            base.pose().mul(pose.world[i], matrix);
            matrix.normal(normalMatrix);
            for (RigGeometry.Quad q : quads) {
                normalMatrix.transform(q.normal(), n).normalize();
                float[] p = q.positions();
                float[] uv = q.uvs();
                for (int k = 0; k < 4; k++) {
                    out.addVertex(matrix, p[k * 3], p[k * 3 + 1], p[k * 3 + 2])
                            .setColor(-1)
                            .setUv(uv[k * 2], uv[k * 2 + 1])
                            .setOverlay(overlay)
                            .setLight(light)
                            .setNormal(n.x, n.y, n.z);
                }
            }
        }
    }
}
