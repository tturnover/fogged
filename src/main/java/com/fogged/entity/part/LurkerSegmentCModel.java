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

/** Body-segment variant C: a slimmer 6x6 box with a short fin and a pair of side spikes. */
public class LurkerSegmentCModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_segment_c"), "main");

    public LurkerSegmentCModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("seg", CubeListBuilder.create()
                        .texOffs(0, 70).addBox(-3.0F, -3.0F, -4.0F, 6.0F, 6.0F, 8.0F)
                        .texOffs(40, 56).addBox(-0.5F, -9.0F, -3.0F, 1.0F, 5.0F, 7.0F)
                        .texOffs(78, 40).addBox(-4.0F, -1.0F, -2.0F, 1.0F, 2.0F, 4.0F)   // left spike
                        .texOffs(90, 40).addBox(3.0F, -1.0F, -2.0F, 1.0F, 2.0F, 4.0F),   // right spike
                PartPose.offset(0.0F, 24.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }
}
