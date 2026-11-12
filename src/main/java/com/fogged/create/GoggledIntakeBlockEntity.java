package com.fogged.create;

import java.util.List;
import java.util.Locale;

import com.fogged.block.AirCompressorCore;
import com.fogged.block.AirCompressorIntakeBlock;
import com.fogged.block.AirCompressorIntakeBlockEntity;
import com.simibubi.create.api.equipment.goggles.IHaveGoggleInformation;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

/** The intake as Create's goggles see it: what it draws in, and its filter. Only made with Create installed. */
public class GoggledIntakeBlockEntity extends AirCompressorIntakeBlockEntity implements IHaveGoggleInformation {

    public GoggledIntakeBlockEntity(BlockPos pos, BlockState state) {
        super(pos, state);
    }

    @Override
    public boolean addToGoggleTooltip(List<Component> tooltip, boolean isPlayerSneaking) {
        if (level == null) {
            return false;
        }
        AirCompressorIntakeBlock.Supply supply = AirCompressorIntakeBlock.supply(level, worldPosition, getBlockState());
        tooltip.add(Component.literal(tooltip.isEmpty() ? "    " : "")
                .append(Component.translatable("fogged.goggles.intake")).withStyle(ChatFormatting.WHITE));
        tooltip.add(AirCompressorCore.line(Component.translatable("fogged.goggles.intake.air").withStyle(ChatFormatting.GRAY)));
        tooltip.add(AirCompressorCore.line(Component.literal(" ")
                .append(Component.translatable("fogged.goggles.intake." + supply.name().toLowerCase(Locale.ROOT)))
                .withStyle(supply.clean() ? ChatFormatting.GREEN : ChatFormatting.RED)));
        // What it sends its compressor, or that no compressor draws on it.
        tooltip.add(AirCompressorCore.line(Component.translatable("fogged.goggles.air_out").withStyle(ChatFormatting.GRAY)));
        tooltip.add(AirCompressorCore.line(Component.literal(" ").append(connected()
                ? Component.translatable("fogged.goggles.per_second_one", AirCompressorCore.amount(feedFlow()))
                : Component.translatable("fogged.goggles.not_connected"))
                .withStyle(connected() ? ChatFormatting.AQUA : ChatFormatting.RED)));
        tooltip.add(AirCompressorCore.line(Component.translatable("fogged.goggles.filter").withStyle(ChatFormatting.GRAY)));
        tooltip.add(AirCompressorCore.line(Component.literal(" ")
                .append(filter().isEmpty() ? Component.translatable("fogged.goggles.filter.none") : filter().getHoverName())
                .withStyle(ChatFormatting.AQUA)));
        if (!filter().isEmpty() && capacity() > 0) {
            int polluted = (int) Math.round(worn() * 100);
            tooltip.add(AirCompressorCore.line(Component.literal(" ")
                    .append(supply == AirCompressorIntakeBlock.Supply.CLOGGED
                            ? Component.translatable("fogged.goggles.filter.clogged")
                            : Component.translatable("fogged.goggles.filter.polluted", polluted))
                    .withStyle(polluted >= 100 ? ChatFormatting.RED : polluted >= 75 ? ChatFormatting.GOLD
                            : ChatFormatting.AQUA)));
        }
        return true;
    }
}
