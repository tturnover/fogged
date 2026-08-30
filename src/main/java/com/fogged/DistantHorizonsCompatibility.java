package com.fogged;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.neoforged.fml.ModList;

/**
 * All integration with Distant Horizons lives here. DH draws LOD terrain far past the vanilla render
 * distance, and this mod sizes the murk surface off {@code getEffectiveRenderDistance()} -- so without
 * this the murk fades out at a few hundred blocks and leaves DH's horizon showing the world below the
 * boundary, with a hard ring where the surface stopped.
 *
 * <p>Read reflectively, with no compile-time dependency and no extra maven repo, the same isolation
 * {@link CreateCompatibility} uses: {@code DhApi.Delayed.configs.graphics()} gives
 * {@code chunkRenderDistance()} (a radius in chunks) and {@code renderingEnabled()}. Every failure
 * path degrades to "DH is not extending anything", which is exactly the behaviour without DH
 * installed, so a DH update that moves the API costs this mod nothing but the extra reach.
 *
 * <p>It also drives DH's own fog while the camera is under the boundary. DH's {@code enableVanillaFog}
 * is what strips the murk: switched off, DH suppresses vanilla MC's fog, and the murk fog goes with it,
 * leaving the world under the plane on open show to the LOD horizon. Rather than only forcing that back
 * on, the murk is pushed into DH's own far-fog settings too, so LODs fade out at the same distance real
 * chunks do. Everything set here is set through the API and released with {@code clearValue()}, which
 * reverts to the value in the user's config file -- nothing is written to their settings.
 *
 * <p>Only the murk's REACH is extended. The waterline foam map and the foam-site scan stay at vanilla
 * range on purpose: LODs carry no block data, so there is nothing out there to find a waterline in.
 * The murk SURFACE does reach that far, as the simplified skirt in FogPlaneMesh.
 */
final class DistantHorizonsCompatibility {

    private static final boolean INSTALLED = ModList.get().isLoaded("distanthorizons");

    // DH's config object is null until it has initialised, and the render distance is a user setting
    // that can change at any time, so this is polled rather than resolved once -- but not per frame.
    private static final long POLL_INTERVAL_NANOS = 1_000_000_000L;

    private static Field configsField;
    private static Method graphics;
    private static Method chunkRenderDistance;
    private static Method renderingEnabled;
    private static Method getValue;
    // Separate flags on purpose. These are two independent pieces of the API, and one moving must not
    // take the other down with it -- a mis-named enum constant in the fog lookup previously disabled
    // the render-distance query too, so the murk neither reached the LODs nor fogged them.
    private static boolean coreFailed;
    private static boolean fogFailed;
    private static String status = "not resolved";

    // NOT seeded to Long.MIN_VALUE: nanoTime() minus that overflows to a negative, so the interval
    // check below passed on the very first call, handed back the uninitialised 0, and never updated the
    // timestamp -- wedging the whole integration at "DH is not rendering" for the rest of the session.
    private static boolean polled;
    private static long lastPollNanos;
    private static int cachedBlocks;

    // Fog overrides are applied on transition, not per frame: every one is a reflective call into DH's
    // config, and the murk either is or is not in force.
    private static Method fog;
    private static Method farFog;
    private static Method enableVanillaFog;
    private static Method enableDhFog;
    private static Method fogColorMode;
    private static Method farFogStart;
    private static Method farFogEnd;
    private static Method setValue;
    private static Method clearValue;
    private static Method canOverride;
    private static Object worldFogColorMode;
    private static Boolean murkApplied;
    private static float appliedEndFraction = -1.0F;

    private DistantHorizonsCompatibility() {
    }

    /** Whether Distant Horizons is installed at all. */
    static boolean installed() {
        return INSTALLED;
    }

    /**
     * DH's LOD render radius in blocks, or 0 when DH is absent, has not initialised yet, has its
     * rendering switched off, or has changed its API out from under us.
     */
    static int renderDistanceBlocks() {
        if (!INSTALLED || coreFailed) {
            return 0;
        }
        long now = System.nanoTime();
        if (polled && now - lastPollNanos < POLL_INTERVAL_NANOS) {
            return cachedBlocks;
        }
        polled = true;
        lastPollNanos = now;
        cachedBlocks = query();
        return cachedBlocks;
    }

    private static int query() {
        try {
            if (!resolveCore()) {
                return 0;
            }
            Object configs = configsField.get(null);
            if (configs == null) {
                return 0; // DH has not finished starting up
            }
            Object graphicsConfig = graphics.invoke(configs);
            if (!(getValue.invoke(renderingEnabled.invoke(graphicsConfig)) instanceof Boolean on) || !on) {
                return 0; // DH is installed but not drawing, so it is not extending the horizon
            }
            if (getValue.invoke(chunkRenderDistance.invoke(graphicsConfig)) instanceof Integer chunks) {
                return Math.max(0, chunks) * 16;
            }
            return 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            coreFailed = true;
            status = "render distance unavailable";
            if (Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
                Fogged.LOGGER.warn("Fogged: Distant Horizons is installed but its API did not answer as "
                        + "expected; the murk will only reach the vanilla render distance", e);
            }
            return 0;
        }
    }

    /**
     * Point DH's fog at the murk, or let it go again. Called every frame with the camera's current
     * side; only acts when that changes, or when the murk's reach moves far enough to matter.
     *
     * @param fogged   whether the camera is on the murk side of the boundary
     * @param endBlocks how far the murk fog reaches there
     */
    static void applyMurkFog(boolean fogged, float endBlocks) {
        if (!INSTALLED || fogFailed) {
            return;
        }
        int reach = renderDistanceBlocks();
        if (reach <= 0) {
            murkApplied = null; // DH is not drawing; nothing of ours is in force either
            return;
        }
        // DH's far fog distances are fractions of its own render radius, not blocks.
        float fraction = fogged ? Math.min(1.0F, endBlocks / reach) : 0.0F;
        boolean unchanged = murkApplied != null && murkApplied == fogged
                && (!fogged || Math.abs(fraction - appliedEndFraction) < 0.01F);
        if (unchanged) {
            return;
        }
        try {
            if (!resolveCore() || !resolveFog()) {
                return;
            }
            Object configs = configsField.get(null);
            if (configs == null) {
                return;
            }
            Object fogConfig = fog.invoke(graphics.invoke(configs));
            Object farFogConfig = farFog.invoke(fogConfig);
            boolean ok;
            if (fogged) {
                // Vanilla fog back on, so the murk reaches real chunks again, and DH's own fog set to
                // match it so the LODs beyond them fade out at the same distance rather than hanging
                // there in clear air. The colour mode takes the world's fog colour, which FogModifier
                // has already set to the murk's.
                ok = set(enableVanillaFog.invoke(fogConfig), Boolean.TRUE);
                ok &= set(enableDhFog.invoke(fogConfig), Boolean.TRUE);
                ok &= set(farFogStart.invoke(farFogConfig), 0.0F);
                ok &= set(farFogEnd.invoke(farFogConfig), fraction);
                if (worldFogColorMode != null) {
                    ok &= set(fogColorMode.invoke(fogConfig), worldFogColorMode);
                }
            } else {
                // Hand every one of them back to whatever the user actually configured.
                ok = clear(enableVanillaFog.invoke(fogConfig));
                ok &= clear(enableDhFog.invoke(fogConfig));
                ok &= clear(farFogStart.invoke(farFogConfig));
                ok &= clear(farFogEnd.invoke(farFogConfig));
                if (worldFogColorMode != null) {
                    ok &= clear(fogColorMode.invoke(fogConfig));
                }
            }
            murkApplied = fogged;
            appliedEndFraction = fraction;
            // Every one of these returns whether DH accepted it; a config marked as not overridable by
            // the API refuses silently otherwise, which is indistinguishable from the integration not
            // running at all.
            status = ok ? (fogged ? "driving" : "released") : "REFUSED by DH";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fogFailed = true;
            status = "fog api unavailable";
            if (Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
                Fogged.LOGGER.warn("Fogged: could not drive Distant Horizons' fog; the world under the "
                        + "plane may stay clear out to the LOD horizon", e);
            }
        }
    }

    // --- LOD terrain heights ---------------------------------------------------------------------
    // Used to cut the distant murk against LOD terrain. The depth buffer cannot do it: DH draws LODs
    // with its own extended projection, so their depth values do not mean what vanilla's inverse
    // thinks they mean, and both the depth test and the shader's own occlusion read them wrong. Asking
    // DH for the actual terrain height is the only reading that agrees with what is on screen.

    private static Method worldProxyLevels;
    private static Method singlePlayerLevel;
    private static Method columnDataAt;
    private static Method createSoftCache;
    private static Field resultPayload;
    private static Field pointTopY;
    private static Field pointBlockState;
    private static Method blockIsAir;
    private static Field worldProxyField;
    private static Field terrainRepoField;
    private static Object terrainCache;
    private static boolean terrainFailed;

    /**
     * Highest solid LOD block at this column, or {@link Integer#MIN_VALUE} when DH has no data there
     * (ungenerated, out of range, or the API is not answering).
     */
    static int lodTopY(int blockX, int blockZ) {
        if (!INSTALLED || terrainFailed) {
            return Integer.MIN_VALUE;
        }
        try {
            if (!resolveTerrain()) {
                return Integer.MIN_VALUE;
            }
            Object levelWrapper = levelWrapper();
            if (levelWrapper == null) {
                return Integer.MIN_VALUE;
            }
            Object result = columnDataAt.invoke(terrainRepoField.get(null), levelWrapper, blockX, blockZ,
                    terrainCache);
            Object payload = resultPayload.get(result);
            if (!(payload instanceof Object[] points)) {
                return Integer.MIN_VALUE;
            }
            int top = Integer.MIN_VALUE;
            for (Object point : points) {
                if (point == null || Boolean.TRUE.equals(blockIsAir.invoke(pointBlockState.get(point)))) {
                    continue;
                }
                top = Math.max(top, pointTopY.getInt(point));
            }
            return top;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            terrainFailed = true;
            if (Config.LOG_RENDER_COMPAT_WARNINGS.getAsBoolean()) {
                Fogged.LOGGER.warn("Fogged: Distant Horizons terrain data unavailable; the distant murk "
                        + "will not be cut against LOD terrain", e);
            }
            return Integer.MIN_VALUE;
        }
    }

    private static Object levelWrapper() throws ReflectiveOperationException {
        Object proxy = worldProxyField.get(null);
        if (proxy == null) {
            return null;
        }
        try {
            return singlePlayerLevel.invoke(proxy);
        } catch (java.lang.reflect.InvocationTargetException e) {
            // Not singleplayer -- getSinglePlayerLevel throws rather than returning null. Take the
            // first loaded level instead; on a server there is only the one the client is in.
            Object levels = worldProxyLevels.invoke(proxy);
            if (levels instanceof Iterable<?> it) {
                for (Object level : it) {
                    return level;
                }
            }
            return null;
        }
    }

    private static boolean resolveTerrain() throws ReflectiveOperationException {
        if (columnDataAt != null) {
            return true;
        }
        Class<?> dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi");
        Class<?> delayed = null;
        for (Class<?> nested : dhApi.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("Delayed")) {
                delayed = nested;
            }
        }
        if (delayed == null) {
            return false;
        }
        worldProxyField = delayed.getField("worldProxy");
        terrainRepoField = delayed.getField("terrainRepo");
        if (terrainRepoField.get(null) == null) {
            return false; // DH still starting up
        }
        singlePlayerLevel = worldProxyField.getType().getMethod("getSinglePlayerLevel");
        worldProxyLevels = worldProxyField.getType().getMethod("getAllLoadedLevelWrappers");

        Class<?> repo = terrainRepoField.getType();
        Class<?> levelType = Class.forName(
                "com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper");
        Class<?> cacheType = Class.forName(
                "com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataCache");
        columnDataAt = repo.getMethod("getColumnDataAtBlockPos", levelType, int.class, int.class, cacheType);
        createSoftCache = repo.getMethod("createSoftCache");
        terrainCache = createSoftCache.invoke(terrainRepoField.get(null));

        Class<?> point = Class.forName(
                "com.seibel.distanthorizons.api.objects.data.DhApiTerrainDataPoint");
        pointTopY = point.getField("topYBlockPos");
        pointBlockState = point.getField("blockStateWrapper");
        blockIsAir = Class.forName(
                "com.seibel.distanthorizons.api.interfaces.block.IDhApiBlockStateWrapper")
                .getMethod("isAir");
        resultPayload = Class.forName("com.seibel.distanthorizons.api.objects.DhApiResult")
                .getField("payload");
        return true;
    }

    /** What the fog integration is doing, for the debug HUD. */
    static String fogStatus() {
        return status;
    }

    /**
     * Every step of the integration with its actual value, for the debug HUD. Each line is read
     * defensively and a failed read is reported rather than thrown, because the whole point of this is
     * to be readable when something in the chain has moved -- which is exactly when it would throw.
     * A line containing '!' is a problem.
     */
    static java.util.List<String> diagnose() {
        java.util.List<String> out = new java.util.ArrayList<>();
        out.add("DH: fog " + status + ", reach " + renderDistanceBlocks() + " blk");
        try {
            if (!resolveCore()) {
                out.add("DH: ! DhApi.Delayed not found");
                return out;
            }
            Object configs = configsField.get(null);
            if (configs == null) {
                out.add("DH: ! configs null (not initialised)");
                return out;
            }
            Object g = graphics.invoke(configs);
            out.add("DH: rendering=" + getValue.invoke(renderingEnabled.invoke(g))
                    + " chunks=" + getValue.invoke(chunkRenderDistance.invoke(g)));
            if (!resolveFog()) {
                out.add("DH: ! fog api not found");
                return out;
            }
            Object f = fog.invoke(g);
            Object ff = farFog.invoke(f);
            out.add("DH: vanillaFog=" + describe(enableVanillaFog.invoke(f))
                    + " dhFog=" + describe(enableDhFog.invoke(f)));
            out.add("DH: farFog " + describe(farFogStart.invoke(ff)) + ".." + describe(farFogEnd.invoke(ff))
                    + " colourMode=" + (worldFogColorMode == null ? "!none" : worldFogColorMode));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            out.add("DH: ! " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        return out;
    }

    // "value" or "value!locked" -- a config DH will not let the API write is the failure that otherwise
    // looks identical to this integration never having run.
    private static String describe(Object configValue) throws ReflectiveOperationException {
        Object value = getValue.invoke(configValue);
        Object overridable = canOverride == null ? Boolean.TRUE : canOverride.invoke(configValue);
        return Boolean.FALSE.equals(overridable) ? value + "!locked" : String.valueOf(value);
    }

    private static boolean set(Object configValue, Object newValue) throws ReflectiveOperationException {
        return !(setValue.invoke(configValue, newValue) instanceof Boolean accepted) || accepted;
    }

    private static boolean clear(Object configValue) throws ReflectiveOperationException {
        return !(clearValue.invoke(configValue) instanceof Boolean accepted) || accepted;
    }

    private static boolean resolveCore() throws ReflectiveOperationException {
        if (getValue != null) {
            return true;
        }
        Class<?> dhApi = Class.forName("com.seibel.distanthorizons.api.DhApi");
        Class<?> delayed = null;
        for (Class<?> nested : dhApi.getDeclaredClasses()) {
            if (nested.getSimpleName().equals("Delayed")) {
                delayed = nested;
                break;
            }
        }
        if (delayed == null) {
            return false;
        }
        configsField = delayed.getField("configs");
        graphics = configsField.getType().getMethod("graphics");
        chunkRenderDistance = graphics.getReturnType().getMethod("chunkRenderDistance");
        renderingEnabled = graphics.getReturnType().getMethod("renderingEnabled");
        // IDhApiConfigValue<T> erases to Object, and both setters report whether DH accepted the write.
        getValue = chunkRenderDistance.getReturnType().getMethod("getValue");
        setValue = chunkRenderDistance.getReturnType().getMethod("setValue", Object.class);
        clearValue = chunkRenderDistance.getReturnType().getMethod("clearValue");
        try {
            canOverride = chunkRenderDistance.getReturnType().getMethod("getCanBeOverrodeByApi");
        } catch (NoSuchMethodException e) {
            canOverride = null; // older API; assume writable and let setValue report otherwise
        }
        return true;
    }

    private static boolean resolveFog() throws ReflectiveOperationException {
        if (farFogEnd != null) {
            return true;
        }
        fog = graphics.getReturnType().getMethod("fog");
        Class<?> fogType = fog.getReturnType();
        enableVanillaFog = fogType.getMethod("enableVanillaFog");
        enableDhFog = fogType.getMethod("enableDhFog");
        fogColorMode = fogType.getMethod("color");
        farFog = fogType.getMethod("farFog");
        farFogStart = farFog.getReturnType().getMethod("farFogStartDistance");
        farFogEnd = farFog.getReturnType().getMethod("farFogEndDistance");
        worldFogColorMode = worldFogColorMode();
        return true;
    }

    // The constant that makes DH take the world's fog colour rather than the sky's. It has been called
    // both of these across DH versions -- USE_WORLD_FOG_COLOR in 3.2, USE_DEFAULT_FOG_COLOR in the 4.x
    // docs -- so it is matched by name against what the enum actually holds. Hard-coding one of them is
    // what broke this integration outright: valueOf threw, and the whole class disabled itself.
    // Returning null is fine; the colour is the least important of the three settings, and the murk
    // still fogs the LODs without it.
    private static Object worldFogColorMode() {
        try {
            Object[] constants = Class.forName(
                    "com.seibel.distanthorizons.api.enums.rendering.EDhApiFogColorMode")
                    .getEnumConstants();
            if (constants == null) {
                return null;
            }
            for (Object constant : constants) {
                String name = ((Enum<?>) constant).name();
                if (name.equals("USE_WORLD_FOG_COLOR") || name.equals("USE_DEFAULT_FOG_COLOR")) {
                    return constant;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // fall through: leave DH's colour mode alone
        }
        return null;
    }
}
