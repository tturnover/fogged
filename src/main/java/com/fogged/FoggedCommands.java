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
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
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
 *   <li>{@code /fogged puddle [pos] [instant]} (op) -- debug-trigger a {@link FogMoss} puddle event,
 *       as if vegetation had wilted there. {@code pos} defaults to the source's own position, so
 *       {@code /fogged puddle} alone drops one under your feet; {@code instant} fast-forwards it to an
 *       established state the way a freshly-revealed chunk gets, instead of growing it out over time.</li>
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
                        .executes(ctx -> spawn(ctx, false))
                        .then(Commands.literal("noai")
                                .executes(ctx -> spawn(ctx, true))))
                .then(Commands.literal("fogheight")
                        .executes(FoggedCommands::fogHeight))
                .then(Commands.literal("depth")
                        .executes(ctx -> depth(ctx, ctx.getSource().getPlayerOrException()))
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ctx -> depth(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("puddle")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> puddle(ctx, sourceBlockPos(ctx), false))
                        .then(Commands.literal("instant")
                                .executes(ctx -> puddle(ctx, sourceBlockPos(ctx), true)))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> puddle(ctx, BlockPosArgument.getBlockPos(ctx, "pos"), false))
                                .then(Commands.literal("instant")
                                        .executes(ctx -> puddle(ctx,
                                                BlockPosArgument.getBlockPos(ctx, "pos"), true)))));
        event.getDispatcher().register(fogged);
    }

    private static int spawn(CommandContext<CommandSourceStack> ctx, boolean noAi) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No server level."));
            return 0;
        }

        // Normally buried a few blocks ahead of the source. The no-AI debug spawn instead drops right at
        // the source's head (eye level), frozen and laid out straight, so it's right in front of you.
        Vec3 look = Vec3.directionFromRotation(0.0F, src.getRotation().y);
        double x, y, z;
        if (noAi) {
            x = src.getPosition().x;
            z = src.getPosition().z;
            y = src.getEntity() != null ? src.getEntity().getEyeY() : src.getPosition().y + 1.6;
        } else {
            x = src.getPosition().x + look.x * 6.0;
            z = src.getPosition().z + look.z * 6.0;
            double ground = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            y = Math.min(ground, src.getPosition().y) - FogLurker.BURROW_DEPTH;
        }

        FogLurker lurker = ModEntities.FOG_LURKER.get().create(level);
        if (lurker == null) {
            src.sendFailure(Component.literal("Could not create fog lurker."));
            return 0;
        }
        lurker.moveTo(x, y, z, src.getRotation().y, 0.0F);
        lurker.finalizeSpawn(level, level.getCurrentDifficultyAt(lurker.blockPosition()),
                MobSpawnType.COMMAND, null);
        lurker.setFrozen(noAi);
        if (noAi) {
            lurker.setPersistenceRequired(); // keep the debug dummy around even if you wander off
        }
        level.addFreshEntity(lurker);

        src.sendSuccess(() -> Component.literal(String.format(
                "Spawned a fog lurker%s at %.1f %.1f %.1f", noAi ? " (no AI)" : "", x, y, z)), true);
        return Command.SINGLE_SUCCESS;
    }

    // Strength of a /fogged puddle debug event: a plain, unscaled reference puddle (FOG_MOSS_STRENGTH_LEAVES
    // is the same 1.0 vanilla growth already uses for a wilted leaf, so this reads the same size).
    private static final double DEBUG_PUDDLE_STRENGTH = 1.0;

    private static BlockPos sourceBlockPos(CommandContext<CommandSourceStack> ctx) {
        return BlockPos.containing(ctx.getSource().getPosition());
    }

    private static int puddle(CommandContext<CommandSourceStack> ctx, BlockPos pos, boolean instant) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No server level."));
            return 0;
        }
        FogMoss.puddleAt(level, pos, DEBUG_PUDDLE_STRENGTH, instant);
        src.sendSuccess(() -> Component.literal(String.format(
                "Triggered a fog-moss puddle at %d %d %d%s",
                pos.getX(), pos.getY(), pos.getZ(), instant ? " (instant)" : "")), true);
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
