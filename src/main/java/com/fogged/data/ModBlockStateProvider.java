package com.fogged.data;

import java.util.List;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlock;
import com.fogged.registry.ModBlocks;

import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.common.data.ExistingFileHelper;

/**
 * Emits {@code assets/fogged/blockstates/<name>.json} plus the block model
 * {@code assets/fogged/models/block/<name>.json} and the matching item model for every block in
 * {@link ModBlocks#BLOCKS}. Defaults to a {@code cube_all} model textured at
 * {@code assets/fogged/textures/block/<name>.png}.
 */
public class ModBlockStateProvider extends BlockStateProvider {
    public ModBlockStateProvider(PackOutput output, ExistingFileHelper exFileHelper) {
        super(output, Fogged.MODID, exFileHelper);
    }

    /** Custom-modelled, directional blocks: each references its own externally-supplied model. */
    private static final List<DeferredBlock<?>> CUSTOM_DIRECTIONAL = List.of(
            ModBlocks.FOG_DETECTOR,
            ModBlocks.FOG_DETECTOR_EXTENSION);

    @Override
    protected void registerStatesAndModels() {
        // Reference the supplied model (BlockBench export at block/<name>) and rotate it:
        // X from VERTICAL_DIRECTION (up=0, down=180), Y from FACING yaw. WATERLOGGED is ignored.
        // Item model parents the block model.
        CUSTOM_DIRECTIONAL.forEach(holder -> {
            String name = holder.getId().getPath();
            ModelFile model = models().getExistingFile(modLoc("block/" + name));
            getVariantBuilder(holder.get()).forAllStatesExcept(state -> {
                int x = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION) == Direction.DOWN ? 180 : 0;
                int y = ((int) state.getValue(HorizontalDirectionalBlock.FACING).toYRot()) % 360;
                return ConfiguredModel.builder().modelFile(model).rotationX(x).rotationY(y).build();
            }, BlockStateProperties.WATERLOGGED);
            // Item model only for blocks that have a BlockItem (the extension has none).
            if (holder != ModBlocks.FOG_DETECTOR_EXTENSION) {
                itemModels().withExistingParent(name, modLoc("block/" + name));
            }
        });

        // Everything else: auto cube_all, blockstate + block model + item model.
        ModBlocks.BLOCKS.getEntries().forEach(holder -> {
            Block block = holder.get();
            if (CUSTOM_DIRECTIONAL.stream().anyMatch(c -> c.get() == block)) {
                return; // handled above
            }
            simpleBlockWithItem(block, cubeAll(block));
        });
    }
}
