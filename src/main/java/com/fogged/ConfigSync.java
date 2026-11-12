package com.fogged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

/**
 * Sends a client the server's values for the gameplay options it also reads (see
 * {@link Config#SYNCED}): on joining, and again whenever the server's config file is reloaded.
 *
 * <p>Common code on purpose: the receiving side touches nothing client-only. It ignores a payload
 * that arrived over the in-memory connection -- that is the integrated server's own client, which
 * already shares the server's spec -- and drops the values again on logout ({@code FoggedClient}).
 */
public final class ConfigSync {

    private ConfigSync() {}

    public record Payload(Map<String, Object> values) implements CustomPacketPayload {
        public static final Type<Payload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "common_config"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Payload> CODEC =
                StreamCodec.of(ConfigSync::write, ConfigSync::read);

        @Override
        public Type<Payload> type() {
            return TYPE;
        }
    }

    static void register(IEventBus modEventBus) {
        modEventBus.addListener(ConfigSync::registerPayloads);
        modEventBus.addListener(ConfigSync::onConfigReload);
        NeoForge.EVENT_BUS.addListener(ConfigSync::onLogin);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // Optional: a client without it still connects, and just keeps drawing from its own file.
        event.registrar("1").optional().playToClient(Payload.TYPE, Payload.CODEC, ConfigSync::receive);
    }

    private static void receive(Payload payload, IPayloadContext context) {
        if (!context.connection().isMemoryConnection()) {
            Config.acceptServerValues(payload.values());
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            send(player, snapshot());
        }
    }

    // The file watcher reloads on its own thread; hop to the server's before touching connections.
    private static void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() != Config.COMMON_SPEC || event.getConfig().getType() != ModConfig.Type.COMMON) {
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            Payload payload = snapshot();
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                send(player, payload);
            }
        });
    }

    private static void send(ServerPlayer player, Payload payload) {
        if (player.connection.hasChannel(Payload.TYPE)) {
            player.connection.send(payload);
        }
    }

    private static Payload snapshot() {
        Map<String, Object> values = new HashMap<>();
        for (ModConfigSpec.ConfigValue<?> value : Config.SYNCED) {
            values.put(String.join(".", value.getPath()), value.get());
        }
        return new Payload(values);
    }

    // --- wire format: entry count, then per entry its dotted path, a type tag and the value ---

    private static final byte BOOL = 0;
    private static final byte INT = 1;
    private static final byte DOUBLE = 2;
    private static final byte STRINGS = 3;

    private static void write(RegistryFriendlyByteBuf buf, Payload payload) {
        buf.writeVarInt(payload.values().size());
        for (Map.Entry<String, Object> e : payload.values().entrySet()) {
            buf.writeUtf(e.getKey());
            Object v = e.getValue();
            if (v instanceof Boolean b) {
                buf.writeByte(BOOL);
                buf.writeBoolean(b);
            } else if (v instanceof Integer i) {
                buf.writeByte(INT);
                buf.writeVarInt(i);
            } else if (v instanceof Double d) {
                buf.writeByte(DOUBLE);
                buf.writeDouble(d);
            } else if (v instanceof List<?> list) {
                buf.writeByte(STRINGS);
                buf.writeVarInt(list.size());
                for (Object o : list) {
                    buf.writeUtf(String.valueOf(o));
                }
            } else {
                throw new IllegalArgumentException("Unsyncable config value at " + e.getKey() + ": " + v);
            }
        }
    }

    private static Payload read(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        Map<String, Object> values = new HashMap<>();
        for (int n = 0; n < count; n++) {
            String path = buf.readUtf();
            byte tag = buf.readByte();
            Object v = switch (tag) {
                case BOOL -> buf.readBoolean();
                case INT -> buf.readVarInt();
                case DOUBLE -> buf.readDouble();
                case STRINGS -> {
                    int size = buf.readVarInt();
                    List<String> list = new ArrayList<>(size);
                    for (int i = 0; i < size; i++) {
                        list.add(buf.readUtf());
                    }
                    yield List.copyOf(list);
                }
                default -> throw new IllegalArgumentException("Unknown config value tag " + tag + " at " + path);
            };
            values.put(path, v);
        }
        return new Payload(values);
    }
}
