package com.fogged;

import com.fogged.mixin.LevelRendererMixin;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Best-effort diagnostic, not a guarantee: a short while after joining a world, checks whether
 * {@link LevelRendererMixin}'s sky/weather/cloud-suppression injectors have actually run at all. Every
 * one of them is {@code require = 0} (see fogged.mixins.json), so a renderer that restructures
 * {@code LevelRenderer}'s {@code renderSky}/{@code renderSnowAndRain}/{@code tickRain}/
 * {@code renderClouds} methods degrades silently instead of crashing mod load -- this just surfaces
 * that degradation once, for players/pack maintainers to notice, rather than leaving it invisible.
 *
 * <p>The fired-flags themselves live here rather than on {@link LevelRendererMixin} because Mixin
 * merges a mixin class's members directly into its target class at apply time, and rejects any new
 * non-private static field a mixin tries to add there (a public field would silently become part of
 * vanilla's {@code LevelRenderer}, which Mixin's validator specifically forbids) -- so the injected
 * methods report here, into an ordinary class, instead of declaring the flags on themselves.
 *
 * <p>This CANNOT distinguish "the mixin failed to apply" from "the hook exists but genuinely wasn't
 * reached in the check window" -- e.g. the clouds flag can stay unfired simply because the player's own
 * Clouds option is Off, not because of a renderer conflict. Treat the warning as "worth investigating",
 * never as proof of an actual incompatibility.
 */
@EventBusSubscriber(modid = Fogged.MODID, value = Dist.CLIENT)
public final class MixinHealthCheck {
    private static final int CHECK_AFTER_TICKS = 400; // ~20 seconds

    // Set unconditionally by LevelRendererMixin's injectors the moment each one actually runs,
    // regardless of whether it goes on to cancel anything -- proof the injection applied and the
    // vanilla hook is still being invoked by whatever renderer is active.
    public static volatile boolean skyFired = false;
    public static volatile boolean weatherFired = false;
    public static volatile boolean rainTickFired = false;
    public static volatile boolean cloudsFired = false;
    /** The terrain-pass fog re-assert; without it another mod's fog override wins (see FogModifier). */
    public static volatile boolean terrainFogFired = false;

    private static int ticks = 0;
    private static boolean checked = false;

    private MixinHealthCheck() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (checked || Minecraft.getInstance().level == null) {
            return;
        }
        if (++ticks < CHECK_AFTER_TICKS) {
            return;
        }
        checked = true;
        if (!Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
            return;
        }
        warnIfNotFired("renderSky", skyFired, "sky suppression under the murk");
        warnIfNotFired("renderSnowAndRain", weatherFired, "rain/snow suppression under the murk");
        warnIfNotFired("tickRain", rainTickFired, "rain-splash suppression under the murk");
        warnIfNotFired("renderClouds", cloudsFired,
                "cloud suppression under the murk (also stays unfired if your own Clouds option is Off)");
    }

    private static void warnIfNotFired(String method, boolean fired, String feature) {
        if (fired) {
            return;
        }
        Fogged.LOGGER.warn("Fogged: LevelRenderer.{} never ran in the first ~{} ticks after joining -- {} "
                + "may not work with the currently active renderer. This is a best-effort heuristic (it "
                + "can't fully rule out the hook simply not being reached in this window) but is worth "
                + "investigating if you notice sky, rain or clouds showing through the fog.",
                method, CHECK_AFTER_TICKS, feature);
    }
}
