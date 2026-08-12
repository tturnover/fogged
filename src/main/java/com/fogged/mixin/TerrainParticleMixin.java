package com.fogged.mixin;

import com.fogged.block.FogDetectorBlock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.TerrainParticle;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// The log a fog detector's pole wears lives in model data, not in the block state, and vanilla picks a
// particle sprite off the baked model without passing model data along -- so every column would crumble
// oak. Re-picking the sprite here with the position's data lets FogDetectorLogModel answer with the
// pole's actual log.
@Mixin(TerrainParticle.class)
public abstract class TerrainParticleMixin extends TextureSheetParticle {

    protected TerrainParticleMixin(ClientLevel level, double x, double y, double z) {
        super(level, x, y, z);
    }

    @Inject(method = "<init>(Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDDLnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;)V",
            at = @At("RETURN"))
    private void fogged$poleSprite(ClientLevel level, double x, double y, double z,
                                   double xSpeed, double ySpeed, double zSpeed,
                                   BlockState state, BlockPos pos, CallbackInfo ci) {
        if (!(state.getBlock() instanceof FogDetectorBlock)) {
            return;
        }
        ModelData data = level.getModelDataManager().getAt(pos);
        setSprite(Minecraft.getInstance().getBlockRenderer().getBlockModel(state)
                .getParticleIcon(data == null ? ModelData.EMPTY : data));
    }
}
