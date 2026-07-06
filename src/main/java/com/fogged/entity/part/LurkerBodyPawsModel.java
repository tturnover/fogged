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
 * Body segment with hanging paws, imported from the {@code fog_lurker_body_paws} Blockbench model (its
 * own 32x32 texture). The {@code main} root is re-anchored from the export's offset(0,18.1,-3) to
 * (0,27.6,1) so the model's body_parrent point ([0,9.5,-4] in Blockbench) lands on the trail line at
 * (0,24,0): the front seam sits at the placement point and the body trails backward to body_child
 * ([0,9.5,3], +7px = 0.591 blocks at SCALE), where the next piece's parent attaches.
 * Used for the first and third body pieces behind the head.
 */
public class LurkerBodyPawsModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_body_paws"), "main");

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker_body_paws.png");

    private final Vector3f animCache = new Vector3f();

    public LurkerBodyPawsModel(ModelPart root) {
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
        long ms = (long) (ageInTicks * 50.0F); // 1 tick = 50 ms for KeyframeAnimations' time base
        FogLurker.State state = entity.state();
        boolean striking = state == FogLurker.State.LUNGE || state == FogLurker.State.DIVE;
        KeyframeAnimations.animate(this, striking ? LurkerBodyPawsAnimations.LUNGE : LurkerBodyPawsAnimations.BURROW,
                ms, 1.0F, animCache);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // Export had main at offset(0,18.1,-3); re-anchored to (0,27.6,1) so body_parrent sits at (0,24,0).
        PartDefinition main = partdefinition.addOrReplaceChild("main", CubeListBuilder.create().texOffs(0, 0).addBox(-4.0F, -7.0F, -1.0F, 8.0F, 7.0F, 7.0F, new CubeDeformation(0.0F))
        .texOffs(0, 3).addBox(0.0F, -12.1F, 0.2F, 0.0F, 6.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 27.6F, 1.0F));

        main.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(0, 3).addBox(0.0F, -6.0F, -7.0F, 0.0F, 6.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.0F, -6.1F, 7.2F, 0.0F, 0.0F, -0.6981F));

        main.addOrReplaceChild("cube_r2", CubeListBuilder.create().texOffs(0, 3).addBox(0.0F, -6.0F, -7.0F, 0.0F, 6.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -6.1F, 7.2F, 0.0F, 0.0F, 0.6981F));

        PartDefinition paw_rigth = main.addOrReplaceChild("paw_rigth", CubeListBuilder.create().texOffs(22, 14).addBox(-1.7F, -1.0F, -1.5F, 2.0F, 11.0F, 3.0F, new CubeDeformation(0.0F))
        .texOffs(3, 4).addBox(-3.0F, 9.0F, 1.0F, 2.0F, 3.0F, 0.0F, new CubeDeformation(0.0F))
        .texOffs(3, 4).addBox(-3.0F, 9.0F, -1.0F, 2.0F, 3.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offset(-4.3F, -5.0F, 2.5F));

        paw_rigth.addOrReplaceChild("cube_r3", CubeListBuilder.create().texOffs(3, 4).addBox(-2.0F, -1.0F, 0.0F, 2.0F, 3.0F, 0.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(-1.0F, 9.0F, 1.0F, 0.0F, 1.5708F, 0.0F));

        PartDefinition paw_rigth2 = main.addOrReplaceChild("paw_rigth2", CubeListBuilder.create().texOffs(22, 14).mirror().addBox(-0.3F, -1.0F, -1.5F, 2.0F, 11.0F, 3.0F, new CubeDeformation(0.0F)).mirror(false)
        .texOffs(3, 4).mirror().addBox(1.0F, 9.0F, 1.0F, 2.0F, 3.0F, 0.0F, new CubeDeformation(0.0F)).mirror(false)
        .texOffs(3, 4).mirror().addBox(1.0F, 9.0F, -1.0F, 2.0F, 3.0F, 0.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offset(4.3F, -5.0F, 2.5F));

        paw_rigth2.addOrReplaceChild("cube_r4", CubeListBuilder.create().texOffs(3, 4).mirror().addBox(0.0F, -1.0F, 0.0F, 2.0F, 3.0F, 0.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(1.0F, 9.0F, 1.0F, 0.0F, -1.5708F, 0.0F));

        return LayerDefinition.create(meshdefinition, 32, 32);
    }
}
