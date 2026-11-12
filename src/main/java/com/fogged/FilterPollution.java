package com.fogged;

import com.fogged.registry.ModDataComponents;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.event.ItemStackedOnOtherEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

/**
 * A used intake filter's pollution ({@link ModDataComponents#POLLUTION}) out of the intake: it stacks
 * with filters as dirty, and dropped onto a stack of the same filter at another step the two merge at
 * the average of the two, rounded up; set down as a block it keeps its pollution there, sheds it out of
 * the murk, and breaks back out as dirty as it still is; it shows as a bar that fills in the slot, in the grime colour, and as a tooltip line.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FilterPollution {

    /** The step of {@code stack}: 0 clean. */
    public static int step(ItemStack stack) {
        return stack.getOrDefault(ModDataComponents.POLLUTION.get(), 0);
    }

    // A whole stack dropped onto the same filter at another pollution step: merged, at the average step
    // over all of it.
    // Both sides run it, as the click itself does.
    @SubscribeEvent
    static void onStackedOnOther(ItemStackedOnOtherEvent event) {
        ItemStack carried = event.getCarriedItem();
        ItemStack target = event.getStackedOnItem();
        if (event.getClickAction() != ClickAction.PRIMARY || carried.isEmpty() || target.isEmpty()
                || carried.getItem() != target.getItem() || ItemStack.isSameItemSameComponents(carried, target)
                || (step(carried) == 0 && step(target) == 0)) {
            return;
        }
        ItemStack a = carried.copy();
        a.remove(ModDataComponents.POLLUTION.get());
        ItemStack b = target.copy();
        b.remove(ModDataComponents.POLLUTION.get());
        Slot slot = event.getSlot();
        if (!ItemStack.isSameItemSameComponents(a, b) || !slot.mayPlace(carried)) {
            return;
        }
        int room = Math.min(slot.getMaxStackSize(target), target.getMaxStackSize()) - target.getCount();
        int move = Math.min(carried.getCount(), room);
        if (move <= 0) {
            return;
        }
        // The average step over every filter in the merged stack, rounded up: mixing never cleans one.
        int total = target.getCount() + move;
        int step = (int) Math.ceil((step(target) * target.getCount() + step(carried) * move) / (double) total);
        ItemStack merged = target.copyWithCount(total);
        if (step > 0) {
            merged.set(ModDataComponents.POLLUTION.get(), step);
        } else {
            merged.remove(ModDataComponents.POLLUTION.get());
        }
        slot.set(merged);
        SlotAccess cursor = event.getCarriedSlotAccess();
        cursor.set(carried.copyWithCount(carried.getCount() - move));
        event.setCanceled(true);
    }

    // --- Set down as a block, a polluted filter keeps its pollution in the chunk (ModAttachments), as
    // blocks of murk, and sheds it out of the murk as one in an intake does (Config#FILTER_CLEAN_RATE).

    private static java.util.Map<Long, Integer> placed(net.minecraft.world.level.LevelAccessor level,
            net.minecraft.core.BlockPos pos) {
        return level.getChunk(pos) instanceof net.minecraft.world.level.chunk.LevelChunk chunk
                ? chunk.getData(com.fogged.registry.ModAttachments.PLACED_POLLUTION) : null;
    }

    private static void changed(net.minecraft.world.level.LevelAccessor level, net.minecraft.core.BlockPos pos) {
        if (level.getChunk(pos) instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            chunk.setUnsaved(true);
        }
    }

    // A filter's capacity in blocks of murk, to turn steps into blocks and back; one that never clogs is
    // reckoned at a nominal size, so its steps still come back as they went down.
    private static int capacityOf(net.minecraft.world.item.Item item) {
        int capacity = Config.filterCapacity(new ItemStack(item));
        return capacity > 0 ? capacity : 1000;
    }

    // The loaded chunks, per level, holding polluted filters to clean.
    private static final java.util.Map<net.minecraft.world.level.Level, it.unimi.dsi.fastutil.longs.LongOpenHashSet>
            DIRTY_CHUNKS = new java.util.WeakHashMap<>();

    private static void track(net.minecraft.world.level.Level level, net.minecraft.world.level.ChunkPos chunk) {
        DIRTY_CHUNKS.computeIfAbsent(level, l -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(chunk.toLong());
    }

    // Placed by a player (or a Deployer's): the hand still holds the stack as it was when the event fires,
    // so its step is read off whichever hand holds this block's item. Anything else placed here clears
    // whatever an earlier filter left.
    @SubscribeEvent
    static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        java.util.Map<Long, Integer> map = placed(event.getLevel(), event.getPos());
        if (map == null) {
            return;
        }
        int step = 0;
        net.minecraft.world.item.Item item = event.getPlacedBlock().getBlock().asItem();
        if (event.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) {
            for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                ItemStack held = living.getItemInHand(hand);
                if (held.is(item) && step(held) > 0) {
                    step = step(held);
                    break;
                }
            }
        }
        long key = event.getPos().asLong();
        if (step > 0) {
            map.put(key, (int) Math.round((double) step / ModDataComponents.MAX_POLLUTION * capacityOf(item)));
            changed(event.getLevel(), event.getPos());
            if (event.getLevel() instanceof net.minecraft.world.level.Level level) {
                track(level, new net.minecraft.world.level.ChunkPos(event.getPos()));
            }
        } else if (map.remove(key) != null) {
            changed(event.getLevel(), event.getPos());
        }
    }

    // Broken: what it drops of itself comes back as polluted as it still is, rounded up to a step.
    @SubscribeEvent
    static void onDrops(net.neoforged.neoforge.event.level.BlockDropsEvent event) {
        java.util.Map<Long, Integer> map = placed(event.getLevel(), event.getPos());
        Integer murk = map == null ? null : map.remove(event.getPos().asLong());
        if (murk == null) {
            return;
        }
        changed(event.getLevel(), event.getPos());
        net.minecraft.world.item.Item item = event.getState().getBlock().asItem();
        int step = Math.min(ModDataComponents.MAX_POLLUTION,
                (int) Math.ceil((double) murk / capacityOf(item) * ModDataComponents.MAX_POLLUTION - 1.0E-6));
        if (step <= 0) {
            return;
        }
        for (net.minecraft.world.entity.item.ItemEntity drop : event.getDrops()) {
            ItemStack stack = drop.getItem();
            if (stack.is(item)) {
                ItemStack dirty = stack.copy();
                dirty.set(ModDataComponents.POLLUTION.get(), step);
                drop.setItem(dirty);
            }
        }
    }

    // A chunk coming in with polluted filters in it: clean them while it is loaded.
    @SubscribeEvent
    static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk
                && chunk.hasData(com.fogged.registry.ModAttachments.PLACED_POLLUTION)
                && !chunk.getData(com.fogged.registry.ModAttachments.PLACED_POLLUTION).isEmpty()) {
            track(level, chunk.getPos());
        }
    }

    // Once a second: each polluted filter out of the murk sheds Config#FILTER_CLEAN_RATE blocks of it;
    // clean, it is forgotten. A record whose block is no filter any more (changed without an event) goes.
    @SubscribeEvent
    static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)
                || level.getGameTime() % 20 != 0) {
            return;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet chunks = DIRTY_CHUNKS.get(level);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        int shed = (int) Math.round(Config.FILTER_CLEAN_RATE.get());
        it.unimi.dsi.fastutil.longs.LongIterator it = chunks.iterator();
        while (it.hasNext()) {
            long packed = it.nextLong();
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(
                    net.minecraft.world.level.ChunkPos.getX(packed), net.minecraft.world.level.ChunkPos.getZ(packed));
            if (chunk == null) {
                it.remove(); // unloaded: it comes back in with its chunk
                continue;
            }
            java.util.Map<Long, Integer> map = chunk.getData(com.fogged.registry.ModAttachments.PLACED_POLLUTION);
            boolean dirty = false;
            java.util.Iterator<java.util.Map.Entry<Long, Integer>> entries = map.entrySet().iterator();
            while (entries.hasNext()) {
                java.util.Map.Entry<Long, Integer> e = entries.next();
                net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(e.getKey());
                net.minecraft.world.item.Item item = level.getBlockState(pos).getBlock().asItem();
                if (Config.filterCapacity(new ItemStack(item)) < 0) {
                    entries.remove();
                    dirty = true;
                    continue;
                }
                if (shed <= 0 || Config.fogged(level, PlaneSensor.worldY(level, pos))) {
                    continue;
                }
                int left = e.getValue() - shed;
                if (left <= 0) {
                    entries.remove();
                } else {
                    e.setValue(left);
                }
                dirty = true;
            }
            if (dirty) {
                chunk.setUnsaved(true);
            }
            if (map.isEmpty()) {
                it.remove();
            }
        }
    }

    /** Client side: the tooltip line. */
    @EventBusSubscriber(modid = Fogged.MODID, value = net.neoforged.api.distmarker.Dist.CLIENT)
    public static final class Client {
        @SubscribeEvent
        static void onTooltip(ItemTooltipEvent event) {
            int step = step(event.getItemStack());
            if (step > 0) {
                int percent = Math.round(100.0F * step / ModDataComponents.MAX_POLLUTION);
                event.getToolTip().add(Component.translatable("fogged.tooltip.filter_polluted", percent)
                        .withStyle(step >= ModDataComponents.MAX_POLLUTION ? ChatFormatting.RED : ChatFormatting.GRAY));
            }
        }

        // Every item: what is a filter is the config's to say, and a stack only draws a bar with a step.
        static void registerDecorations(RegisterItemDecorationsEvent event) {
            IItemDecorator bar = FilterPollution.Client::drawBar;
            BuiltInRegistries.ITEM.forEach(item -> event.register(item, bar));
        }

        // The vanilla durability bar's place and size, filling left to right with the pollution.
        private static boolean drawBar(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
            int step = step(stack);
            if (step <= 0) {
                return false;
            }
            int width = Math.round(13.0F * step / ModDataComponents.MAX_POLLUTION);
            int left = x + 2;
            int top = y + 13;
            graphics.fill(RenderType.guiOverlay(), left, top, left + 13, top + 2, 0xFF000000);
            graphics.fill(RenderType.guiOverlay(), left, top, left + width, top + 1, 0xFF000000 | barColor());
            return false;
        }

        // The grime colour, lightened so it reads against the bar's black.
        private static int barColor() {
            int c = Config.filterPollutionColor();
            int r = (c >> 16) & 0xFF, g = (c >> 8) & 0xFF, b = c & 0xFF;
            r += (255 - r) * 45 / 100;
            g += (255 - g) * 45 / 100;
            b += (255 - b) * 45 / 100;
            return (r << 16) | (g << 8) | b;
        }

        private Client() {}
    }

    private FilterPollution() {}
}
