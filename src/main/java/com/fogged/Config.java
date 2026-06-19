package com.fogged;

import net.neoforged.neoforge.common.ModConfigSpec;

// Mod config. Demonstrates how to use Neo's config APIs.
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // The visual plane sits a hair above the boundary so it doesn't z-fight with block faces on the
    // boundary. Fog and the plane both use this offset so the fog starts exactly at the visible surface.
    public static final double PLANE_SURFACE_OFFSET = 0.02;

    // ---- Underwater-breathing boundary config ----

    public static final ModConfigSpec.DoubleValue BREATH_HEIGHT = BUILDER
            .comment("World Y height of the breathing boundary. When the player's eyes are below this height",
                    "they breathe as if underwater: air supply drains and they begin to drown.")
            .defineInRange("breathHeight", 62.0, -64.0, 320.0);

    public static final ModConfigSpec.IntValue AIR_LOSS_PER_TICK = BUILDER
            .comment("Air lost per tick (out of 300) while below the breathing boundary. Higher = drown faster.")
            .defineInRange("airLossPerTick", 1, 1, 300);

    public static final ModConfigSpec.IntValue FOG_DISTANCE = BUILDER
            .comment("Render distance (in blocks) of the thick fog applied while the camera is below the",
                    "breathing boundary. Lower = denser fog / shorter view, like being underwater.")
            .defineInRange("fogDistance", 24, 4, 256);

    public static final ModConfigSpec.BooleanValue RENDER_PLANE = BUILDER
            .comment("Whether to render the semi-transparent separation plane at the breathing boundary.")
            .define("renderPlane", true);

    public static final ModConfigSpec.IntValue PLANE_RED = BUILDER
            .comment("Separation plane colour: red component (0-255).")
            .defineInRange("planeRed", 64, 0, 255);

    public static final ModConfigSpec.IntValue PLANE_GREEN = BUILDER
            .comment("Separation plane colour: green component (0-255).")
            .defineInRange("planeGreen", 128, 0, 255);

    public static final ModConfigSpec.IntValue PLANE_BLUE = BUILDER
            .comment("Separation plane colour: blue component (0-255).")
            .defineInRange("planeBlue", 255, 0, 255);

    public static final ModConfigSpec.IntValue PLANE_ALPHA = BUILDER
            .comment("Separation plane opacity (0 = invisible, 255 = opaque). Keep high so the plane",
                    "reads as the same dense fog you see beneath it; lower it to see through the plane.")
            .defineInRange("planeAlpha", 255, 0, 255);

    public static final ModConfigSpec.DoubleValue FOAM_WIDTH = BUILDER
            .comment("How far (in blocks) the white foam reaches from where the plane meets blocks and",
                    "entities. 0 disables the foam; larger = wider foam band around every edge.")
            .defineInRange("foamWidth", 1.25, 0.0, 8.0);

    public static final ModConfigSpec.BooleanValue FOAM_DEBUG = BUILDER
            .comment("Debug: render the plane as raw foam data instead of the normal look.",
                    "Red = depth-proximity edge factor, Green = sampled scene depth. If Green is a flat",
                    "single colour the depth buffer isn't being read; if Red is blank there's no edge.")
            .define("foamDebug", false);

    static final ModConfigSpec SPEC = BUILDER.build();
}
