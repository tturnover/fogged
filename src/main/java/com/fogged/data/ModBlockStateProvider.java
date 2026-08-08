package com.fogged.data;

import java.util.List;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlock;
import com.fogged.block.NozzleFilterBlock;
import com.fogged.registry.ModBlocks;

import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.item.DyeColor;
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

    /** Blocks handled explicitly below; the auto cube_all pass skips these. */
    private static final List<DeferredBlock<?>> CUSTOM = List.of(
            ModBlocks.FOG_DETECTOR,
            ModBlocks.FOG_DETECTOR_EXTENSION,
            ModBlocks.NOZZLE_FILTER);

    @Override
    protected void registerStatesAndModels() {
        // Detector + extension: model rotated by orientation, swapped off/_on by LIT. Item = the unlit model.
        fogDetector();
        itemModels().withExistingParent("fog_detector", modLoc("block/fog_detector"));
        directionalLit(ModBlocks.FOG_DETECTOR_EXTENSION, "fog_detector_extension");

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

    // One model per dye colour, each a child of our fogged:block/nozzle_filter (which itself parents
    // Create's nozzle model) that swaps the mesh texture var #3 to that wool. Rotated by FACING with the
    // same X/Y rotations Create's nozzle blockstate uses (authored facing UP). The COLOR state picks the
    // colour, so a filter made from red wool renders with red wool in Create's mesh and so on.
    private void nozzleFilter() {
        var builder = getVariantBuilder(ModBlocks.NOZZLE_FILTER.get());
        for (DyeColor color : DyeColor.values()) {
            ModelFile model = models()
                    .withExistingParent("nozzle_filter_" + color.getSerializedName(), modLoc("block/nozzle_filter"))
                    .texture("3", mcLoc("block/" + color.getSerializedName() + "_wool"));
            for (Direction facing : Direction.values()) {
                int[] rot = filterRotation(facing);
                builder.partialState()
                        .with(NozzleFilterBlock.FACING, facing)
                        .with(NozzleFilterBlock.COLOR, color)
                        .setModels(new ConfiguredModel(model, rot[0], rot[1], false));
            }
        }
    }

    // {x, y} rotation that points the UP-authored nozzle filter model along `facing`, matching Create's nozzle.
    private static int[] filterRotation(Direction facing) {
        return switch (facing) {
            case DOWN -> new int[] {180, 0};
            case NORTH -> new int[] {90, 0};
            case SOUTH -> new int[] {90, 180};
            case EAST -> new int[] {90, 90};
            case WEST -> new int[] {90, 270};
            default -> new int[] {0, 0}; // UP
        };
    }

    // Rotated by orientation (X from VERTICAL_DIRECTION, Y from FACING); off/_on by LIT. Base name = unlit.
    private void fogDetector() {
        ModelFile off = models().getExistingFile(modLoc("block/fog_detector"));
        ModelFile on = models().getExistingFile(modLoc("block/fog_detector_on"));
        getVariantBuilder(ModBlocks.FOG_DETECTOR.get()).forAllStatesExcept(state -> {
            int x = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION) == Direction.DOWN ? 180 : 0;
            int y = ((int) state.getValue(HorizontalDirectionalBlock.FACING).toYRot()) % 360;
            ModelFile model = state.getValue(FogDetectorBlock.LIT) ? on : off;
            return ConfiguredModel.builder().modelFile(model).rotationX(x).rotationY(y).build();
        }, BlockStateProperties.WATERLOGGED);
    }

    // As fogDetector, for any block/<name> (off) + block/<name>_on (lit) pair rotated by orientation.
    private void directionalLit(DeferredBlock<?> holder, String name) {
        ModelFile off = models().getExistingFile(modLoc("block/" + name));
        ModelFile on = models().getExistingFile(modLoc("block/" + name + "_on"));
        getVariantBuilder(holder.get()).forAllStatesExcept(state -> {
            int x = state.getValue(FogDetectorBlock.VERTICAL_DIRECTION) == Direction.DOWN ? 180 : 0;
            int y = ((int) state.getValue(HorizontalDirectionalBlock.FACING).toYRot()) % 360;
            ModelFile model = state.getValue(FogDetectorBlock.LIT) ? on : off;
            return ConfiguredModel.builder().modelFile(model).rotationX(x).rotationY(y).build();
        }, BlockStateProperties.WATERLOGGED);
    }
}
