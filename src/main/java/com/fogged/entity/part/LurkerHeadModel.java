package com.fogged.entity.part;

import org.joml.Vector3f;

import com.fogged.Fogged;
import com.fogged.entity.FogLurker;

import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/**
 * The worm's head, imported from the {@code fog_lurker_head} Blockbench model (its own 64x64 texture).
 * The root part {@code bone2} is lowered so the head's spine lines up with the body segments' centreline
 * (the trail line); edit that offset to raise/lower the head if it sits off the body.
 */
public class LurkerHeadModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_head"), "main");

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker_head.png");

    private final Vector3f animCache = new Vector3f();

    public LurkerHeadModel(ModelPart root) {
        super(root);
    }

    @Override
    public ResourceLocation texture() {
        return TEXTURE;
    }

    @Override
    public void setupAnim(FogLurker entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        // ageInTicks -> milliseconds for KeyframeAnimations' time base (1 tick = 50 ms).
        long ms = (long) (ageInTicks * 50.0F);
        FogLurker.State state = entity.state();
        boolean striking = state == FogLurker.State.LUNGE || state == FogLurker.State.DIVE;
        KeyframeAnimations.animate(this, striking ? LurkerAnimations.LUNGE : LurkerAnimations.BURROW,
                ms, 1.0F, animCache);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // bone2 raised from the export's y=15 to y=24.5 so the model's body_child point ([0,9.5,6] in
        // Blockbench) lands on the trail line (model y=24) -- that point is where the first body piece's
        // body_parrent seam attaches (see FogLurkerRenderer.HEAD_DIST).
        PartDefinition bone2 = partdefinition.addOrReplaceChild("bone2", CubeListBuilder.create().texOffs(33, -12).addBox(0.0F, -8.0F, -7.0F, 0.0F, 6.0F, 15.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 24.5F, -1.0F));

        bone2.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(33, -12).addBox(0.0F, -6.0F, -7.0F, 0.0F, 6.0F, 15.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -2.0F, 0.0F, 0.0F, 0.0F, -0.6981F));

        bone2.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(33, -12).addBox(0.0F, -6.0F, -7.0F, 0.0F, 6.0F, 15.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -2.0F, 0.0F, 0.0F, 0.0F, 0.6981F));

        bone2.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(0, 12).addBox(-3.0F, -6.0F, -3.0F, 6.0F, 7.0F, 6.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, 2.0F, 4.0F, 0.0F, 0.7854F, 0.0F));

        PartDefinition bone = bone2.addOrReplaceChild("bone", CubeListBuilder.create().texOffs(0, 0).addBox(-2.5F, -2.0F, -9.0F, 5.0F, 2.0F, 10.0F, new CubeDeformation(0.0F))
                .texOffs(10, 25).addBox(-1.0F, 0.0F, -9.0F, 2.0F, 4.0F, 1.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 0.0F, 2.0F));

        bone.addOrReplaceChild("right_r1", CubeListBuilder.create().texOffs(43, 10).mirror().addBox(-6.0F, 0.0F, -2.0F, 7.0F, 0.0F, 5.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(43, 10).addBox(4.0F, 0.0F, -2.0F, 7.0F, 0.0F, 5.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-2.5F, -1.0F, -9.0F, 0.48F, 0.0F, 0.0F));

        bone.addOrReplaceChild("snout", CubeListBuilder.create().texOffs(0, 25).addBox(-1.5F, -1.0F, -1.0F, 3.0F, 2.0F, 2.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, -1.0F, -10.0F));

        bone2.addOrReplaceChild("bone3", CubeListBuilder.create().texOffs(24, 12).addBox(-2.0F, -1.0F, -8.0F, 4.0F, 2.0F, 8.0F, new CubeDeformation(0.0F))
                .texOffs(24, 22).addBox(-2.0F, -2.0F, -8.0F, 4.0F, 1.0F, 8.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 1.3F, 2.0F));

        bone2.addOrReplaceChild("right", CubeListBuilder.create().texOffs(21, 1).addBox(-3.0F, -7.0F, 0.0F, 4.0F, 7.0F, 1.0F, new CubeDeformation(0.0F))
                .texOffs(0, 2).addBox(-3.0F, -7.0F, 0.9F, 4.0F, 7.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offset(-3.0F, -3.0F, 5.0F));

        bone2.addOrReplaceChild("left", CubeListBuilder.create().texOffs(21, 1).mirror().addBox(-1.0F, -7.0F, 0.0F, 4.0F, 7.0F, 1.0F, new CubeDeformation(0.0F)).mirror(false)
                .texOffs(0, 2).mirror().addBox(-1.0F, -7.0F, 0.9F, 4.0F, 7.0F, 0.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(3.0F, -3.0F, 5.0F));

        return LayerDefinition.create(meshdefinition, 64, 64);
    }
}
