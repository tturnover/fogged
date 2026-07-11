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
 * First tail piece (the "base"), imported from the {@code fog_lurker_tail_start} Blockbench model (shared
 * 64x64 tail texture). The {@code main} root is re-anchored from the export's offset(0,15,-3) to
 * (0,24.5,1) so the model's body_parrent point ([0,9.5,-4] in Blockbench) lands on the trail line at
 * (0,24,0): the front seam sits at the placement point and the body trails backward to body_child
 * ([0,10.5,4], +8px), where the next tail piece attaches. First tail piece behind the body.
 */
public class LurkerTailStartModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_tail_start"), "main");

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker_tail.png");

    public LurkerTailStartModel(ModelPart root) {
        super(root);
    }

    @Override
    public ResourceLocation texture() {
        return TEXTURE;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // Export had main at offset(0,15,-3); re-anchored to (0,24.5,1) so body_parrent sits at (0,24,0).
        root.addOrReplaceChild("main", CubeListBuilder.create()
                        .texOffs(1, 26).addBox(-2.0F, -3.0F, -1.0F, 4.0F, 3.0F, 8.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 24.5F, 1.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }
}
