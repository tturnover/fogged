package com.fogged;

import com.fogged.FogWalker.SuitFilter;
import com.fogged.FogWalkerModels.Model;
import com.fogged.FogWalkerModels.Part;
import com.fogged.FogWalkerModels.Quad;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

/**
 * Draws the parts of a worn fog walker the vanilla armor layer does not: the helmet (on the head, or hung
 * at the side when another helmet is worn), the canister on the chest, and the filter in it, in the
 * fitted block's own texture, going toward the murk's colour as it fills (as an intake's does). The
 * plain chestplate under them is the vanilla layer's, from the same texture. See {@link FogWalkerModels}.
 */
public class FogWalkerLayer<T extends LivingEntity, M extends HumanoidModel<T>> extends RenderLayer<T, M> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/models/armor/fog_diving_layer_1.png");

    public FogWalkerLayer(RenderLayerParent<T, M> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, T entity, float limbSwing,
            float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
        ItemStack suit = FogWalker.suit(entity);
        if (suit.isEmpty()) {
            return;
        }
        Model model = FogWalkerModels.get(FogWalker.helmeted(entity) ? FogWalkerModels.WORN : FogWalkerModels.HEADLESS);
        if (model == null) {
            return;
        }
        SuitFilter filter = FogWalker.filter(suit);
        TextureAtlasSprite sprite = null;
        int tint = 0xFFFFFFFF;
        if (filter != null && filter.stack().getItem() instanceof BlockItem block) {
            sprite = Minecraft.getInstance().getBlockRenderer()
                    .getBlockModel(block.getBlock().defaultBlockState()).getParticleIcon(ModelData.EMPTY);
            // White when clean, all the way to the grime colour clogged.
            float t = filter.worn();
            int grime = Config.filterPollutionColor();
            int r = (int) (255 + (((grime >> 16) & 0xFF) - 255) * t);
            int g = (int) (255 + (((grime >> 8) & 0xFF) - 255) * t);
            int b = (int) (255 + ((grime & 0xFF) - 255) * t);
            tint = 0xFF000000 | (r << 16) | (g << 8) | b;
        }

        // One pass per buffer, each fetched right before it is filled: asking for a second one closes the
        // first. The filter's faces only while one is fitted; the rest of the bellows always.
        draw(model, pose, buffers.getBuffer(RenderType.armorCutoutNoCull(TEXTURE)), false, null, 0xFFFFFFFF, light);
        if (sprite != null) {
            draw(model, pose, buffers.getBuffer(RenderType.armorCutoutNoCull(TextureAtlas.LOCATION_BLOCKS)), true,
                    sprite, tint, light);
        }
        if (suit.hasFoil()) {
            VertexConsumer glint = buffers.getBuffer(RenderType.armorEntityGlint());
            draw(model, pose, glint, false, null, 0xFFFFFFFF, light);
            if (sprite != null) {
                draw(model, pose, glint, true, null, 0xFFFFFFFF, light);
            }
        }
    }

    // The quads on the armor texture, or with {@code filterPass} those on the filter's (UVs into its sprite
    // when one is given).
    private void draw(Model model, PoseStack pose, VertexConsumer vc, boolean filterPass,
            TextureAtlasSprite sprite, int color, int light) {
        M parent = getParentModel();
        for (var entry : model.parts().entrySet()) {
            ModelPart part = part(parent, entry.getKey());
            if (!part.visible) {
                continue;
            }
            pose.pushPose();
            part.translateAndRotate(pose);
            PoseStack.Pose last = pose.last();
            for (Quad q : entry.getValue()) {
                if (q.filterTexture() != filterPass) {
                    continue;
                }
                float[] p = q.positions();
                float[] uv = q.uvs();
                for (int k = 0; k < 4; k++) {
                    float u = uv[k * 2];
                    float v = uv[k * 2 + 1];
                    if (sprite != null) {
                        u = sprite.getU(u);
                        v = sprite.getV(v);
                    }
                    vc.addVertex(last, p[k * 3] / 16.0F, p[k * 3 + 1] / 16.0F, p[k * 3 + 2] / 16.0F)
                            .setColor(color)
                            .setUv(u, v)
                            .setOverlay(OverlayTexture.NO_OVERLAY)
                            .setLight(light)
                            .setNormal(last, q.normal().x, q.normal().y, q.normal().z);
                }
            }
            pose.popPose();
        }
    }

    private static ModelPart part(HumanoidModel<?> model, Part part) {
        return switch (part) {
            case HEAD -> model.head;
            case BODY -> model.body;
            case RIGHT_ARM -> model.rightArm;
            case LEFT_ARM -> model.leftArm;
            case RIGHT_LEG -> model.rightLeg;
            case LEFT_LEG -> model.leftLeg;
        };
    }

    /** On every humanoid renderer: players of both skins, armor stands, zombies and the rest. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static void addLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            EntityRenderer<?> renderer = event.getSkin(skin);
            if (renderer instanceof LivingEntityRenderer living && living.getModel() instanceof HumanoidModel) {
                living.addLayer(new FogWalkerLayer(living));
            }
        }
        for (EntityType<?> type : event.getEntityTypes()) {
            EntityRenderer<?> renderer = event.getRenderer(type);
            if (renderer instanceof LivingEntityRenderer living && living.getModel() instanceof HumanoidModel) {
                living.addLayer(new FogWalkerLayer(living));
            }
        }
    }
}
