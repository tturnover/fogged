package com.fogged;

import com.fogged.registry.ModDataComponents;

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

/**
 * A used intake filter's pollution ({@link ModDataComponents#POLLUTION}) out of the intake: it stacks
 * with filters as dirty, and dropped onto a stack of the same filter at another step the two merge at
 * the average of the two, rounded up; set down as a block it keeps its pollution, sheds it out of the
 * murk, and breaks back out as dirty as it still is; it shows as a bar that fills in the slot, in the
 * grime colour.
 *
 * <p>Set down, it becomes a used filter ({@code UsedFilterBlock}) holding its pollution in its own NBT,
 * shaded toward the grime colour as it is in an intake.
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

    // --- Set down as a block, a polluted filter becomes a used filter (UsedFilterBlock), which holds the
    // block it is and its pollution in its own NBT and sheds it out of the murk.

    /**
     * A filter's capacity in blocks of murk, to turn steps into blocks and back; one that never clogs is
     * reckoned at a nominal size, so its steps still come back as they went down.
     */
    public static int capacityOf(net.minecraft.world.item.Item item) {
        int capacity = Config.filterCapacity(new ItemStack(item));
        return capacity > 0 ? capacity : 1000;
    }

    // Set down some way other than through its item (BlockItemMixin covers that, on both sides): read the
    // pollution off the placer's hand -- which still holds the stack as it was while the event fires --
    // and swap the plain block for its used filter.
    @SubscribeEvent
    static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState placed = event.getPlacedBlock();
        if (placed.getBlock() instanceof com.fogged.block.UsedFilterBlock) {
            return; // placed as one already, and told what it holds
        }
        net.minecraft.world.item.Item item = placed.getBlock().asItem();
        if (Config.filterCapacity(new ItemStack(item)) < 0) {
            return;
        }
        if (event.getEntity() instanceof net.minecraft.world.entity.LivingEntity living) {
            for (net.minecraft.world.InteractionHand hand : net.minecraft.world.InteractionHand.values()) {
                ItemStack held = living.getItemInHand(hand);
                if (held.is(item) && step(held) > 0) {
                    becomeUsed(level, event.getPos(), placed, murkOf(held, item));
                    return;
                }
            }
        }
    }

    // The murk a stack's step comes to, rounded down: the step it breaks back out at is rounded up
    // (UsedFilterBlockEntity#step), so setting it down and picking it up again leaves it as it was.
    private static int murkOf(ItemStack stack, net.minecraft.world.item.Item item) {
        return (int) Math.floor((double) step(stack) / ModDataComponents.MAX_POLLUTION * capacityOf(item));
    }

    /**
     * What a polluted filter is placed as, on both sides (BlockItemMixin): its used filter -- its block's
     * own, or the generic one -- at its step. So the placer's client predicts the used filter itself,
     * shaded, rather than the plain block the server then swaps out, which flickered. Null leaves it be.
     */
    public static net.minecraft.world.level.block.state.BlockState placementState(ItemStack stack,
            net.minecraft.world.level.block.state.BlockState placing) {
        int step = step(stack);
        if (step <= 0 || placing == null || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem bi && bi.getBlock() == placing.getBlock())
                || Config.filterCapacity(stack) < 0) {
            return null;
        }
        return UsedFilters.usedFor(placing.getBlock()).defaultBlockState()
                .setValue(com.fogged.block.UsedFilterBlock.POLLUTION, Math.min(step, ModDataComponents.MAX_POLLUTION));
    }

    /**
     * A polluted filter has just been placed as its used filter at {@code pos}: tell it what it is and
     * holds, from the stack still in hand. On both sides, so the placer's client draws it right before
     * the server has said anything (BlockItemMixin).
     */
    public static void placed(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos, ItemStack stack) {
        if (step(stack) > 0 && stack.getItem() instanceof net.minecraft.world.item.BlockItem bi
                && level.getBlockEntity(pos) instanceof com.fogged.block.UsedFilterBlockEntity be) {
            be.init(bi.getBlock().defaultBlockState(), murkOf(stack, bi));
        }
    }

    private static void becomeUsed(net.minecraft.world.level.Level level, net.minecraft.core.BlockPos pos,
            net.minecraft.world.level.block.state.BlockState original, int murk) {
        // Its steps in the state, so it is shaded the moment it appears (UsedFilterBlock#POLLUTION).
        level.setBlock(pos, UsedFilters.usedFor(original.getBlock()).defaultBlockState()
                .setValue(com.fogged.block.UsedFilterBlock.POLLUTION,
                        com.fogged.block.UsedFilterBlockEntity.stepOf(original, murk)), 3);
        if (level.getBlockEntity(pos) instanceof com.fogged.block.UsedFilterBlockEntity be) {
            be.init(original, murk);
        }
    }

    // --- Worlds from before: placed pollution kept per chunk (ModAttachments#PLACED_POLLUTION). Each
    // such filter becomes a used filter the first time its chunk is loaded, and the record goes.

    private static final java.util.Map<net.minecraft.world.level.Level, it.unimi.dsi.fastutil.longs.LongOpenHashSet>
            LEGACY_CHUNKS = new java.util.WeakHashMap<>();

    @SubscribeEvent
    static void onChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && event.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk
                && chunk.hasData(com.fogged.registry.ModAttachments.PLACED_POLLUTION)
                && !chunk.getData(com.fogged.registry.ModAttachments.PLACED_POLLUTION).isEmpty()) {
            LEGACY_CHUNKS.computeIfAbsent(level, l -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet())
                    .add(chunk.getPos().toLong());
        }
    }

    // Not in the load event itself, which may come while the chunk is still being put in place.
    @SubscribeEvent
    static void onLevelTick(net.neoforged.neoforge.event.tick.LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        it.unimi.dsi.fastutil.longs.LongOpenHashSet chunks = LEGACY_CHUNKS.get(level);
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        long[] all = chunks.toLongArray();
        chunks.clear();
        for (long packed : all) {
            net.minecraft.world.level.chunk.LevelChunk chunk = level.getChunkSource().getChunkNow(
                    net.minecraft.world.level.ChunkPos.getX(packed), net.minecraft.world.level.ChunkPos.getZ(packed));
            if (chunk == null) {
                continue; // gone again: it comes back in with its chunk
            }
            java.util.Map<Long, Integer> map = chunk.getData(com.fogged.registry.ModAttachments.PLACED_POLLUTION);
            for (java.util.Map.Entry<Long, Integer> e : new java.util.ArrayList<>(map.entrySet())) {
                net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.of(e.getKey());
                net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
                if (e.getValue() > 0 && Config.filterCapacity(new ItemStack(state.getBlock().asItem())) >= 0) {
                    becomeUsed(level, pos, state, e.getValue());
                }
            }
            map.clear();
            chunk.setUnsaved(true);
        }
    }

    /** Client side: the bar in the slot (FoggedClient registers it). No tooltip line: the bar already says it. */
    public static final class Client {
        // Every item: what is a filter is the config's to say, and a stack only draws a bar with a step.
        static void registerDecorations(RegisterItemDecorationsEvent event) {
            IItemDecorator bar = FilterPollution.Client::drawBar;
            BuiltInRegistries.ITEM.forEach(item -> event.register(item, bar));
        }

        // The vanilla durability bar's place and size, filling left to right with the pollution, under the
        // stack's count as the durability bar is. A fog walker's fitted filter gets a bar of its own: in the
        // durability bar's place, or two pixels above it while that shows.
        private static boolean drawBar(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
            if (stack.getItem() instanceof com.fogged.item.FogWalkerItem) {
                com.fogged.FogWalker.SuitFilter fitted = com.fogged.FogWalker.filter(stack);
                if (fitted != null && fitted.worn() > 0.0F) {
                    bar(graphics, x, stack.isBarVisible() ? y + 11 : y + 13, Math.max(1, Math.round(13.0F * fitted.worn())));
                }
                return false;
            }
            int step = step(stack);
            if (step <= 0) {
                return false;
            }
            bar(graphics, x, y + 13, Math.round(13.0F * step / ModDataComponents.MAX_POLLUTION));
            // Decorators draw after the count; draw it again on top, where vanilla puts it, so the bar sits under it.
            if (stack.getCount() != 1) {
                String count = String.valueOf(stack.getCount());
                graphics.pose().pushPose();
                graphics.pose().translate(0.0F, 0.0F, 200.0F);
                graphics.drawString(font, count, x + 19 - 2 - font.width(count), y + 6 + 3, 0xFFFFFF, true);
                graphics.pose().popPose();
            }
            return false;
        }

        private static void bar(GuiGraphics graphics, int x, int top, int width) {
            int left = x + 2;
            graphics.fill(RenderType.guiOverlay(), left, top, left + 13, top + 2, 200, 0xFF000000);
            graphics.fill(RenderType.guiOverlay(), left, top, left + width, top + 1, 200, 0xFF000000 | barColor());
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
