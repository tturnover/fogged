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

/** The worm's tail: a tapering body ending in a tall vertical tail fin. */
public class LurkerTailModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_tail"), "main");

    public LurkerTailModel(ModelPart root) {
        super(root);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild("tail", CubeListBuilder.create()
                        .texOffs(0, 86).addBox(-2.0F, -2.0F, -4.0F, 4.0F, 4.0F, 10.0F)  // tapering body
                        .texOffs(40, 70).addBox(-0.5F, -6.0F, 5.0F, 1.0F, 11.0F, 6.0F), // vertical tail fin
                PartPose.offset(0.0F, 24.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 128);
    }
}
