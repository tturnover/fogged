package com.fogged.rig;

import java.util.Map;

import net.minecraft.resources.ResourceLocation;

/** Everything loaded for one rigged entity: its geometry, what the bone names mean, animations and tuning. */
public record RigModel(
        ResourceLocation id,
        RigGeometry geometry,
        RigLayout layout,
        Map<String, RigAnimation.Bound> animations,
        RigTuning tuning) {}
