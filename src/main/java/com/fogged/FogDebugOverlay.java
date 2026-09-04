package com.fogged;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

// Text readout of the render state, drawn while debugHud is on. Everything here is read back from the
// last frame's plane draw (FogPlaneRenderer's last* fields) and from the buffers that feed it, so the
// HUD reports what actually happened rather than recomputing its own answer and possibly disagreeing.
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class FogDebugOverlay {

    private static final int LINE_HEIGHT = 10;
    private static final int MARGIN = 4;
    private static final int OK = 0xFF7BE87B;
    private static final int BAD = 0xFFE87B7B;
    private static final int PLAIN = 0xFFE0E0E0;


    private FogDebugOverlay() {
    }

    @SubscribeEvent
    static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!Config.DEBUG_HUD.getAsBoolean() || mc.options.hideGui || mc.level == null || mc.player == null) {
            return;
        }
        // The vanilla debug screen owns this corner; don't fight it for the same pixels.
        if (mc.getDebugOverlay().showDebugScreen()) {
            return;
        }

        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double boundary = Config.breathHeight(mc.level);
        boolean fogged = Config.fogged(mc.level, cam.y);

        List<Line> lines = new ArrayList<>();
        String stage = String.valueOf(Config.PLANE_STAGE.get());
        String effective = String.valueOf(FogPlaneRenderer.murkStage());
        lines.add(new Line("Fogged  view=" + Config.DEBUG_VIEW.get()
                + "  stage=" + (effective.equals(stage) ? stage : stage + ">" + effective)
                + "  vapor=" + Config.VAPOR_STAGE.get()
                + (Config.VAPOR_UNDERWATER.getAsBoolean()
                        ? "+" + Config.VAPOR_UNDERWATER_STAGE.get() : ""), PLAIN));
        lines.add(new Line(String.format("boundary %.2f  surface %.2f  camera %.2f",
                boundary, FogPlaneRenderer.lastSurfaceY, cam.y), PLAIN));
        lines.add(new Line(fogged ? "camera: murk side" : "camera: dry side", PLAIN));
        lines.add(FogPlaneRenderer.lastDrawn
                ? new Line(String.format("plane on  %s  fade end %.0f",
                        FogPlaneRenderer.lastBelow ? "below" : "above", FogPlaneRenderer.lastFadeEnd), OK)
                : new Line("plane off (renderPlane)", BAD));
        lines.add(Config.PLANE_SOFT_OCCLUSION.getAsBoolean()
                ? new Line(FogPlaneRenderer.lastDepthValid
                        ? "scene depth: captured" : "scene depth: UNAVAILABLE",
                        FogPlaneRenderer.lastDepthValid ? OK : BAD)
                : new Line("scene depth: off (planeSoftOcclusion)", PLAIN));
        lines.add(new Line("fog event:   " + FogModifier.eventStatus(), PLAIN));
        lines.add(new Line("fog enforce: " + FogModifier.enforceStatus(), PLAIN));
        lines.add(new Line("submerged murk: " + SubmergedMurk.status(),
                SubmergedMurk.status().startsWith("band ") ? OK : PLAIN));
        lines.add(new Line(String.format("depth from fbo %d%s",
                SceneDepth.sourceFramebuffer(),
                SceneDepth.usedFallbackSource() ? " (fallback: main target)" : ""),
                SceneDepth.usedFallbackSource() ? BAD : PLAIN));
        lines.add(new Line(String.format("waterline %d blk @ %d cells/blk  tex %d",
                WaterlineMap.size(), WaterlineMap.cellsPerBlock(), WaterlineMap.textureId()), PLAIN));
        // Quad count against block count: the gap between them is what the greedy merge bought.
        if (FogPlaneRenderer.dhCompat() && FarPlaneGrid.cells() > 0) {
            int total = FarPlaneGrid.cells() * FarPlaneGrid.cells();
            int got = FarPlaneGrid.sampledCells();
            lines.add(new Line(String.format("far haze %d quads  %d/%d cells @ %d blk%s",
                    FogPlaneMesh.farQuadCount(), got, total, FarPlaneGrid.cellSize(),
                    FarPlaneGrid.stalled() ? "  ! DH terrain query hung" : ""),
                    FarPlaneGrid.stalled() || got == 0 ? BAD : PLAIN));
        }
        lines.add(new Line(String.format("mesh %d quads  rev %d (of %d blocks)",
                FogPlaneMesh.quadCount(), FogPlaneMesh.builtRevision(),
                WaterlineMap.size() * WaterlineMap.size()), PLAIN));
        lines.add(new Line("vapor " + (Config.RENDER_VAPOR.getAsBoolean()
                ? Config.VAPOR_SHEETS.getAsInt() + " sheets" : "off"), PLAIN));
        if (FogPlaneRenderer.dhCompat()) {
            for (String line : DistantHorizonsCompatibility.diagnose()) {
                lines.add(new Line(line, line.contains("!") ? BAD : PLAIN));
            }
        }
        lines.add(new Line(String.format("far plane %.0f blk (clips the murk past it)",
                mc.gameRenderer.getDepthFar()), PLAIN));
        lines.add(mixinLine());

        int y = MARGIN;
        GuiGraphics g = event.getGuiGraphics();
        for (Line line : lines) {
            g.drawString(mc.font, line.text, MARGIN, y, line.color);
            y += LINE_HEIGHT;
        }
    }

    // Which of the sky/weather/cloud suppression injectors have actually run this session. A hook that
    // never fires means another mod restructured LevelRenderer past it (see LevelRendererMixin), which
    // shows up in game as sky or rain bleeding through the murk.
    private static Line mixinLine() {
        String text = "hooks:"
                + flag(" sky", MixinHealthCheck.skyFired)
                + flag(" weather", MixinHealthCheck.weatherFired)
                + flag(" rain", MixinHealthCheck.rainTickFired)
                + flag(" clouds", MixinHealthCheck.cloudsFired)
                + flag(" terrainfog", MixinHealthCheck.terrainFogFired)
                + flag(" blockchange", MixinHealthCheck.blockChangeFired);
        boolean all = MixinHealthCheck.skyFired && MixinHealthCheck.weatherFired
                && MixinHealthCheck.rainTickFired && MixinHealthCheck.cloudsFired
                && MixinHealthCheck.terrainFogFired && MixinHealthCheck.blockChangeFired;
        return new Line(text, all ? OK : BAD);
    }

    private static String flag(String name, boolean fired) {
        return name + (fired ? "+" : "-");
    }

    private record Line(String text, int color) {
    }
}
