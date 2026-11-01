package com.fogged.rig;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fogged.Fogged;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Loads every rigged model with the resources, so F3+T picks up a fresh export from Blockbench:
 *
 * <pre>
 * assets/fogged/geo/entity/&lt;entity&gt;.geo.json               geometry (required)
 * assets/fogged/animations/entity/&lt;entity&gt;.animation.json  animations
 * assets/fogged/rigs/&lt;entity&gt;.json                         tuning (see {@link RigTuning})
 * assets/fogged/textures/entity/&lt;entity&gt;.png                texture (or the tuning's own)
 * </pre>
 *
 * A broken file is logged and skipped; the rest still load. Each model gets one log line saying what
 * the rig found in it, which is the first place to look when a leg doesn't move.
 *
 * <p><b>Live source, for development.</b> With the system property {@code fogged.rigSource} pointing
 * at a resources folder (the dev client run sets it to {@code src/main/resources}), models are read
 * straight from there instead, and the folder is checked every second: a new export from Blockbench,
 * a tuning edit or a repainted texture shows up in the running game on its own. No build step and no
 * F3+T, whose full resource reload is the slow and fragile part with many mods installed.
 */
public final class RigAssets extends SimplePreparableReloadListener<Map<ResourceLocation, RigModel>> {

    public static final RigAssets INSTANCE = new RigAssets();

    private static final String GEO = "geo/entity/";
    private static final String GEO_SUFFIX = ".geo.json";

    /** Resources folder to read live from, or null outside development. */
    private static final Path SOURCE = System.getProperty("fogged.rigSource") == null ? null
            : Path.of(System.getProperty("fogged.rigSource"));

    private volatile Map<ResourceLocation, RigModel> models = Map.of();
    /** Newest file time behind each live-loaded model, to spot an edit. */
    private final Map<ResourceLocation, Long> stamps = new HashMap<>();
    private int pollTicks;

    private RigAssets() {}

    public static RigModel get(ResourceLocation id) {
        return INSTANCE.models.get(id);
    }

    public static boolean live() {
        return SOURCE != null;
    }

    /** Where a resource reads from: the resource packs, or the live source folder. */
    private interface Source {
        JsonObject json(ResourceLocation location) throws IOException;
    }

    @Override
    protected Map<ResourceLocation, RigModel> prepare(ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, RigModel> out = new HashMap<>();
        if (SOURCE != null) {
            for (ResourceLocation id : liveIds()) {
                tryLoad(id, RigAssets::readLive, out);
            }
            return out;
        }
        Map<ResourceLocation, Resource> geos = manager.listResources("geo/entity",
                loc -> loc.getNamespace().equals(Fogged.MODID) && loc.getPath().endsWith(GEO_SUFFIX));
        Source packs = loc -> {
            var resource = manager.getResource(loc);
            return resource.isPresent() ? read(resource.get()) : null;
        };
        for (ResourceLocation geo : geos.keySet()) {
            String path = geo.getPath();
            tryLoad(ResourceLocation.fromNamespaceAndPath(geo.getNamespace(),
                    path.substring(GEO.length(), path.length() - GEO_SUFFIX.length())), packs, out);
        }
        return out;
    }

    @Override
    protected void apply(Map<ResourceLocation, RigModel> prepared, ResourceManager manager, ProfilerFiller profiler) {
        Map<ResourceLocation, RigModel> loaded = new HashMap<>(prepared);
        if (SOURCE != null) {
            loaded.replaceAll((id, model) -> withLiveTexture(model));
            stamps.clear();
            for (ResourceLocation id : liveIds()) {
                stamps.put(id, stamp(id));
            }
        }
        models = Map.copyOf(loaded);
    }

    /** Live source only: once a second, reloads any model whose files changed. Render thread. */
    public static void onClientTick(ClientTickEvent.Post event) {
        if (SOURCE == null || ++INSTANCE.pollTicks < 20) {
            return;
        }
        INSTANCE.pollTicks = 0;
        Map<ResourceLocation, RigModel> next = null;
        for (ResourceLocation id : liveIds()) {
            long stamp = stamp(id);
            Long last = INSTANCE.stamps.put(id, stamp);
            if (last != null && last == stamp) {
                continue;
            }
            Map<ResourceLocation, RigModel> one = new HashMap<>();
            if (tryLoad(id, RigAssets::readLive, one)) {
                if (next == null) {
                    next = new HashMap<>(INSTANCE.models);
                }
                next.put(id, withLiveTexture(one.get(id)));
                Fogged.LOGGER.info("[rig] {}: reloaded from source", id);
            }
        }
        if (next != null) {
            INSTANCE.models = Map.copyOf(next);
        }
    }

    private static boolean tryLoad(ResourceLocation id, Source source, Map<ResourceLocation, RigModel> out) {
        try {
            out.put(id, load(id, source));
            return true;
        } catch (Exception ex) {
            Fogged.LOGGER.error("[rig] {}: could not load: {}", id, ex.toString());
            return false;
        }
    }

    private static RigModel load(ResourceLocation id, Source source) throws Exception {
        JsonObject geoFile = source.json(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), GEO + id.getPath() + GEO_SUFFIX));
        if (geoFile == null) {
            throw new IOException("no geometry");
        }
        RigGeometry geometry = RigGeometry.parse(geoFile);
        RigLayout layout = RigLayout.of(geometry);

        Map<String, RigAnimation.Bound> animations = new LinkedHashMap<>();
        Set<String> missing = new TreeSet<>();
        JsonObject animFile = source.json(ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                "animations/entity/" + id.getPath() + ".animation.json"));
        if (animFile != null) {
            for (RigAnimation a : RigAnimation.parseFile(animFile).values()) {
                animations.put(a.name, a.bind(geometry, missing));
                if (!a.usedMolang.isEmpty()) {
                    Fogged.LOGGER.warn("[rig] {}: {} uses Molang on {}; those keyframes read as rest", id, a.name, a.usedMolang);
                }
            }
        }

        JsonObject tuningFile = source.json(ResourceLocation.fromNamespaceAndPath(id.getNamespace(), "rigs/" + id.getPath() + ".json"));
        RigTuning tuning = RigTuning.parse(id, tuningFile);

        Fogged.LOGGER.info("[rig] {}: {} bones, legs [{}], tilt [{}], animations [{}]{}", id, geometry.bones.length,
                Arrays.stream(layout.legs).map(RigLayout.Leg::id).collect(Collectors.joining(", ")),
                Arrays.stream(layout.tiltBones).mapToObj(i -> geometry.bones[i].name).collect(Collectors.joining(", ")),
                String.join(", ", animations.keySet()),
                tuningFile != null ? "" : ", default tuning");
        for (String problem : layout.problems) {
            Fogged.LOGGER.warn("[rig] {}: {}", id, problem);
        }
        if (!missing.isEmpty()) {
            Fogged.LOGGER.warn("[rig] {}: animations drive bones the model doesn't have: {}", id, missing);
        }
        for (String name : new String[] {tuning.idle(), tuning.walk()}) {
            if (!animations.containsKey(name)) {
                Fogged.LOGGER.warn("[rig] {}: no animation named {}", id, name);
            }
        }
        return new RigModel(id, geometry, layout, animations, tuning);
    }

    private static JsonObject read(Resource resource) throws IOException {
        try (Reader reader = resource.openAsReader()) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    // --- Live source ------------------------------------------------------------

    private static Path file(ResourceLocation location) {
        return SOURCE.resolve("assets").resolve(location.getNamespace()).resolve(location.getPath());
    }

    private static JsonObject readLive(ResourceLocation location) throws IOException {
        Path path = file(location);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Set<ResourceLocation> liveIds() {
        Set<ResourceLocation> ids = new TreeSet<>();
        try (Stream<Path> files = Files.list(SOURCE.resolve("assets").resolve(Fogged.MODID).resolve(GEO))) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(GEO_SUFFIX))
                    .forEach(name -> ids.add(ResourceLocation.fromNamespaceAndPath(Fogged.MODID,
                            name.substring(0, name.length() - GEO_SUFFIX.length()))));
        } catch (IOException ignored) {
            // no folder yet: nothing to load
        }
        return ids;
    }

    /** Newest modification time among a model's files (the texture is the tuning's default one). */
    private static long stamp(ResourceLocation id) {
        String ns = id.getNamespace(), name = id.getPath();
        long newest = 0;
        for (String path : new String[] {GEO + name + GEO_SUFFIX, "animations/entity/" + name + ".animation.json",
                "rigs/" + name + ".json", "textures/entity/" + name + ".png"}) {
            try {
                newest = Math.max(newest, Files.getLastModifiedTime(file(ResourceLocation.fromNamespaceAndPath(ns, path))).toMillis());
            } catch (IOException ignored) {
                // a missing optional file just doesn't count
            }
        }
        return newest;
    }

    /**
     * Uploads the model's texture straight from the source folder under a dev-only name and points the
     * model at it, so a repainted texture shows without a resource reload. Render thread.
     */
    private static RigModel withLiveTexture(RigModel model) {
        ResourceLocation texture = model.tuning().texture();
        Path path = file(texture);
        if (!Files.isRegularFile(path)) {
            return model;
        }
        try (InputStream in = Files.newInputStream(path)) {
            NativeImage image = NativeImage.read(in);
            ResourceLocation live = ResourceLocation.fromNamespaceAndPath(texture.getNamespace(), "rig_live/" + texture.getPath());
            Minecraft.getInstance().getTextureManager().register(live, new DynamicTexture(image));
            return new RigModel(model.id(), model.geometry(), model.layout(), model.animations(), model.tuning().withTexture(live));
        } catch (IOException ex) {
            Fogged.LOGGER.error("[rig] {}: could not read texture {}: {}", model.id(), path, ex.toString());
            return model;
        }
    }
}
