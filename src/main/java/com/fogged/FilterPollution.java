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
 * the average of the two, rounded up; set down as a block it keeps its pollution there, sheds it out of
 * the murk, and breaks back out as dirty as it still is; it shows as a bar that fills in the slot, in the grime colour.
 *
 * <p>Set down, it is shaded toward the grime colour as it is in an intake: the server tells the players
 * watching a chunk how worn each filter in it is ({@link PlacedTint}), and {@code PlacedFilterRenderer}
 * draws it.
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
            sync(event.getLevel(), event.getPos());
        } else if (map.remove(key) != null) {
            changed(event.getLevel(), event.getPos());
            sync(event.getLevel(), event.getPos());
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
        sync(event.getLevel(), event.getPos());
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
                sync(level, chunk);
            }
            if (map.isEmpty()) {
                it.remove();
            }
        }
    }

    // --- Seen set down: how worn each placed filter of a chunk is, sent to the players watching it ---

    /**
     * Every polluted filter set down in one chunk, each with how worn it is (1..255 of clogged), for the
     * client to shade it by. Sent whole: a chunk holds only a few. Empty when it holds none any more.
     */
    public record PlacedTint(long chunk, long[] cells, byte[] wear)
            implements net.minecraft.network.protocol.common.custom.CustomPacketPayload {
        public static final Type<PlacedTint> TYPE = new Type<>(
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "placed_filter_tint"));
        public static final net.minecraft.network.codec.StreamCodec<net.minecraft.network.FriendlyByteBuf, PlacedTint> CODEC =
                net.minecraft.network.codec.StreamCodec.of((buf, t) -> {
                    buf.writeLong(t.chunk);
                    buf.writeLongArray(t.cells);
                    buf.writeByteArray(t.wear);
                }, buf -> new PlacedTint(buf.readLong(), buf.readLongArray(), buf.readByteArray()));

        @Override
        public Type<? extends net.minecraft.network.protocol.common.custom.CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static PlacedTint tintOf(net.minecraft.world.level.Level level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        java.util.Map<Long, Integer> map = chunk.hasData(com.fogged.registry.ModAttachments.PLACED_POLLUTION)
                ? chunk.getData(com.fogged.registry.ModAttachments.PLACED_POLLUTION) : java.util.Map.of();
        it.unimi.dsi.fastutil.longs.LongArrayList cells = new it.unimi.dsi.fastutil.longs.LongArrayList(map.size());
        it.unimi.dsi.fastutil.bytes.ByteArrayList wear = new it.unimi.dsi.fastutil.bytes.ByteArrayList(map.size());
        for (java.util.Map.Entry<Long, Integer> e : map.entrySet()) {
            net.minecraft.world.item.Item item = level.getBlockState(net.minecraft.core.BlockPos.of(e.getKey())).getBlock().asItem();
            int w = (int) Math.ceil(Math.min(1.0, (double) e.getValue() / capacityOf(item)) * 255.0);
            if (w > 0) {
                cells.add(e.getKey().longValue());
                wear.add((byte) w);
            }
        }
        return new PlacedTint(chunk.getPos().toLong(), cells.toLongArray(), wear.toByteArray());
    }

    // The chunk holding pos changed its placed filters: tell whoever watches it.
    private static void sync(net.minecraft.world.level.LevelAccessor level, net.minecraft.core.BlockPos pos) {
        if (level instanceof net.minecraft.server.level.ServerLevel server
                && server.getChunk(pos) instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
            sync(server, chunk);
        }
    }

    private static void sync(net.minecraft.server.level.ServerLevel level, net.minecraft.world.level.chunk.LevelChunk chunk) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingChunk(level, chunk.getPos(), tintOf(level, chunk));
    }

    // A player starts watching a chunk with polluted filters set down in it.
    @SubscribeEvent
    static void onChunkSent(net.neoforged.neoforge.event.level.ChunkWatchEvent.Sent event) {
        net.minecraft.world.level.chunk.LevelChunk chunk = event.getChunk();
        if (chunk.hasData(com.fogged.registry.ModAttachments.PLACED_POLLUTION)
                && !chunk.getData(com.fogged.registry.ModAttachments.PLACED_POLLUTION).isEmpty()) {
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(event.getPlayer(), tintOf(event.getLevel(), chunk));
        }
    }

    // Client side, per chunk: each placed filter's wear, 0..1. Main thread only.
    static final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap>
            CLIENT_TINT = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

    static void registerPayloads(net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(PlacedTint.TYPE, PlacedTint.CODEC, (t, ctx) -> ctx.enqueueWork(() -> {
            if (t.cells().length == 0) {
                CLIENT_TINT.remove(t.chunk());
                return;
            }
            it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap m = new it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap(t.cells().length);
            for (int i = 0; i < t.cells().length; i++) {
                m.put(t.cells()[i], (t.wear()[i] & 0xFF) / 255.0F);
            }
            CLIENT_TINT.put(t.chunk(), m);
        }));
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
