package com.fogged;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The {@code /fogged} command tree:
 * <ul>
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
                .then(Commands.literal("fogheight")
                        .executes(FoggedCommands::fogHeight))
                .then(Commands.literal("depth")
                        .executes(ctx -> depth(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> depth(ctx, EntityArgument.getPlayer(ctx, "player")))));
        event.getDispatcher().register(fogged);
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
