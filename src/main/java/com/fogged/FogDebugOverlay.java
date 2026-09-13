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
        lines.add(new Line("Fogged  view=" + Config.DEBUG_VIEW.get(), PLAIN));
        lines.add(new Line(String.format("boundary %.2f  surface %.2f  camera %.2f",
                boundary, FogPlaneRenderer.lastSurfaceY, cam.y), PLAIN));
        lines.add(new Line(fogged ? "camera: murk side" : "camera: dry side", PLAIN));
        lines.add(FogPlaneRenderer.lastDrawn
                ? new Line(String.format("plane on  %s  fade end %.0f",
                        FogPlaneRenderer.lastBelow ? "below" : "above", FogPlaneRenderer.lastFadeEnd), OK)
                : new Line("plane off (renderPlane)", BAD));
        if (FogPlaneRenderer.lastThroughPack) {
            lines.add(new Line("drawn through the Iris shader pack", PLAIN));
        }
        lines.add(Config.PLANE_SOFT_OCCLUSION.getAsBoolean()
                ? new Line(FogPlaneRenderer.lastDepthValid
                        ? "scene depth: captured" : "scene depth: UNAVAILABLE",
                        FogPlaneRenderer.lastDepthValid ? OK : BAD)
                : new Line("scene depth: off (planeSoftOcclusion)", PLAIN));
        lines.add(new Line("entity holes " + FogPlaneRenderer.lastHoles + "/32  water boxes "
                + FogPlaneRenderer.lastWaterBoxes + " of " + WaterlineMap.flowingColumnCount() + " columns", PLAIN));
        lines.add(new Line(String.format("cpu plane stage %.2f ms = map %.2f + depth copy %.2f + water boxes %.2f + rest",
                FogPlaneRenderer.lastStageMs, FogPlaneRenderer.lastMapMs, FogPlaneRenderer.lastCaptureMs,
                FogPlaneRenderer.lastProxyMs), PLAIN));
        lines.add(new Line(String.format("gpu plane stage %.2f ms  vapor (over) %.2f ms  murk composite %.2f ms (cpu %.2f)",
                FogPlaneRenderer.gpu.lastMs, FogVapor.gpu.lastMs, MurkComposite.gpu.lastMs, MurkComposite.lastMs), PLAIN));
        lines.add(new Line(String.format("map cpu: rescan reseed %.2f + chamfer %.2f ms; tick stamp %.2f + ease %.2f + upload %.2f ms",
                WaterlineMap.lastReseedMs, WaterlineMap.lastChamferMs, WaterlineMap.lastStampMs,
                WaterlineMap.lastEaseMs, WaterlineMap.lastUploadMs), PLAIN));
        lines.add(new Line(String.format("waterline %d blk @ %d cells/blk  tex %d",
                WaterlineMap.size(), WaterlineMap.cellsPerBlock(), WaterlineMap.textureId()), PLAIN));
        lines.add(new Line("foam sites in " + FoamSites.cachedChunks() + " chunks", PLAIN));
        lines.add(new Line("vapor " + (Config.VAPOR_SHEETS.getAsInt() > 0
                ? Config.VAPOR_SHEETS.getAsInt() + " sheets"
                        + (Config.VAPOR_UNDERSIDE.getAsBoolean() ? " both sides" : "")
                        + (Config.VAPOR_UNDERWATER.getAsBoolean() ? " x2 (under water)" : "")
                : "off (vaporSheets 0)"), PLAIN));
        lines.add(fogLine());
        lines.add(mixinLine());

        int y = MARGIN;
        GuiGraphics g = event.getGuiGraphics();
        for (Line line : lines) {
            g.drawString(mc.font, line.text, MARGIN, y, line.color);
            y += LINE_HEIGHT;
        }
    }

    // What the terrain shaders were actually handed at the last terrain draw, against what the config
    // asks for. This is the line that separates "the murk reaches too far because nothing fogged the
    // terrain" from "the murk reaches too far even though the terrain was fogged correctly" -- the
    // first is a hook that lost (see FogModifier, SodiumCompatibility), the second is the screen-space
    // pass. Shape matters as much as distance: a cylinder reaches 1.41x further looking down than a
    // sphere of the same radius (see FogModifier.onRenderFog).
    private static Line fogLine() {
        String path = FogModifier.lastPath;
        if (path == null) {
            return new Line("terrain fog: no terrain hook has run"
                    + (SodiumCompatibility.loaded() ? " (Sodium loaded)" : ""), BAD);
        }
        float want = Config.FOG_DISTANCE.getAsInt();
        boolean fogged = Config.fogged(Minecraft.getInstance().level,
                Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y);
        // Off the murk side the fog is somebody else's and a mismatch means nothing.
        boolean matches = !fogged
                || (Math.abs(FogModifier.lastEnd - want) < 0.01F && FogModifier.lastShape == 0);
        return new Line(String.format("terrain fog (%s): %.1f..%.1f %s  want %.1f..%.1f sphere",
                path, FogModifier.lastStart, FogModifier.lastEnd,
                FogModifier.lastShape == 0 ? "sphere" : FogModifier.lastShape == 1 ? "cylinder" : "?",
                want * 0.25F, want), matches ? OK : BAD);
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
                + (SodiumCompatibility.loaded() ? flag(" sodium", MixinHealthCheck.sodiumFogFired) : "");
        boolean all = MixinHealthCheck.skyFired && MixinHealthCheck.weatherFired
                && MixinHealthCheck.rainTickFired && MixinHealthCheck.cloudsFired
                && !SodiumCompatibility.hookMissing();
        return new Line(text, all ? OK : BAD);
    }

    private static String flag(String name, boolean fired) {
        return name + (fired ? "+" : "-");
    }

    private record Line(String text, int color) {
    }
}
