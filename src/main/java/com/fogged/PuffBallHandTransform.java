package com.fogged;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;

/**
 * The puff ball's first-person pose while breathing: a steady raise to the mouth. The item is
 * {@link net.minecraft.world.item.UseAnim#NONE} (no vanilla wobble or sipping sound), so this draws the
 * raise itself -- vanilla's drink transform minus its cosine bob. Returns {@code false} when not using so
 * vanilla keeps the idle/swing pose.
 */
public final class PuffBallHandTransform implements IClientItemExtensions {
    @Override
    public boolean applyForgeHandTransform(PoseStack poseStack, LocalPlayer player, HumanoidArm arm,
            ItemStack itemInHand, float partialTick, float equipProcess, float swingProcess) {
        if (!player.isUsingItem() || player.getUseItemRemainingTicks() <= 0) {
            return false;
        }
        HumanoidArm usedArm = player.getUsedItemHand() == InteractionHand.MAIN_HAND
                ? player.getMainArm() : player.getMainArm().getOpposite();
        if (arm != usedArm) {
            return false;
        }

        int i = arm == HumanoidArm.RIGHT ? 1 : -1;
        // Raise first, then the base-hand offset -- vanilla's DRINK order. Reversed, the matrices compose
        // wrong and fling the item off-screen (looks invisible).
        float f = player.getUseItemRemainingTicks() - partialTick + 1.0F;
        float f3 = 1.0F - (float) Math.pow(f / itemInHand.getUseDuration(player), 27.0); // eases 0->1, then holds
        poseStack.translate(f3 * 0.6F * i, f3 * -0.5F, 0.0F);
        poseStack.mulPose(Axis.YP.rotationDegrees(i * f3 * 90.0F));
        poseStack.mulPose(Axis.XP.rotationDegrees(f3 * 10.0F));
        poseStack.mulPose(Axis.ZP.rotationDegrees(i * f3 * 30.0F));
        poseStack.translate(i * 0.56F, -0.52F + equipProcess * -0.6F, -0.72F);
        return true;
    }
}
