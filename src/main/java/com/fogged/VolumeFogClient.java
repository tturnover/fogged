package com.fogged;

import com.fogged.block.MurkBlock;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * The client's side of {@link VolumeFog}. The fog itself needs nothing here: it reaches the client
 * and its renderer as ordinary {@link MurkBlock}s. This only answers whether the camera is inside it,
 * for {@link FogModifier} to close the view in.
 */
public final class VolumeFogClient {

    private VolumeFogClient() {}

    /** Whether the camera sits inside murk: in a murk block's cell, below the top of what it holds. */
    public static boolean cameraInFog(Camera camera) {
        var level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        Vec3 p = camera.getPosition();
        BlockPos pos = BlockPos.containing(p);
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof MurkBlock
                && p.y - pos.getY() < state.getValue(MurkBlock.LEVEL) * 0.5;
    }
}
