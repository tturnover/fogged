package com.fogged;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Debug view of the airtight rooms: {@code /fogged airtight} toggles it for the player who runs it.
 * Rooms live only on the server ({@link AirtightRooms}), so while it is on the server sends that
 * player a {@link Snapshot} of every room whose compressor is near them -- each room's cells, state
 * and leak path -- whenever any of it changes, and the client draws them
 * ({@code AirtightDebugRenderer}). Only the players who asked are sent anything.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class AirtightDebug {

    /** How far from the player a compressor may be for its room to be sent, in blocks. */
    private static final double RANGE = 96.0;
    /** Ticks between looks for a change; a pressurizing room changes every tick, so this throttles it. */
    private static final int INTERVAL = 5;
    /** Cells sent per room at most, so a debug packet can never be the one that is too big. */
    private static final int MAX_CELLS = 65_536;

    /** One room as the debug view sees it. Cells and the leak path are packed block positions. */
    public record Room(long anchor, int state, int volume, int capacity, long[] cells, long[] leak) {
        static Room read(FriendlyByteBuf buf) {
            return new Room(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readLongArray(), buf.readLongArray());
        }

        void write(FriendlyByteBuf buf) {
            buf.writeLong(anchor);
            buf.writeVarInt(state);
            buf.writeVarInt(volume);
            buf.writeVarInt(capacity);
            buf.writeLongArray(cells);
            buf.writeLongArray(leak);
        }
    }

    /** Every room near the player; an empty one clears the view. */
    public record Snapshot(List<Room> rooms) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "airtight_debug"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of(
                (buf, snap) -> {
                    buf.writeVarInt(snap.rooms.size());
                    for (Room r : snap.rooms) {
                        r.write(buf);
                    }
                },
                buf -> {
                    int n = buf.readVarInt();
                    List<Room> rooms = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        rooms.add(Room.read(buf));
                    }
                    return new Snapshot(rooms);
                });

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // Who has the view on, and a fingerprint of what each was last sent, to send only on a change.
    private static final Set<UUID> WATCHING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Long> LAST_SENT = new ConcurrentHashMap<>();

    /** {@code /fogged airtight}: toggle the view for the running player. Needs permission level 2. */
    static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("airtight")
                .requires(src -> src.hasPermission(2))
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    boolean on = WATCHING.add(player.getUUID());
                    if (!on) {
                        WATCHING.remove(player.getUUID());
                        LAST_SENT.remove(player.getUUID());
                        PacketDistributor.sendToPlayer(player, new Snapshot(List.of()));
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(on
                            ? "Airtight debug view on: green sealed, yellow pressurizing, red the way to a leak."
                            : "Airtight debug view off."), false);
                    return 1;
                });
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (WATCHING.isEmpty() || event.getServer().getTickCount() % INTERVAL != 0) {
            return;
        }
        for (UUID id : WATCHING) {
            ServerPlayer player = event.getServer().getPlayerList().getPlayer(id);
            if (player == null) {
                continue;
            }
            List<Room> rooms = new ArrayList<>();
            long print = player.level().dimension().location().hashCode();
            for (AirtightRooms.Space s : AirtightRooms.near(player.level(), player.blockPosition(), RANGE)) {
                long[] cells = s.cells().toLongArray();
                if (cells.length > MAX_CELLS) {
                    cells = java.util.Arrays.copyOf(cells, MAX_CELLS);
                }
                long[] leak = s.leakPath;
                rooms.add(new Room(s.anchor.asLong(), s.state, s.volume, s.capacity, cells, leak));
                print = print * 31 + s.anchor.asLong();
                print = print * 31 + s.state;
                print = print * 31 + s.volume;
                print = print * 31 + s.capacity;
                print = print * 31 + cells.length;
                print = print * 31 + java.util.Arrays.hashCode(leak);
            }
            Long last = LAST_SENT.put(id, print);
            if (last == null || last != print) {
                PacketDistributor.sendToPlayer(player, new Snapshot(rooms));
            }
        }
    }

    // A player who left stops watching; one who changes dimension is re-sent from scratch.
    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        WATCHING.remove(event.getEntity().getUUID());
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        LAST_SENT.remove(event.getEntity().getUUID());
    }

    /** Registered from {@link Fogged}: the client handler lives in {@code AirtightDebugRenderer}. */
    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(Snapshot.TYPE, Snapshot.CODEC,
                (snap, ctx) -> ctx.enqueueWork(() -> ClientHook.accept(snap)));
    }

    /**
     * Indirection to the client renderer, so this class loads on a dedicated server without naming a
     * client class: the handler only ever runs on a client.
     */
    static final class ClientHook {
        static volatile java.util.function.Consumer<Snapshot> sink = snap -> {};

        static void accept(Snapshot snap) {
            sink.accept(snap);
        }
    }

    private AirtightDebug() {}
}
