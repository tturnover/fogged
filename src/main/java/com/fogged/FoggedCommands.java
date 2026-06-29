package com.fogged;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import com.fogged.entity.FogLurker;
import com.fogged.registry.ModEntities;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The {@code /fogged} command tree:
 * <ul>
 *   <li>{@code /fogged spawnlurker} (op) -- summon a {@link FogLurker} buried in front of the source.</li>
 *   <li>{@code /fogged fogheight} -- report the current fog surface Y in this dimension.</li>
 *   <li>{@code /fogged depth [player]} -- report how far a player's eyes are below the fog surface.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Fogged.MODID)
public final class FoggedCommands {
    private FoggedCommands() {}

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> fogged = Commands.literal("fogged")
                .then(Commands.literal("spawnlurker")
                        .requires(src -> src.hasPermission(2))
                        .executes(FoggedCommands::spawn))
                .then(Commands.literal("fogheight")
                        .executes(FoggedCommands::fogHeight))
                .then(Commands.literal("depth")
                        .executes(ctx -> depth(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> depth(ctx, EntityArgument.getPlayer(ctx, "player")))));
        event.getDispatcher().register(fogged);
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No server level."));
            return 0;
        }

        // A few blocks ahead of the source, buried under the ground so it spawns underground.
        Vec3 look = Vec3.directionFromRotation(0.0F, src.getRotation().y);
        double x = src.getPosition().x + look.x * 6.0;
        double z = src.getPosition().z + look.z * 6.0;
        double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        double y = Math.min(ground, src.getPosition().y) - FogLurker.BURROW_DEPTH;

        FogLurker lurker = ModEntities.FOG_LURKER.get().create(level);
        if (lurker == null) {
            src.sendFailure(Component.literal("Could not create fog lurker."));
            return 0;
        }
        lurker.moveTo(x, y, z, src.getRotation().y, 0.0F);
        lurker.finalizeSpawn(level, level.getCurrentDifficultyAt(lurker.blockPosition()),
                MobSpawnType.COMMAND, null);
        level.addFreshEntity(lurker);

        src.sendSuccess(() -> Component.literal(String.format(
                "Spawned a fog lurker at %.1f %.1f %.1f", x, y, z)), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int fogHeight(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No server level."));
            return 0;
        }
        double surface = Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET;
        double boundary = Config.breathHeight(level);
        src.sendSuccess(() -> Component.literal(String.format(
                "Fog surface Y = %.2f (breathing boundary %.2f)", surface, boundary)), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int depth(CommandContext<CommandSourceStack> ctx, ServerPlayer player) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        double surface = Config.breathHeight(player.level()) + Config.PLANE_SURFACE_OFFSET;
        double depth = surface - player.getEyeY();
        String where = depth >= 0.0
                ? String.format("%.2f blocks under the fog", depth)
                : String.format("%.2f blocks above the fog", -depth);
        src.sendSuccess(() -> Component.literal(String.format(
                "%s: eyeY %.2f, fog surface %.2f -> %s",
                player.getName().getString(), player.getEyeY(), surface, where)), false);
        return Command.SINGLE_SUCCESS;
    }
}
