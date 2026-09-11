package com.fogged.registry;

import com.fogged.Fogged;
import com.fogged.KarstMarkerPiece;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Structure piece types. The only one is the empty piece the karst marker carries so its start counts
 * as valid; see {@link KarstMarkerPiece}.
 */
public final class ModStructurePieces {
    public static final DeferredRegister<StructurePieceType> STRUCTURE_PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, Fogged.MODID);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> KARST_MARKER =
            STRUCTURE_PIECES.register("karst_marker",
                    () -> (StructurePieceType.ContextlessType) KarstMarkerPiece::new);

    public static void register(IEventBus modEventBus) {
        STRUCTURE_PIECES.register(modEventBus);
    }

    private ModStructurePieces() {}
}
