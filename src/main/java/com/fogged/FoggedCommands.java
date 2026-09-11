package com.fogged;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The {@code /fogged} command tree:
 * <ul>
 *   <li>{@code /fogged fogheight} -- report the current fog surface Y in this dimension.</li>
 *   <li>{@code /fogged depth [player]} -- report how far a player's eyes are below the fog surface.</li>
 *   <li>{@code /fogged pillars} -- why the towers near you came out as they did.</li>
 *   <li>{@code /fogged locate pillars|ridge|isle} -- the nearest karst tower of that kind, as a
 *       clickable position.</li>
 * </ul>
 *
 * <p>Locating is here rather than under vanilla's {@code /locate} because that command only searches
 * structures, biomes and points of interest, and the towers are a worldgen feature -- no structure
 * tag can reach them. It loses nothing by it: towers are a pure function of the seed and their cell,
 * so this answers from arithmetic alone, without loading the chunks in between.
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
                                .executes(ctx -> depth(ctx, EntityArgument.getPlayer(ctx, "player")))))
                .then(Commands.literal("pillars")
                        .executes(FoggedCommands::pillarReport))
                .then(Commands.literal("locate")
                        .then(Commands.literal("pillars")
                                .executes(ctx -> locatePillars(ctx, KarstPillarsFeature.Layout.ANY)))
                        .then(Commands.literal("ridge")
                                .executes(ctx -> locatePillars(ctx, KarstPillarsFeature.Layout.RIDGE)))
                        .then(Commands.literal("isle")
                                .executes(ctx -> locatePillars(ctx, KarstPillarsFeature.Layout.ISLE))));
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

    // How far out to look before giving up. Cheap enough to be generous -- no chunk is touched -- but
    // bounded, since nothing stops someone setting a spacing that leaves the world nearly empty.
    // Scaled off the spacing rather than fixed: at the default one group per ten thousand blocks, a
    // fixed ten-thousand-block range would routinely report nothing when there was simply a group a
    // little further out.
    private static int locateRange() {
        return Math.max(10000, Config.PILLAR_GROUP_SPACING.getAsInt() * 5);
    }

    private static String describe(KarstPillarsFeature.Layout kind) {
        return switch (kind) {
            case RIDGE -> "karst ridge";
            case ISLE -> "karst isle";
            default -> "karst pillar";
        };
    }

    // Why the towers near you came out as they did: how many the group wanted, how many the density or
    // crest-gap roll turned down, and how many were stopped by a structure standing in the way.
    private static int pillarReport(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No server level."));
            return 0;
        }
        Vec3 from = src.getPosition();
        var r = KarstPillarsFeature.report(level, Mth.floor(from.x), Mth.floor(from.z));
        src.sendSuccess(() -> Component.literal(String.format(
                "%s group at %.0f, %.0f: %d towers wanted, %d passed over by chance, %d placed",
                r.ridge() ? "Ridge" : "Isle", r.anchorX(), r.anchorZ(), r.considered(),
                r.skippedByChance(), r.placed())), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int locatePillars(CommandContext<CommandSourceStack> ctx,
            KarstPillarsFeature.Layout kind) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getLevel() instanceof ServerLevel level)) {
            src.sendFailure(Component.literal("No server level."));
            return 0;
        }
        if (!Config.GENERATE_PILLARS.get()) {
            src.sendFailure(Component.literal("Karst pillars are turned off (generatePillars)."));
            return 0;
        }
        Vec3 from = src.getPosition();
        int fromX = Mth.floor(from.x);
        int fromZ = Mth.floor(from.z);
        int range = locateRange();
        BlockPos found = KarstPillarsFeature.findNearest(level.getSeed(), fromX, fromZ, range,
                Config.maxBreathHeight(), level.getMinBuildHeight(), level.getMaxBuildHeight(), kind);
        if (found == null) {
            src.sendFailure(Component.literal("No " + describe(kind) + " within " + range
                    + " blocks. Try lowering pillarGroupSpacing."));
            return 0;
        }
        int distance = Mth.floor(Math.sqrt(found.distSqr(new BlockPos(fromX, found.getY(), fromZ))));
        // Same shape of answer vanilla's /locate gives: the position as a click-to-teleport link, then
        // how far it is.
        src.sendSuccess(() -> Component.literal("The nearest " + describe(kind) + " is at ")
                .append(ComponentUtils.wrapInSquareBrackets(
                        Component.literal(found.getX() + ", " + found.getY() + ", " + found.getZ()))
                        .withStyle(style -> style
                                .withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                                        "/tp @s " + found.getX() + " " + found.getY() + " " + found.getZ()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("Click to put a teleport in the chat box")))))
                .append(Component.literal(" (" + distance + " blocks away; that Y is its top)")), false);
        return Command.SINGLE_SUCCESS;
    }
}
