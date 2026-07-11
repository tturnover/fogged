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
 * Last tail piece (the tapering fin end), imported from the {@code fog_lurker_tail_end} Blockbench model
 * (shared 64x64 tail texture). The {@code bone} root is re-anchored from the export's offset(0,12.5,1) to
 * (0,23,6) so the model's body_parrent point ([0,10.5,-5] in Blockbench) lands on the trail line at
 * (0,24,0). The three angled {@code cube_r} planes are the splayed tail fins; they ride the bone unchanged.
 */
public class LurkerTailEndModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_tail_end"), "main");

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker_tail.png");

    public LurkerTailEndModel(ModelPart root) {
        super(root);
    }

    @Override
    public ResourceLocation texture() {
        return TEXTURE;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // Export had bone at offset(0,12.5,1); re-anchored to (0,23,6) so body_parrent sat at (0,24,0),
        // then raised 1 unit (offset y -1 -> 22) to follow the tail middle's raised seam continuously.
        PartDefinition bone = root.addOrReplaceChild("bone", CubeListBuilder.create()
                        .texOffs(3, 14).addBox(-1.0F, 0.0F, -6.0F, 2.0F, 2.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 22.0F, 6.0F));

        bone.addOrReplaceChild("cube_r1", CubeListBuilder.create()
                        .texOffs(18, 9).addBox(0.0F, -4.3F, -4.0F, 0.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 1.4F, 0.0F, 0.0F, 0.0F, -0.2182F));

        bone.addOrReplaceChild("cube_r2", CubeListBuilder.create()
                        .texOffs(18, 9).addBox(0.0F, -4.3F, -4.0F, 0.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 2.0F, 0.0F, 0.0F, 0.0F, -0.7854F));

        bone.addOrReplaceChild("cube_r3", CubeListBuilder.create()
                        .texOffs(18, 9).addBox(0.0F, -4.3F, -4.0F, 0.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offsetAndRotation(0.0F, 2.0F, 0.0F, 0.0F, 0.0F, 0.7854F));

        return LayerDefinition.create(mesh, 64, 64);
    }
}
