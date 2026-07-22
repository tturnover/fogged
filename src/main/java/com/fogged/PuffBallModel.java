package com.fogged;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.item.ItemDisplayContext;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/**
 * Baked item model for the puff ball. 1.21.1 has no per-context model definitions, so this wrapper
 * swaps geometry by display context via NeoForge's {@link #applyTransform}: a flat {@code item/generated}
 * icon in the GUI, the 3D fur ball everywhere else (hand/ground/frame/head). Installed over the item's
 * baked model in {@link FoggedClient}.
 */
public class PuffBallModel extends BakedModelWrapper<BakedModel> {
    private final BakedModel gui;

    public PuffBallModel(BakedModel model3d, BakedModel gui) {
        super(model3d);
        this.gui = gui;
    }

    @Override
    public BakedModel applyTransform(ItemDisplayContext ctx, PoseStack poseStack, boolean applyLeftHandTransform) {
        BakedModel target = ctx == ItemDisplayContext.GUI ? gui : originalModel;
        return target.applyTransform(ctx, poseStack, applyLeftHandTransform);
    }

    // GuiGraphics reads these off the wrapper (before applyTransform swaps in the flat model) to decide
    // GUI lighting and depth. Report the flat GUI model's values so the icon gets flat, front-lit shading.
    @Override
    public boolean isGui3d() {
        return gui.isGui3d();
    }

    @Override
    public boolean usesBlockLight() {
        return gui.usesBlockLight();
    }
}
