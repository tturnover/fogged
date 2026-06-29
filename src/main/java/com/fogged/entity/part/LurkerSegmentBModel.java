package com.fogged.entity.part;

import com.fogged.Fogged;

import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

/** Body-segment variant B: a fatter 8x8 box with a tall dorsal fin -- a bulge along the body. */
public class LurkerSegmentBModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_segment_b"), "main");

    public LurkerSegmentBModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("seg", CubeListBuilder.create()
                        .texOffs(0, 52).addBox(-4.0F, -4.0F, -4.0F, 8.0F, 8.0F, 8.0F)
                        .texOffs(40, 42).addBox(-0.5F, -12.0F, -3.0F, 1.0F, 8.0F, 7.0F),
                PartPose.offset(0.0F, 24.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }
}
