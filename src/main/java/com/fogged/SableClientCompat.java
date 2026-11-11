package com.fogged;

import org.joml.Matrix4d;
import org.joml.Vector3d;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side Sable, kept apart from {@link SableCompatibility} because it names {@link ClientSubLevel},
 * which a dedicated server does not have. Only reached when Sable is loaded.
 */
final class SableClientCompat {

    private SableClientCompat() {}

    /**
     * The matrix taking plot coordinates to world coordinates for the sub-level whose plot holds
     * {@code anchor}, at this frame's interpolated pose; null when {@code anchor} is not in a plot.
     */
    /**
     * The world-space point {@code world} in the plot of the sub-level that holds {@code anchor}, at
     * this frame's pose; null when {@code anchor} is not in a plot.
     */
    static Vec3 worldToPlot(Level level, BlockPos anchor, Vec3 world) {
        float partial = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        Matrix4d m = plotToWorld(level, anchor, partial);
        if (m == null) {
            return null;
        }
        Vector3d p = m.invert().transformPosition(new Vector3d(world.x, world.y, world.z));
        return new Vec3(p.x, p.y, p.z);
    }

    static Matrix4d plotToWorld(Level level, BlockPos anchor, float partialTick) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(anchor)) {
            return null;
        }
        LevelPlot plot = container.getPlot(new ChunkPos(anchor));
        SubLevel sub = plot == null ? null : plot.getSubLevel();
        if (sub == null) {
            return null;
        }
        var pose = sub instanceof ClientSubLevel client ? client.renderPose(partialTick) : sub.logicalPose();
        return pose.bakeIntoMatrix(new Matrix4d());
    }
}
