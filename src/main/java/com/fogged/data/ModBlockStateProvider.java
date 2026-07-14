package com.fogged.data;

import java.util.List;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlock;
import com.fogged.block.FoggyGrassBlock;
import com.fogged.block.NozzleFilterBlock;
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

    /** Foggy grass: 5 authored (stump placeholder) model shapes per age stage, each randomly textured
     *  with one of GRASS_TEXTURES shared textures -> GRASS_MODELS*GRASS_TEXTURES random variants per age. */
    private static final int GRASS_MODELS = 5;
    private static final int GRASS_TEXTURES = 3;

    /** The temperature variants of fog moss; all share the same randomised cube_all texture set. */
    private static final List<DeferredBlock<Block>> MOSS_BLOCKS = List.of(
            ModBlocks.FOG_MOSS,
            ModBlocks.SOFT_FOG_MOSS,
            ModBlocks.HARSH_FOG_MOSS);

    /** Blocks handled explicitly below; the auto cube_all pass skips these. */
    private static final List<DeferredBlock<?>> CUSTOM = List.of(
            ModBlocks.FOG_DETECTOR,
            ModBlocks.FOG_DETECTOR_EXTENSION,
            ModBlocks.FOG_MOSS,
            ModBlocks.SOFT_FOG_MOSS,
            ModBlocks.HARSH_FOG_MOSS,
            ModBlocks.FOGGY_GRASS,
            ModBlocks.NOZZLE_FILTER);

    @Override
    protected void registerStatesAndModels() {
        // Detector: single supplied model, rotated by orientation.
        directional(ModBlocks.FOG_DETECTOR, "fog_detector");
        itemModels().withExistingParent("fog_detector", modLoc("block/fog_detector"));

        // Detector extension: randomly pick one of the supplied texture variants per block, still
        // rotated by orientation. No item model (the extension has no BlockItem).
        directionalVariants(ModBlocks.FOG_DETECTOR_EXTENSION, "fog_detector_extension", EXTENSION_VARIANTS);

        // Fog moss: each temperature variant (plain / soft / harsh) randomly picks one of its own
        // cube_all texture set, named block/<registry-path>_<i>.
        for (DeferredBlock<Block> block : MOSS_BLOCKS) {
            String name = block.getId().getPath();
            ConfiguredModel[] models = new ConfiguredModel[MOSS_VARIANTS];
            for (int i = 0; i < MOSS_VARIANTS; i++) {
                String variant = name + "_" + i;
                models[i] = new ConfiguredModel(models().cubeAll(variant, modLoc("block/" + variant)));
            }
            getVariantBuilder(block.get()).partialState().setModels(models);
            itemModels().withExistingParent(name, modLoc("block/" + name + "_0"));
        }

        // Foggy grass: per AGE stage, randomly pick one of 5 authored stump models, each randomly
        // textured with one of 3 shared textures. The item icon reuses a shared tuft texture.
        foggyGrass();

        // Nozzle filter: single supplied model rotated by FACING (matching Create's own nozzle
        // blockstate orientations). No item model -- it has no BlockItem.
        nozzleFilter();

        // Everything else: auto cube_all, blockstate + block model + item model.
        ModBlocks.BLOCKS.getEntries().forEach(holder -> {
            Block block = holder.get();
            if (CUSTOM.stream().anyMatch(c -> c.get() == block)) {
                return; // handled above
            }
            simpleBlockWithItem(block, cubeAll(block));
        });
    }

    // Foggy grass: each AGE stage offers GRASS_MODELS authored stump models (block/foggy_grass_age<age>_m<m>,
    // hand-editable placeholders), and each of those is emitted GRASS_TEXTURES times as a generated child
    // that re-points texture #0 to one of the shared block/foggy_grass_<t> textures. All the resulting
    // model+texture combos are equal-weight random variants, so the game picks one per block position.
    private void foggyGrass() {
        var builder = getVariantBuilder(ModBlocks.FOGGY_GRASS.get());
        for (int age = 0; age <= FoggyGrassBlock.MAX_AGE; age++) {
            ConfiguredModel[] variants = new ConfiguredModel[GRASS_MODELS * GRASS_TEXTURES];
            int i = 0;
            for (int m = 0; m < GRASS_MODELS; m++) {
                String base = "foggy_grass_age" + age + "_m" + m;
                for (int t = 0; t < GRASS_TEXTURES; t++) {
                    ModelFile textured = models()
                            .withExistingParent(base + "_t" + t, modLoc("block/" + base))
                            .texture("0", modLoc("block/foggy_grass_" + t));
                    variants[i++] = new ConfiguredModel(textured);
                }
            }
            builder.partialState().with(FoggyGrassBlock.AGE, age).setModels(variants);
        }
        itemModels().withExistingParent("foggy_grass", mcLoc("item/generated"))
                .texture("layer0", modLoc("block/foggy_grass_2"));
    }

    // Rotate the supplied block/nozzle_filter model by FACING, using the same X/Y rotations Create's
    // nozzle blockstate uses (the authored model faces UP; every other facing is a rotation of it).
    private void nozzleFilter() {
        ModelFile model = models().getExistingFile(modLoc("block/nozzle_filter"));
        getVariantBuilder(ModBlocks.NOZZLE_FILTER.get()).forAllStates(state -> {
            int x;
            int y;
            switch (state.getValue(NozzleFilterBlock.FACING)) {
                case DOWN -> { x = 180; y = 0; }
                case NORTH -> { x = 90; y = 0; }
                case SOUTH -> { x = 90; y = 180; }
                case EAST -> { x = 90; y = 90; }
                case WEST -> { x = 90; y = 270; }
                default -> { x = 0; y = 0; } // UP
            }
            return ConfiguredModel.builder().modelFile(model).rotationX(x).rotationY(y).build();
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
