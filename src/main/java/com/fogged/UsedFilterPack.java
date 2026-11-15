package com.fogged;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.AddPackFindersEvent;

/**
 * The blockstates of the used filters made at startup ({@link UsedFilters}), which datagen cannot know
 * of: a built-in, always-on resource pack made in memory, naming the generic used filter's empty model
 * for each, which UsedFilterModels swaps for the block's own as models bake. Hidden from the pack list.
 */
public final class UsedFilterPack implements PackResources {

    private static final String ID = "fogged_used_filters";
    // One variant per pollution step; the model is swapped for the block's own, shaded, as models bake
    // (UsedFilterModels), so what is named here only has to exist.
    private static final byte[] BLOCKSTATE = blockstate();

    private static byte[] blockstate() {
        StringBuilder json = new StringBuilder("{\"variants\":{");
        for (int i = 0; i <= com.fogged.registry.ModDataComponents.MAX_POLLUTION; i++) {
            json.append(i == 0 ? "" : ",").append("\"pollution=").append(i)
                    .append("\":{\"model\":\"fogged:block/used_filter\"}");
        }
        return json.append("}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private final PackLocationInfo location;

    private UsedFilterPack(PackLocationInfo location) {
        this.location = location;
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(UsedFilterPack::onAddPackFinders);
    }

    private static void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() != PackType.CLIENT_RESOURCES) {
            return;
        }
        PackLocationInfo info = new PackLocationInfo(ID, Component.literal("Fogged used filters"),
                PackSource.BUILT_IN, Optional.empty());
        Pack.ResourcesSupplier resources = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new UsedFilterPack(location);
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return new UsedFilterPack(location);
            }
        };
        event.addRepositorySource(add -> {
            Pack pack = Pack.readMetaAndCreate(info, resources, PackType.CLIENT_RESOURCES,
                    new PackSelectionConfig(true, Pack.Position.TOP, false));
            if (pack != null) {
                add.accept(pack);
            }
        });
    }

    // blockstates/used/<namespace>/<path>.json for each used filter
    private static ResourceLocation blockstate(ResourceLocation original) {
        return ResourceLocation.fromNamespaceAndPath(Fogged.MODID,
                "blockstates/used/" + original.getNamespace() + "/" + original.getPath() + ".json");
    }

    private static IoSupplier<InputStream> json() {
        return () -> new ByteArrayInputStream(BLOCKSTATE);
    }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, ResourceLocation location) {
        if (type == PackType.CLIENT_RESOURCES) {
            for (ResourceLocation original : UsedFilters.byOriginal().keySet()) {
                if (blockstate(original).equals(location)) {
                    return json();
                }
            }
        }
        return null;
    }

    @Override
    public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
        if (type != PackType.CLIENT_RESOURCES || !namespace.equals(Fogged.MODID)) {
            return;
        }
        for (ResourceLocation original : UsedFilters.byOriginal().keySet()) {
            ResourceLocation file = blockstate(original);
            if (file.getPath().startsWith(path + "/")) {
                output.accept(file, json());
            }
        }
    }

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? Set.of(Fogged.MODID) : Set.of();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getMetadataSection(MetadataSectionSerializer<T> deserializer) {
        if (deserializer == PackMetadataSection.TYPE) {
            return (T) new PackMetadataSection(Component.literal("Fogged used filters"),
                    SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES), Optional.empty());
        }
        return null;
    }

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return location;
    }

    @Override
    public boolean isHidden() {
        return true;
    }

    @Override
    public void close() {
    }
}
