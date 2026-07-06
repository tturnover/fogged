package com.fogged.entity.part;

import com.fogged.Fogged;

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
 * Thin furred body segment, imported from the {@code fog_lurker_body_center} Blockbench model (its own
 * 32x32 texture). The {@code main} root is re-anchored from the export's offset(0,18.1,-3) to (0,27.3,1)
 * so the model's body_parrent point ([0,9.2,-4] in Blockbench) lands on the trail line at (0,24,0): the
 * front seam sits at the placement point and the body trails backward to body_child ([0,9.2,7], +11px =
 * 0.928 blocks at SCALE), where the next piece's parent attaches. Static -- used for the body piece
 * sitting between the two paw'd segments.
 */
public class LurkerBodyCenterModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_body_center"), "main");

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker_body_center.png");

    public LurkerBodyCenterModel(ModelPart root) {
        super(root);
    }

    @Override
    public ResourceLocation texture() {
        return TEXTURE;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition meshdefinition = new MeshDefinition();
        PartDefinition partdefinition = meshdefinition.getRoot();

        // Export had main at offset(0,18.1,-3); re-anchored to (0,27.3,1) so body_parrent sits at (0,24,0).
        PartDefinition main = partdefinition.addOrReplaceChild("main", CubeListBuilder.create().texOffs(0, 5).addBox(0.0F, -11.1F, 0.2F, 0.0F, 5.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offset(0.0F, 27.3F, 1.0F));

        main.addOrReplaceChild("fur_1_right_r1", CubeListBuilder.create().texOffs(0, 5).mirror().addBox(0.0F, -4.0F, -7.0F, 0.0F, 5.0F, 11.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(-1.0F, -6.1F, 7.2F, 0.0F, 0.0F, -0.6981F));

        main.addOrReplaceChild("fur_2_right_r1", CubeListBuilder.create().texOffs(0, 5).mirror().addBox(2.0F, -8.0F, -7.0F, 0.0F, 5.0F, 11.0F, new CubeDeformation(0.0F)).mirror(false), PartPose.offsetAndRotation(-1.0F, -6.1F, 7.2F, 0.0F, 0.0F, 3.0543F));

        main.addOrReplaceChild("fur_2_left_r1", CubeListBuilder.create().texOffs(0, 5).addBox(-2.0F, -8.0F, -7.0F, 0.0F, 5.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -6.1F, 7.2F, 0.0F, 0.0F, -3.0543F));

        main.addOrReplaceChild("fur_1_left_r1", CubeListBuilder.create().texOffs(0, 5).addBox(0.0F, -4.0F, -7.0F, 0.0F, 5.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(1.0F, -6.1F, 7.2F, 0.0F, 0.0F, 0.6981F));

        main.addOrReplaceChild("cube_r1", CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, -2.0F, -1.0F, 5.0F, 5.0F, 11.0F, new CubeDeformation(0.0F)), PartPose.offsetAndRotation(0.0F, -4.0F, 0.0F, 0.0F, 0.0F, 0.7854F));

        return LayerDefinition.create(meshdefinition, 32, 32);
    }
}
