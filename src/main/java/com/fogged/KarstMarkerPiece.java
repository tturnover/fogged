package com.fogged;

import com.fogged.registry.ModStructurePieces;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * The one piece of {@link KarstMarkerStructure}, and it builds nothing.
 *
 * <p>It exists because a structure start with no pieces is discarded as invalid before {@code /locate}
 * ever sees it, so a marker needs at least one piece to be findable. The towers themselves are raised
 * by {@link KarstPillarsFeature} during ordinary feature generation, which is why there is nothing for
 * this to do -- {@link #postProcess} is deliberately empty, and must stay that way, or the towers would
 * be built twice.
 */
public class KarstMarkerPiece extends StructurePiece {

    public KarstMarkerPiece(BlockPos pos) {
        // A one-block box on the tower's own column: enough to be a valid piece, small enough that it
        // never claims ground and never adapts the terrain around it.
        super(ModStructurePieces.KARST_MARKER.get(), 0, new BoundingBox(pos));
    }

    public KarstMarkerPiece(CompoundTag tag) {
        super(ModStructurePieces.KARST_MARKER.get(), tag);
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        // Nothing of its own to save: the bounding box the base class writes is the whole of it.
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator,
            RandomSource random, BoundingBox box, ChunkPos chunkPos, BlockPos pos) {
        // Intentionally empty -- see the class note.
    }
}
