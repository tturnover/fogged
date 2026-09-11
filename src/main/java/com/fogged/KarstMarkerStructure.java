package com.fogged;

import java.util.Optional;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.fogged.registry.ModStructures;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;

/**
 * A structure that generates nothing, standing in for the karst towers so vanilla's {@code /locate}
 * can find them.
 *
 * <p>{@code /locate} searches structures, biomes and points of interest -- never worldgen features --
 * so the towers, being a feature, are invisible to it and to structure tags. Rather than rebuild them
 * as a real structure, this marks where they already are: it reports a generation point only for a
 * chunk that {@link KarstPillarsFeature} will actually raise a tower in or beside, and contributes one
 * piece that places no blocks. The towers keep being built by the feature exactly as before.
 *
 * <p>The two agree because both ask the same question of the same pure function of the world seed --
 * so a marker cannot drift onto empty ground, and cannot be left behind when a group moves after a
 * config change. {@code /fogged locate pillars} answers the same question directly and needs no
 * structure at all; this exists so the vanilla command and structure tags work too.
 */
public class KarstMarkerStructure extends Structure {

    public static final MapCodec<KarstMarkerStructure> CODEC = RecordCodecBuilder.mapCodec(
            instance -> instance.group(
                    settingsCodec(instance),
                    KarstPillarsFeature.Layout.CODEC
                            .optionalFieldOf("kind", KarstPillarsFeature.Layout.ANY)
                            .forGetter(structure -> structure.kind))
                    .apply(instance, KarstMarkerStructure::new));

    // Which kind of group this marker looks for, so a ridge and a field of isles can be located apart
    // from each other rather than only as "a pillar somewhere".
    private final KarstPillarsFeature.Layout kind;

    // Chunks either side of the candidate that are searched for a tower. A tower's cell is chunk-sized
    // but its top leans away from its foot, so the tower nearest a chunk may be rooted in a neighbour.
    private static final int SEARCH_CHUNKS = 1;

    public KarstMarkerStructure(StructureSettings settings, KarstPillarsFeature.Layout kind) {
        super(settings);
        this.kind = kind;
    }

    @Override
    protected Optional<GenerationStub> findGenerationPoint(GenerationContext context) {
        if (!Config.GENERATE_PILLARS.get()) {
            return Optional.empty();
        }
        int centreX = context.chunkPos().getMiddleBlockX();
        int centreZ = context.chunkPos().getMiddleBlockZ();
        // Only as far as the search box: a hit further out belongs to a chunk nearer to it, and letting
        // this one claim it too would scatter markers over ground with no tower on it.
        int range = (SEARCH_CHUNKS + 1) * 16;
        BlockPos tower = KarstPillarsFeature.findNearest(context.seed(), centreX, centreZ, range,
                Config.maxBreathHeight(), context.heightAccessor().getMinBuildHeight(),
                context.heightAccessor().getMaxBuildHeight(), kind);
        if (tower == null) {
            return Optional.empty();
        }
        return Optional.of(new GenerationStub(tower, builder -> builder.addPiece(new KarstMarkerPiece(tower))));
    }

    @Override
    public StructureType<?> type() {
        return ModStructures.KARST_MARKER.get();
    }
}
