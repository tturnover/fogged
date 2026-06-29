package com.fogged;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fogged.entity.FogLurker;
import com.fogged.registry.ModEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Server-side driver for the {@link FogLurker}: spawns one near each deep-enough player on a random
 * interval (30s..20min, configurable), and disturbs nearby docile ones whenever a player breaks or
 * places a block under the fog -- unless the player carries a {@link #LURKER_WARD} item, which suppresses
 * the aggro. Each disturbance ratchets the worm toward its attack (see {@link FogLurker#disturb}).
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FogLurkerEvents {
    private FogLurkerEvents() {}

    // Items that, held in any hand or worn, stop a carrier from provoking the worm.
    public static final TagKey<Item> LURKER_WARD = TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "lurker_ward"));

    // Per-player game-tick at which the next spawn may happen. Cleared when a player stops qualifying so
    // the clock restarts fresh when they next go deep.
    private static final Map<UUID, Long> NEXT_SPAWN = new HashMap<>();

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (!Config.LURKER_ENABLED.get()) {
            return;
        }
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (level.getGameTime() % 20 != 0) {
            return; // a once-a-second check is plenty for second-scale intervals
        }

        long now = level.getGameTime();
        int range = Config.LURKER_RANGE.getAsInt();
        int max = Config.LURKER_MAX_NEARBY.getAsInt();
        double surfaceY = Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
        int minDepth = Config.LURKER_MIN_DEPTH.getAsInt();

        for (ServerPlayer player : level.players()) {
            UUID id = player.getUUID();
            if (player.isCreative() || player.isSpectator() || surfaceY - player.getEyeY() < minDepth) {
                NEXT_SPAWN.remove(id);
                continue;
            }
            Long next = NEXT_SPAWN.get(id);
            if (next == null) {
                NEXT_SPAWN.put(id, now + nextInterval(level)); // start the clock on going deep
                continue;
            }
            if (now < next) {
                continue;
            }
            NEXT_SPAWN.put(id, now + nextInterval(level));
            int near = level.getEntitiesOfClass(FogLurker.class, player.getBoundingBox().inflate(range)).size();
            if (near < max) {
                trySpawnNear(level, player, range);
            }
        }
    }

    // A random spawn gap in ticks, rolled between the configured min and max seconds.
    private static long nextInterval(ServerLevel level) {
        int min = Config.LURKER_SPAWN_MIN_SECONDS.getAsInt();
        int max = Math.max(min, Config.LURKER_SPAWN_MAX_SECONDS.getAsInt());
        return (min + level.random.nextInt(max - min + 1)) * 20L;
    }

    private static void trySpawnNear(ServerLevel level, ServerPlayer player, int range) {
        double angle = level.random.nextDouble() * Math.PI * 2.0;
        double dist = range * (0.55 + 0.4 * level.random.nextDouble());
        double x = player.getX() + Math.cos(angle) * dist;
        double z = player.getZ() + Math.sin(angle) * dist;

        // Bury it under the ground (or under the player if they are deeper, e.g. in a cave).
        BlockPos col = BlockPos.containing(x, player.getY(), z);
        if (!level.isLoaded(col)) {
            return;
        }
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.getX(), col.getZ());
        double y = Math.min(ground, player.getY()) - FogLurker.BURROW_DEPTH;

        FogLurker lurker = ModEntities.FOG_LURKER.get().create(level);
        if (lurker == null) {
            return;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        lurker.moveTo(x, y, z, (float) Math.toDegrees(angle), 0.0F);
        lurker.finalizeSpawn(level, level.getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        level.addFreshEntity(lurker);
    }

    @SubscribeEvent
    static void onBlockBreak(BlockEvent.BreakEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            disturbNearby(player.level(), player);
        }
    }

    @SubscribeEvent
    static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player) {
            disturbNearby(player.level(), player);
        }
    }

    // Disturb every docile lurker in aggro range of an under-fog player, unless the player is warded.
    private static void disturbNearby(Level level, Player player) {
        if (!Config.LURKER_ENABLED.get() || level.isClientSide) {
            return;
        }
        if (!Config.fogged(level, player.getEyeY()) || isWarded(player)) {
            return;
        }
        double r = Config.LURKER_AGGRO_RADIUS.getAsInt();
        AABB area = player.getBoundingBox().inflate(r + 4.0);
        double rSq = (r + 2.0) * (r + 2.0);
        for (FogLurker lurker : level.getEntitiesOfClass(FogLurker.class, area)) {
            if (lurker.distanceToSqr(player) <= rSq) {
                lurker.disturb(player);
            }
        }
    }

    // True if the player holds (either hand) or wears a ward item.
    private static boolean isWarded(Player player) {
        for (ItemStack stack : player.getAllSlots()) {
            if (stack.is(LURKER_WARD)) {
                return true;
            }
        }
        return false;
    }
}
