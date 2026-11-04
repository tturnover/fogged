package com.fogged.entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.UnaryOperator;

import com.fogged.entity.CirclePlayerGoal.Mode;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /fogged animTest}: one mob at a time circles the player, set up from chat. Picking a mob lets
 * go of any other circling one, then chat shows buttons for it, each a command that changes the mob and
 * shows the buttons again. Nothing is kept here: the mob's circle tag ({@link CirclePlayerGoal}) is the
 * state, so it survives a restart and the summon-with-a-tag way of doing it stays in step.
 *
 * <pre>
 * /fogged animTest                 the mob you look at, or the circling one, or the nearest
 * /fogged animTest select &lt;mob&gt;
 * /fogged animTest left|right|face|walk|run|jump|stay|stop
 * /fogged animTest action &lt;name&gt;   plays it; again clears it
 * </pre>
 */
public final class AnimTestControl {

    private static final double REACH = 32;
    private static final String ROOT = "/fogged animTest";

    private AnimTestControl() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("animTest")
                .requires(source -> source.hasPermission(2))
                .executes(AnimTestControl::pick)
                .then(Commands.literal("select")
                        .then(Commands.argument("mob", EntityArgument.entity())
                                .executes(ctx -> select(ctx, EntityArgument.getEntity(ctx, "mob")))))
                .then(change("left", m -> new Mode(false, m.facing(), m.run(), m.jump(), m.stay())))
                .then(change("right", m -> new Mode(true, m.facing(), m.run(), m.jump(), m.stay())))
                .then(change("face", m -> new Mode(m.right(), !m.facing(), m.run(), m.jump(), m.stay())))
                .then(change("walk", m -> new Mode(m.right(), m.facing(), false, m.jump(), m.stay())))
                .then(change("run", m -> new Mode(m.right(), m.facing(), true, m.jump(), m.stay())))
                .then(change("jump", m -> new Mode(m.right(), m.facing(), m.run(), !m.jump(), m.stay())))
                .then(change("stay", m -> new Mode(m.right(), m.facing(), m.run(), m.jump(), !m.stay())))
                .then(Commands.literal("action")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> {
                                    PathfinderMob mob = circling(ctx.getSource());
                                    return SharedSuggestionProvider.suggest(
                                            mob instanceof RiggedMob rigged ? rigged.actions() : List.of(), builder);
                                })
                                .executes(AnimTestControl::action)))
                .then(Commands.literal("stop").executes(AnimTestControl::stop));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> change(String name, UnaryOperator<Mode> change) {
        return Commands.literal(name).executes(ctx -> {
            PathfinderMob mob = circlingOrFail(ctx.getSource());
            if (mob == null) {
                return 0;
            }
            CirclePlayerGoal.setMode(mob, change.apply(CirclePlayerGoal.mode(mob.getTags())));
            return show(ctx.getSource(), mob);
        });
    }

    private static int pick(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerPlayer player = source.getPlayerOrException();
        PathfinderMob mob = lookedAt(player);
        if (mob == null) {
            mob = circling(source);
        }
        if (mob == null) {
            mob = source.getLevel().getEntitiesOfClass(PathfinderMob.class, player.getBoundingBox().inflate(REACH), Entity::isAlive)
                    .stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        }
        if (mob == null) {
            source.sendFailure(Component.literal("No mob within " + (int) REACH + " blocks."));
            return 0;
        }
        return select(ctx, mob);
    }

    private static int select(CommandContext<CommandSourceStack> ctx, Entity entity) {
        CommandSourceStack source = ctx.getSource();
        if (!(entity instanceof PathfinderMob mob)) {
            source.sendFailure(Component.literal(entity.getName().getString() + " can't circle: it doesn't walk by pathfinding."));
            return 0;
        }
        for (PathfinderMob other : allCircling(source)) {
            if (other != mob) {
                release(other);
            }
        }
        if (CirclePlayerGoal.mode(mob.getTags()) == null) {
            CirclePlayerGoal.setMode(mob, new Mode(null, false, false, false, false));
        }
        return show(source, mob);
    }

    private static int action(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PathfinderMob mob = circlingOrFail(source);
        if (mob == null) {
            return 0;
        }
        String name = StringArgumentType.getString(ctx, "name");
        if (!(mob instanceof RiggedMob rigged) || !rigged.actions().contains(name)) {
            source.sendFailure(Component.literal(mob.getName().getString() + " has no action " + name + "."));
            return 0;
        }
        rigged.setAction(name.equals(rigged.rigAction()) ? "" : name);
        return show(source, mob);
    }

    private static int stop(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PathfinderMob mob = circlingOrFail(source);
        if (mob == null) {
            return 0;
        }
        release(mob);
        Component name = mob.getName();
        source.sendSuccess(() -> Component.empty().append(name).append(" stopped circling."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static void release(PathfinderMob mob) {
        CirclePlayerGoal.setMode(mob, null);
        if (mob instanceof RiggedMob rigged) {
            rigged.setAction("");
        }
    }

    private static PathfinderMob lookedAt(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = player.pick(REACH, 0, false).getLocation(); // stops at the first block
        AABB box = new AABB(eye, end).inflate(1);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player, eye, end, box,
                e -> e instanceof PathfinderMob && e.isAlive(), REACH * REACH);
        return hit != null ? (PathfinderMob) hit.getEntity() : null;
    }

    private static List<PathfinderMob> allCircling(CommandSourceStack source) {
        List<PathfinderMob> mobs = new ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof PathfinderMob mob && CirclePlayerGoal.mode(mob.getTags()) != null) {
                    mobs.add(mob);
                }
            }
        }
        return mobs;
    }

    /** The circling mob, or the nearest if tags made more than one; null if there is none. */
    private static PathfinderMob circling(CommandSourceStack source) {
        Vec3 from = source.getPosition();
        return allCircling(source).stream()
                .filter(mob -> mob.level() == source.getLevel())
                .min(Comparator.comparingDouble(mob -> mob.distanceToSqr(from)))
                .orElse(null);
    }

    private static PathfinderMob circlingOrFail(CommandSourceStack source) {
        PathfinderMob mob = circling(source);
        if (mob == null) {
            source.sendFailure(Component.literal("Nothing is circling. Look at a mob and run " + ROOT + "."));
        }
        return mob;
    }

    private static int show(CommandSourceStack source, PathfinderMob mob) {
        Mode mode = CirclePlayerGoal.mode(mob.getTags());
        MutableComponent panel = Component.empty()
                .append(mob.getName().copy().withStyle(ChatFormatting.YELLOW))
                .append(" circling  ")
                .append(button("Stop", ROOT + " stop", false, "Let it go"))
                .append("\nWay ")
                .append(button("Left", ROOT + " left", Boolean.FALSE.equals(mode.right()), "Go left round the circle"))
                .append(" ")
                .append(button("Right", ROOT + " right", Boolean.TRUE.equals(mode.right()), "Go right round the circle"))
                .append("  ")
                .append(button("Face", ROOT + " face", mode.facing(), "Face the player, stepping sideways"))
                .append("\nGait ")
                .append(button("Walk", ROOT + " walk", !mode.run(), "Walk"))
                .append(" ")
                .append(button("Run", ROOT + " run", mode.run(), "Run"))
                .append("  ")
                .append(button("Jump", ROOT + " jump", mode.jump(), "Hop as it goes"))
                .append("  ")
                .append(button("Stay", ROOT + " stay", mode.stay(), "Stay in place, keeping everything else"));
        if (mob instanceof RiggedMob rigged && !rigged.actions().isEmpty()) {
            panel.append("\nActions");
            for (String action : rigged.actions()) {
                panel.append(" ").append(button(action, ROOT + " action " + action,
                        action.equals(rigged.rigAction()), "Plays " + action + "; click again to clear"));
            }
        }
        source.sendSuccess(() -> panel, false);
        return Command.SINGLE_SUCCESS;
    }

    private static MutableComponent button(String label, String command, boolean on, String hover) {
        return Component.literal("[" + label + "]").withStyle(style -> style
                .withColor(on ? ChatFormatting.GREEN : ChatFormatting.GRAY)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }
}
