package com.fogged;

import com.fogged.item.FogWalkerItem;
import com.fogged.registry.ModDataComponents;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The fog walker: a copper diving suit worn in the chest slot whose helmet takes the head slot too. With
 * the head slot empty the helmet is on, and the wearer breathes the murk through the filter fitted into
 * the suit ({@link #filtering}), which fills with murk as they do ({@link #onPlayerTick}). Clogged, the
 * filter lets no air through at all, in the murk or out of it ({@link #choking}). With another helmet on,
 * the suit's own hangs at the side: it is only armour then.
 *
 * <p>A filter is any intake filter ({@link Config#filterCapacity}) not on the suit's blacklist
 * ({@link Config#fitsFogWalker}), fitted from the inventory
 * ({@link FogWalkerItem}). It keeps how full of murk it is as a fraction of its capacity, so it comes back
 * out as polluted as it went in ({@link ModDataComponents#POLLUTION}).
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FogWalker {

    /**
     * The filter fitted into a suit: one item, its pollution stripped, and how full of murk it is, 0 clean to
     * 1 clogged.
     */
    public record SuitFilter(ItemStack stack, float worn) {
        public static final Codec<SuitFilter> CODEC = RecordCodecBuilder.create(i -> i.group(
                ItemStack.SINGLE_ITEM_CODEC.fieldOf("filter").forGetter(SuitFilter::stack),
                Codec.floatRange(0.0F, 1.0F).optionalFieldOf("worn", 0.0F).forGetter(SuitFilter::worn))
                .apply(i, SuitFilter::new));
        public static final StreamCodec<RegistryFriendlyByteBuf, SuitFilter> STREAM_CODEC = StreamCodec.composite(
                ItemStack.STREAM_CODEC, SuitFilter::stack, ByteBufCodecs.FLOAT, SuitFilter::worn, SuitFilter::new);

        public boolean clogged() {
            return worn >= 1.0F;
        }

        // ItemStack has no equals of its own; a component compares by value, or every copy looks changed.
        @Override
        public boolean equals(Object o) {
            return o instanceof SuitFilter f && f.worn == worn && ItemStack.matches(f.stack, stack);
        }

        @Override
        public int hashCode() {
            return 31 * ItemStack.hashItemAndComponents(stack) + Float.hashCode(worn);
        }
    }

    /** The fog walker {@code entity} wears in the chest slot, or empty. */
    public static ItemStack suit(LivingEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        return chest.getItem() instanceof FogWalkerItem ? chest : ItemStack.EMPTY;
    }

    /** True when {@code entity} wears a fog walker with its helmet on: nothing else in the head slot. */
    public static boolean helmeted(LivingEntity entity) {
        return !suit(entity).isEmpty() && entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
    }

    /** The filter fitted into {@code suit}, or null. */
    public static SuitFilter filter(ItemStack suit) {
        return suit.get(ModDataComponents.SUIT_FILTER.get());
    }

    /** The filter {@code entity} breathes through: helmet on and a filter fitted, clogged or not. Else null. */
    public static SuitFilter breathingThrough(LivingEntity entity) {
        return helmeted(entity) ? filter(suit(entity)) : null;
    }

    /** True when {@code entity} breathes clean air through its suit's filter, in the murk or not. */
    public static boolean filtering(LivingEntity entity) {
        SuitFilter f = breathingThrough(entity);
        return f != null && !f.clogged();
    }

    /** True when the filter {@code entity} breathes through is clogged: no air gets in anywhere. */
    public static boolean choking(LivingEntity entity) {
        SuitFilter f = breathingThrough(entity);
        return f != null && f.clogged();
    }

    /** {@code stack} (one filter) as fitted into a suit, as polluted as its {@link ModDataComponents#POLLUTION} says. */
    public static SuitFilter fit(ItemStack stack) {
        ItemStack one = stack.copyWithCount(1);
        int step = one.getOrDefault(ModDataComponents.POLLUTION.get(), 0);
        one.remove(ModDataComponents.POLLUTION.get());
        return new SuitFilter(one, Math.min(1.0F, (float) step / ModDataComponents.MAX_POLLUTION));
    }

    /** The fitted filter taken back out: its murk rounded up to the pollution steps a loose filter keeps. */
    public static ItemStack takeOut(SuitFilter filter) {
        ItemStack out = filter.stack().copyWithCount(1);
        int step = Math.min(ModDataComponents.MAX_POLLUTION,
                (int) Math.ceil(filter.worn() * ModDataComponents.MAX_POLLUTION - 1.0E-6));
        if (step > 0) {
            out.set(ModDataComponents.POLLUTION.get(), step);
        }
        return out;
    }

    /**
     * The part of {@code filter}'s capacity it fills each second its wearer breathes the murk through it:
     * one block of murk a second, more deeper down as the air loss goes ({@link Config#depthAirFactor}).
     * 0 for one that never clogs.
     */
    public static double wearPerSecond(Player player, SuitFilter filter) {
        int capacity = Config.filterCapacity(filter.stack());
        if (capacity <= 0) {
            return 0.0;
        }
        return Config.depthAirFactor(player.level(), player.getEyeY()) / capacity;
    }

    // Once a second, the filter of a wearer breathing the murk through it catches its share of it. Only
    // while it is doing the work: in clean air, or sheltered, nothing reaches it. Written once a second so
    // the suit's slot is not resent every tick.
    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        Player player = event.getEntity();
        if (player.level().isClientSide || player.tickCount % 20 != 0 || !BreathHandler.breathingMurk(player)) {
            return;
        }
        ItemStack suit = suit(player);
        SuitFilter f = breathingThrough(player);
        if (f == null || f.clogged()) {
            return;
        }
        double wear = wearPerSecond(player, f);
        if (wear > 0.0) {
            suit.set(ModDataComponents.SUIT_FILTER.get(),
                    new SuitFilter(f.stack(), (float) Math.min(1.0, f.worn() + wear)));
        }
    }

    private FogWalker() {}
}
