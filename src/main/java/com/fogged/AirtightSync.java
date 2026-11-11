package com.fogged;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Tells each player whether they are breathing a sealed room's air.
 *
 * <p>Breathing runs on both sides: the client ticks its own copy of the air bar so it moves smoothly,
 * and the server only sends a correction when its own value changes. The rooms live on the server
 * alone, so without this the client thinks a player in a sealed room is in the murk and drains the bar
 * it shows, while the server -- knowing better -- keeps the real air full and so never corrects it.
 * The nozzle filter's spheres avoid this by mirroring themselves to the client; a room is too big to
 * mirror cheaply, and the client only ever asks about its own player, so this sends just that answer,
 * and only when it changes.
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class AirtightSync {

    /** Whether the receiving player's eyes are in a sealed room's air. */
    public record Status(boolean sheltered) implements CustomPacketPayload {
        public static final Type<Status> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "airtight_status"));
        public static final StreamCodec<ByteBuf, Status> CODEC =
                ByteBufCodecs.BOOL.map(Status::new, Status::sheltered);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * The cells of the room the receiving player is in, so the client can clear the murk out of it
     * (see {@code AirtightView}); empty when they are in none. World coordinates.
     */
    public record RoomCells(long anchor, boolean plot, long[] cells) implements CustomPacketPayload {
        public static final Type<RoomCells> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "airtight_room"));
        public static final StreamCodec<FriendlyByteBuf, RoomCells> CODEC = StreamCodec.of(
                (buf, r) -> {
                    buf.writeLong(r.anchor);
                    buf.writeBoolean(r.plot);
                    buf.writeLongArray(r.cells);
                },
                buf -> new RoomCells(buf.readLong(), buf.readBoolean(), buf.readLongArray()));

        static RoomCells none() {
            return new RoomCells(0L, false, new long[0]);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** How long a pressurizing room may grow before its new cells are sent again, in ticks. */
    private static final int ROOM_RESEND = 10;
    /** How far from a room's compressor a player can wander before the room is dropped from their view. */
    private static final double ROOM_KEEP = 64.0;

    // The room each player was last sent, and how many cells it had then.
    private static final Map<UUID, AirtightRooms.Space> ROOM = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> ROOM_SIZE = new ConcurrentHashMap<>();

    /**
     * The room the local player was last sent, read by the client renderers. A room on a Sable ship
     * ({@code plot}) has its cells in the ship's plot, found through its compressor at {@code anchor}.
     */
    record ClientRoom(LongOpenHashSet cells, BlockPos anchor, boolean plot) {
        static final ClientRoom NONE = new ClientRoom(new LongOpenHashSet(), BlockPos.ZERO, false);
    }

    static volatile ClientRoom clientRoom = ClientRoom.NONE;

    private static final Map<UUID, Boolean> LAST = new ConcurrentHashMap<>();

    /**
     * Each player's room-air signal, 0..1. Taken straight from Starship EVO, whose oxygen arrives in
     * occasional molecule hits: a tick in room air sets it to 1, a tick out of it decays it
     * exponentially, and the player breathes while it stays above {@link #THRESHOLD}. Here every tick
     * is a sure answer rather than a lucky hit, so the decay is not hiding gaps between samples -- it
     * is the air a player carries out of a room, which keeps a doorway, a cell boundary or a door
     * swinging from flickering the air bar. {@link Config#AIRTIGHT_GRACE} sets how long it lasts.
     */
    private static final Map<UUID, Float> SIGNAL = new ConcurrentHashMap<>();
    /** The signal a player breathes above. */
    private static final float THRESHOLD = 0.25F;
    /** The server's answer per player, smoothed; read by {@link #sheltered}. */
    private static final Map<UUID, Boolean> SHELTERED = new ConcurrentHashMap<>();

    /** Whether {@code player} breathes a sealed room's air, smoothed (see {@link #SIGNAL}). */
    static boolean sheltered(net.minecraft.world.entity.player.Player player) {
        if (player.level().isClientSide) {
            return localSheltered; // the client only ever asks about its own player
        }
        return SHELTERED.getOrDefault(player.getUUID(), false);
    }

    // Per-tick decay that takes the signal from 1 down to THRESHOLD in the configured grace time.
    private static float decay() {
        double ticks = Config.AIRTIGHT_GRACE.get() * 20.0;
        return ticks <= 0.0 ? 0.0F : (float) Math.pow(THRESHOLD, 1.0 / ticks);
    }

    // --- Client side ---

    /** What the server last said about the local player. */
    static volatile boolean localSheltered;
    /** The local player's eye position, supplied by the client (null on a dedicated server). */
    static volatile Supplier<Vec3> localEye = () -> null;

    /**
     * Client-side answer for a point: true only for the local player's own eyes, while the server
     * says they are in a room's air. Every client-side question about breathing is about that player.
     */
    static boolean clientBreathable(Vec3 point) {
        if (!localSheltered) {
            return false;
        }
        Vec3 eye = localEye.get();
        return eye != null && eye.distanceToSqr(point) < 0.25;
    }

    // --- Server side ---

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        float decay = decay();
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            float signal = AirtightRooms.isBreathable(player.level(), player.getEyePosition())
                    ? 1.0F
                    : SIGNAL.getOrDefault(id, 0.0F) * decay;
            SIGNAL.put(id, signal);
            boolean now = signal > THRESHOLD;
            SHELTERED.put(id, now);
            Boolean was = LAST.put(id, now);
            if (was == null ? now : was != now) {
                PacketDistributor.sendToPlayer(player, new Status(now));
            }
            syncRoom(player, id, event.getServer().getTickCount());
        }
    }

    // Send the room the player stands in, again as it grows, and nothing once they are well clear of
    // it or it has gone. Stepping out of a door does not clear it: the client decides from the cells
    // whether the camera is inside, so the murk closes in the moment it leaves, with no round trip.
    private static void syncRoom(ServerPlayer player, UUID id, int tick) {
        AirtightRooms.Space last = ROOM.get(id);
        AirtightRooms.Space here = AirtightRooms.spaceAt(player.level(), player.getEyePosition());
        AirtightRooms.Space keep = here != null ? here : last;
        if (keep != null && (!AirtightRooms.isOpen(player.level(), keep) || keep.cells().isEmpty()
                || AirtightRooms.worldAnchor(player.level(), keep).distanceToSqr(player.position())
                        > ROOM_KEEP * ROOM_KEEP)) {
            keep = null;
        }
        int size = keep == null ? 0 : keep.cells().size();
        Integer lastSize = ROOM_SIZE.get(id);
        boolean changed = keep != last
                || (lastSize != null && size != lastSize && tick % ROOM_RESEND == 0);
        if (!changed) {
            return;
        }
        if (keep == null) {
            ROOM.remove(id);
        } else {
            ROOM.put(id, keep);
        }
        ROOM_SIZE.put(id, size);
        PacketDistributor.sendToPlayer(player, keep == null ? RoomCells.none()
                : new RoomCells(keep.anchor.asLong(), keep.plot, keep.cells().toLongArray()));
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST.remove(id);
        SIGNAL.remove(id);
        SHELTERED.remove(id);
        ROOM.remove(id);
        ROOM_SIZE.remove(id);
    }

    @SubscribeEvent
    static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        ROOM.remove(event.getEntity().getUUID());
        ROOM_SIZE.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) {
            PacketDistributor.sendToPlayer(player, RoomCells.none());
        }
    }

    static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").optional().playToClient(Status.TYPE, Status.CODEC,
                (status, ctx) -> ctx.enqueueWork(() -> localSheltered = status.sheltered()));
        event.registrar("1").optional().playToClient(RoomCells.TYPE, RoomCells.CODEC,
                (room, ctx) -> ctx.enqueueWork(() -> clientRoom = room.cells().length == 0 ? ClientRoom.NONE
                        : new ClientRoom(new LongOpenHashSet(room.cells()), BlockPos.of(room.anchor()), room.plot())));
    }

    private AirtightSync() {}
}
