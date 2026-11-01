package com.fogged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.ai.control.MoveControl;

/**
 * Lets a strafe go faster than vanilla's fixed quarter speed: {@link MoveControl#strafe} always sets
 * the speed modifier to 0.25, which suits a skeleton edging sideways but leaves a circling mob crawling.
 */
@Mixin(MoveControl.class)
public interface MoveControlAccessor {
    @Accessor("speedModifier")
    void fogged$setSpeedModifier(double speedModifier);
}
