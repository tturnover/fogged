package com.fogged.entity.part;

import com.fogged.Fogged;
import com.fogged.entity.FogLurker;

import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;

/**
 * Base for one swappable piece of the worm (head, a body-segment variant, or the tail). Each subclass
 * is a self-contained model: it owns a {@code ModelLayerLocation LAYER} and a {@code createBodyLayer()}
 * mesh, so editing a piece -- or adding another segment variant -- only touches that one file. The
 * chain renderer ({@code FogLurkerRenderer}) bakes them and draws them strung along the worm's trail.
 *
 * <p>It is a {@link HierarchicalModel} so the head can play keyframe animations (which resolve bones by
 * name). Body pieces leave {@link #setupAnim} empty -- they are static and reused at many trail points.
 */
public abstract class LurkerPart extends HierarchicalModel<FogLurker> {
    /** Texture shared by the body pieces; the head overrides {@link #texture()} with its own. */
    public static final ResourceLocation DEFAULT_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/entity/fog_lurker.png");

    protected final ModelPart root;

    protected LurkerPart(ModelPart root) {
        this.root = root;
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(FogLurker entity, float limbSwing, float limbSwingAmount, float ageInTicks,
                          float netHeadYaw, float headPitch) {
        // Static by default; the head overrides this to play its burrow / lunge animation.
    }

    /** The texture this piece is drawn with. Override per piece to give it its own art. */
    public ResourceLocation texture() {
        return DEFAULT_TEXTURE;
    }
}
