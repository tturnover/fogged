package com.fogged.create;

import java.util.Locale;

import com.fogged.block.AirCompressorCore;
import com.fogged.block.AirCompressorIntakeBlock;
import com.fogged.block.AirCompressorIntakeBlockEntity;
import com.fogged.block.CompressorHost;
import com.simibubi.create.content.redstone.displayLink.DisplayLinkContext;
import com.simibubi.create.content.redstone.displayLink.source.NumericSingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.source.PercentOrProgressBarDisplaySource;
import com.simibubi.create.content.redstone.displayLink.source.SingleLineDisplaySource;
import com.simibubi.create.content.redstone.displayLink.target.DisplayTargetStats;
import com.simibubi.create.foundation.gui.ModularGuiLineBuilder;
import com.simibubi.create.foundation.utility.CreateLang;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * What a Create Display Link reads off an air compressor or an air intake. Each source gives one bare
 * value -- a number, a percent or a progress bar -- the way Create's own do, and the player labels it in
 * the link. Pick one in the link's source list.
 */
public final class AirCompressorDisplaySources {

    private static AirCompressorCore core(DisplayLinkContext context) {
        return context.getSourceBlockEntity() instanceof CompressorHost host ? host.core() : null;
    }

    private static AirCompressorIntakeBlockEntity intake(DisplayLinkContext context) {
        BlockEntity be = context.getSourceBlockEntity();
        return be instanceof AirCompressorIntakeBlockEntity i ? i : null;
    }

    /** A percent or a progress bar, chosen in the link as Create's fill level is. */
    private abstract static class Fraction extends PercentOrProgressBarDisplaySource {
        @Override
        protected boolean progressBarActive(DisplayLinkContext context) {
            return context.sourceConfig().getInt("Mode") != 0;
        }

        @Override
        public void initConfigurationWidgets(DisplayLinkContext context, ModularGuiLineBuilder builder, boolean isFirstLine) {
            super.initConfigurationWidgets(context, builder, isFirstLine);
            if (isFirstLine) {
                return;
            }
            builder.addSelectionScrollInput(0, 120, (si, l) -> si
                    .forOptions(CreateLang.translatedOptions("display_source.fill_level", "percent", "progress_bar"))
                    .titled(CreateLang.translateDirect("display_source.fill_level.display")), "Mode");
        }

        @Override
        protected boolean allowsLabeling(DisplayLinkContext context) {
            return true;
        }
    }

    /** A bare number. */
    private abstract static class Number extends NumericSingleLineDisplaySource {
        protected abstract Double value(DisplayLinkContext context);

        @Override
        protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
            Double v = value(context);
            return v == null ? EMPTY_LINE : Component.literal(AirCompressorCore.amount(v));
        }

        @Override
        protected boolean allowsLabeling(DisplayLinkContext context) {
            return true;
        }
    }

    /** How much of the room is breathable: held over its size. */
    public static class RoomAir extends Fraction {
        @Override
        protected Float getProgress(DisplayLinkContext context) {
            AirCompressorCore c = core(context);
            if (c == null) {
                return null;
            }
            int volume = c.controller().roomVolume();
            return volume <= 0 ? 0.0F : Math.min(1.0F, c.controller().heldBlocks() / (float) volume);
        }
    }

    /** The filters' pollution: the unit's average, or the intake's own. */
    public static class FilterPollution extends Fraction {
        @Override
        protected Float getProgress(DisplayLinkContext context) {
            AirCompressorCore c = core(context);
            if (c != null) {
                return (float) c.filterPollution();
            }
            AirCompressorIntakeBlockEntity i = intake(context);
            return i == null || i.filter().isEmpty() ? (i == null ? null : 0.0F) : (float) i.worn();
        }
    }

    /** Blocks of air the room holds now. */
    public static class AirHeld extends Number {
        @Override
        protected Double value(DisplayLinkContext context) {
            AirCompressorCore c = core(context);
            return c == null ? null : (double) c.controller().heldBlocks();
        }
    }

    /** Blocks of air per second the intakes supply. */
    public static class AirFlow extends Number {
        @Override
        protected Double value(DisplayLinkContext context) {
            AirCompressorCore c = core(context);
            return c == null ? null : c.supplyFlow();
        }
    }

    /** How many filters are clogged: the unit's, or the intake's own (0 or 1). */
    public static class CloggedFilters extends Number {
        @Override
        protected Double value(DisplayLinkContext context) {
            AirCompressorCore c = core(context);
            if (c != null) {
                return (double) c.cloggedFilters();
            }
            AirCompressorIntakeBlockEntity i = intake(context);
            if (i == null) {
                return null;
            }
            var s = i.getBlockState();
            return s.getValue(AirCompressorIntakeBlock.FILTERED)
                    && s.getValue(AirCompressorIntakeBlock.POLLUTION) >= AirCompressorIntakeBlock.CLOGGED ? 1.0 : 0.0;
        }
    }

    /** What the unit's air is doing, in a word or two: Sealed, Leaking, Stopped by redstone... */
    public static class Status extends SingleLineDisplaySource {
        @Override
        protected MutableComponent provideLine(DisplayLinkContext context, DisplayTargetStats stats) {
            AirCompressorCore c = core(context);
            return c == null ? EMPTY_LINE : Component.translatable(
                    "fogged.goggles.state." + c.state().name().toLowerCase(Locale.ROOT));
        }

        @Override
        protected boolean allowsLabeling(DisplayLinkContext context) {
            return true;
        }
    }

    private AirCompressorDisplaySources() {}
}
