package com.fogged.mixin;

import com.fogged.FilterPollution;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// A polluted filter is placed straight as its used filter, and told what it is and holds while the stack
// is still in hand -- on the server and in the placer's client prediction alike, so the block that appears
// is the right one, shaded, from the start (FilterPollution#placementState, #placed).
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    @Inject(method = "getPlacementState", at = @At("RETURN"), cancellable = true)
    private void fogged$placeUsedFilter(BlockPlaceContext context, CallbackInfoReturnable<BlockState> cir) {
        BlockState used = FilterPollution.placementState(context.getItemInHand(), cir.getReturnValue());
        if (used != null) {
            cir.setReturnValue(used);
        }
    }

    @Inject(method = "updateCustomBlockEntityTag(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/block/state/BlockState;)Z",
            at = @At("HEAD"))
    private void fogged$tellUsedFilter(net.minecraft.core.BlockPos pos, net.minecraft.world.level.Level level,
            net.minecraft.world.entity.player.Player player, net.minecraft.world.item.ItemStack stack,
            BlockState state, CallbackInfoReturnable<Boolean> cir) {
        FilterPollution.placed(level, pos, stack);
    }
}
