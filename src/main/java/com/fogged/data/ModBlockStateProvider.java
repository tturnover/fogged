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

    /** Number of randomised textures for fog moss / the detector extension (textures suffixed _0.._n). */
    private static final int MOSS_VARIANTS = 4;
    private static final int EXTENSION_VARIANTS = 2;

    /** Blocks handled explicitly below; the auto cube_all pass skips these. */
    private static final List<DeferredBlock<?>> CUSTOM = List.of(
            ModBlocks.FOG_DETECTOR,
            ModBlocks.FOG_DETECTOR_EXTENSION,
            ModBlocks.FOG_MOSS);

    @Override
    protected void registerStatesAndModels() {
        // Detector: single supplied model, rotated by orientation.
        directional(ModBlocks.FOG_DETECTOR, "fog_detector");
        itemModels().withExistingParent("fog_detector", modLoc("block/fog_detector"));

        // Detector extension: randomly pick one of the supplied texture variants per block, still
        // rotated by orientation. No item model (the extension has no BlockItem).
        directionalVariants(ModBlocks.FOG_DETECTOR_EXTENSION, "fog_detector_extension", EXTENSION_VARIANTS);

        // Fog moss: randomly pick one of the cube_all texture variants per block.
        ConfiguredModel[] moss = new ConfiguredModel[MOSS_VARIANTS];
        for (int i = 0; i < MOSS_VARIANTS; i++) {
            String variant = "fog_moss_" + i;
            moss[i] = new ConfiguredModel(models().cubeAll(variant, modLoc("block/" + variant)));
        }
        getVariantBuilder(ModBlocks.FOG_MOSS.get()).partialState().setModels(moss);
        itemModels().withExistingParent("fog_moss", modLoc("block/fog_moss_0"));

        // Everything else: auto cube_all, blockstate + block model + item model.
        ModBlocks.BLOCKS.getEntries().forEach(holder -> {
            Block block = holder.get();
            if (CUSTOM.stream().anyMatch(c -> c.get() == block)) {
                return; // handled above
            }
            simpleBlockWithItem(block, cubeAll(block));
        });
    }

    // Rotate the supplied model (BlockBench export at block/<name>): X from VERTICAL_DIRECTION
    // (up=0, down=180), Y from FACING yaw. WATERLOGGED is ignored.
    private void directional(DeferredBlock<?> holder, String name) {
        ModelFile model = models().getExistingFile(modLoc("block/" + name));
        getVariantBuilder(holder.get()).forAllStatesExcept(state -> {
            int x = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION) == Direction.DOWN ? 180 : 0;
            int y = ((int) state.getValue(HorizontalDirectionalBlock.FACING).toYRot()) % 360;
            return ConfiguredModel.builder().modelFile(model).rotationX(x).rotationY(y).build();
        }, BlockStateProperties.WATERLOGGED);
    }

    // As directional(), but offers `count` texture variants per orientation so the game randomly picks
    // one. Only the base model (block/<name>) is authored; each extra variant is a generated child of it
    // that just re-points the texture to block/<name>_<i>, so the geometry lives in a single file.
    private void directionalVariants(DeferredBlock<?> holder, String name, int count) {
        ModelFile[] models = new ModelFile[count];
        models[0] = this.models().getExistingFile(modLoc("block/" + name));
        for (int i = 1; i < count; i++) {
            String variant = name + "_" + i;
            // Only re-point the visible texture; particle is inherited from the parent model.
            models[i] = this.models().withExistingParent(variant, modLoc("block/" + name))
                    .texture("0", modLoc("block/" + variant));
        }
        getVariantBuilder(holder.get()).forAllStatesExcept(state -> {
            int x = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION) == Direction.DOWN ? 180 : 0;
            int y = ((int) state.getValue(HorizontalDirectionalBlock.FACING).toYRot()) % 360;
            ConfiguredModel[] variants = new ConfiguredModel[count];
            for (int i = 0; i < count; i++) {
                variants[i] = new ConfiguredModel(models[i], x, y, false);
            }
            return variants;
        }, BlockStateProperties.WATERLOGGED);
    }
}
