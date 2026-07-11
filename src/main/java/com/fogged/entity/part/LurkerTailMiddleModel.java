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
 * Middle tail piece, imported from the {@code fog_lurker_tail_middle} Blockbench model (shared 64x64 tail
 * texture). The {@code bone} root is re-anchored from the export's offset(0,14.5,-5) to (0,25,1) so the
 * model's body_parrent point ([0,10.5,-6] in Blockbench) lands on the trail line at (0,24,0): the front
 * seam sits at the placement point and the body trails backward to body_child ([0,10.5,5], +11px), where
 * the tail end attaches. Sits between the tail base and the tail end.
 */
public class LurkerTailMiddleModel extends LurkerPart {

    public static final ModelLayerLocation LAYER = new ModelLayerLocation(
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "fog_lurker_tail_middle"), "main");

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker_tail.png");

    public LurkerTailMiddleModel(ModelPart root) {
        super(root);
    }

    @Override
    public ResourceLocation texture() {
        return TEXTURE;
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        // Export had bone at offset(0,14.5,-5); re-anchored to (0,25,1) so body_parrent sat at (0,24,0),
        // then raised 1 unit (offset y -1 -> 24) so its parent seam (tail start's child) anchors higher.
        root.addOrReplaceChild("bone", CubeListBuilder.create()
                        .texOffs(0, 0).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 2.0F, 11.0F, new CubeDeformation(0.0F)),
                PartPose.offset(0.0F, 24.0F, 1.0F));
        return LayerDefinition.create(mesh, 64, 64);
    }
}
