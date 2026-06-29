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

/** Body-segment variant A: a plain 7x7 box with a medium dorsal fin. */
public class LurkerSegmentAModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_segment_a"), "main");

    public LurkerSegmentAModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("seg", CubeListBuilder.create()
                        .texOffs(0, 32).addBox(-3.5F, -3.5F, -4.0F, 7.0F, 7.0F, 8.0F)
                        .texOffs(40, 24).addBox(-0.5F, -10.0F, -3.0F, 1.0F, 6.0F, 7.0F),
                PartPose.offset(0.0F, 24.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }
}
