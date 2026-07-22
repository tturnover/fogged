package com.fogged;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;

/**
 * The puff ball. Right-click to breathe: refills the player's air to full, then puts every puff ball on a
 * shared cooldown so a pocketful can't be chained. Can't be used with the head underwater. Uses
 * {@link UseAnim#NONE} so vanilla adds no drink wobble or sipping sound -- the raise-to-mouth is drawn by
 * {@link PuffBallHandTransform}.
 */
public class PuffBallItem extends Item {
    private static final int USE_TICKS = 32;
    private static final int COOLDOWN_TICKS = 20 * 15;

    public PuffBallItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        // A create:nozzle + a fistful is the nozzle_filter conversion; let that interaction own the click.
        if (NozzleFilterEvents.isConversionTarget(player, stack)) {
            return InteractionResultHolder.pass(stack);
        }
        if (player.isUnderWater() || player.getCooldowns().isOnCooldown(this)) {
            return InteractionResultHolder.fail(stack);
        }
        player.startUsingItem(hand);
        return InteractionResultHolder.consume(stack);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity entity) {
        if (entity instanceof Player player) {
            player.setAirSupply(player.getMaxAirSupply());
            player.getCooldowns().addCooldown(this, COOLDOWN_TICKS);
            player.awardStat(Stats.ITEM_USED.get(this));
            level.playSound(player, player, SoundEvents.PLAYER_BREATH, SoundSource.PLAYERS, 1.0F, 1.0F);
            if (!player.getAbilities().instabuild) {
                stack.shrink(1);
            }
        }
        return stack;
    }

    @Override
    public UseAnim getUseAnimation(ItemStack stack) {
        return UseAnim.NONE;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity entity) {
        return USE_TICKS;
    }
}
