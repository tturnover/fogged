package com.fogged.data;

import java.util.List;

import com.fogged.Fogged;
import com.fogged.block.FogDetectorBlock;
import com.fogged.block.FogEyeBlock;
import com.fogged.block.FoggyGrassBlock;
import com.fogged.block.FoggyGrassSideBlock;
import com.fogged.block.NozzleFilterBlock;
import com.fogged.registry.ModBlocks;

import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.client.model.generators.BlockStateProvider;
import net.neoforged.neoforge.client.model.generators.ConfiguredModel;
import net.neoforged.neoforge.client.model.generators.ModelFile;
import net.neoforged.neoforge.client.model.generators.MultiPartBlockStateBuilder;
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

    /** Randomised end-cap models on a fog eye stem's unconnected faces (vanilla ships the same four). */
    private static final int STEM_CAPS = 4;

    /** Quarter turns a fog eye head is randomly spun by, for the upright model and the leaning one. */
    private static final int EYE_SPINS = 4;

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
            ModBlocks.FOGGY_GRASS_SIDE,
            ModBlocks.FOG_EYE_STEM,
            ModBlocks.FOG_EYE,
            ModBlocks.NOZZLE_FILTER,
            ModBlocks.PUFF_BUSH_SAPLING,
            ModBlocks.PUFF_BUSH_LOG);

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
        // The wall-clinging variant reuses those same models, rotated onto the face it grows out of.
        foggyGrassSide();

        // Fog eye + its stem: vanilla's chorus models, retextured. The stem is a six-way multipart
        // like chorus_plant. The eye picks its model from OPEN alone -- open at the fog surface, shut
        // anywhere below it; AGE and REST are growth bookkeeping and REST is left out of the blockstate
        // entirely (64 timer values times the rest would be 640 pointless variants). Both item icons
        // reuse the block models, as vanilla does.
        fogEyeStem();
        fogEye();

        // Nozzle filter: single supplied model rotated by FACING (matching Create's own nozzle
        // blockstate orientations). No item model -- it has no BlockItem.
        nozzleFilter();

        // Puff bush: a pillar log (axis states + item) and a cross-shaped sapling (cutout, item from the
        // same texture). The leaves fall to the auto cube_all pass below like any other full block.
        puffBush();

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

    // Side foggy grass: one authored model per AGE stage and nothing more -- no shape or texture
    // randomising, unlike the upright grass. Each model is authored rooted on the south face growing
    // north, so the other three walls are just a yaw of it. No item model -- the side variant has no
    // BlockItem; the upright grass's item places it (see ModBlocks#FOGGY_GRASS).
    private void foggyGrassSide() {
        var builder = getVariantBuilder(ModBlocks.FOGGY_GRASS_SIDE.get());
        for (int age = 0; age <= FoggyGrassBlock.MAX_AGE; age++) {
            ModelFile model = models().getExistingFile(modLoc("block/foggy_grass_side_age" + age));
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                int y = ((int) facing.toYRot() + 180) % 360;
                builder.partialState()
                        .with(FoggyGrassSideBlock.FACING, facing)
                        .with(FoggyGrassSideBlock.AGE, age)
                        .setModels(new ConfiguredModel(model, 0, y, false));
            }
        }
    }

    // Fog eye: a base and a head, both turned to point away from the stem the eye grew off (FACING),
    // with the head randomised on top of that so a stand of eyes never looks stamped out.
    //
    // The base is the part that meets the stem, so it only ever takes the FACING rotation -- no random
    // spin, or it would tear away from the plant it plugs into. The head takes FACING too, and then as
    // much randomness as the rotation still has room for: a head pointing UP leaves the Y rotation free,
    // so it gets EYE_SPINS spins of both an upright and a leaning model; a head pointing sideways has
    // already spent Y on the direction, so it only picks between upright and leaning. Which pair of
    // models is offered comes from OPEN. AGE is growth bookkeeping and is never drawn.
    private void fogEye() {
        MultiPartBlockStateBuilder builder = getMultipartBuilder(ModBlocks.FOG_EYE.get());
        ModelFile base = models().getExistingFile(modLoc("block/fog_eye_base"));
        for (Direction facing : Direction.values()) {
            int[] rot = upModelRotation(facing);
            builder.part().modelFile(base).rotationX(rot[0]).rotationY(rot[1]).uvLock(true)
                    .addModel().condition(FogEyeBlock.FACING, facing).end();
            fogEyeHeads(builder, facing, true, "fog_eye_head", "fog_eye_head_lean");
            fogEyeHeads(builder, facing, false, "fog_eye_head_closed", "fog_eye_head_closed_lean");
        }
        itemModels().withExistingParent("fog_eye", modLoc("block/fog_eye"));
    }

    // One head part: the upright and leaning models, spun as often as FACING leaves the Y rotation
    // free, as equal-weight random variants. No uvlock -- letting the texture turn with the head is
    // half of what makes the spins read as different heads rather than the same head.
    private void fogEyeHeads(MultiPartBlockStateBuilder builder, Direction facing, boolean open,
            String upright, String leaning) {
        int[] rot = upModelRotation(facing);
        int spins = facing.getAxis().isVertical() ? EYE_SPINS : 1;
        var part = builder.part();
        boolean first = true;
        for (String name : List.of(upright, leaning)) {
            ModelFile model = models().getExistingFile(modLoc("block/" + name));
            for (int i = 0; i < spins; i++) {
                part = first ? part : part.nextModel();
                part = part.modelFile(model).rotationX(rot[0]).rotationY((rot[1] + i * (360 / EYE_SPINS)) % 360);
                first = false;
            }
        }
        part.addModel().condition(FogEyeBlock.FACING, facing).condition(FogEyeBlock.OPEN, open).end();
    }

    // {x, y} rotation that points an UP-authored model along `facing`. Same mapping the nozzle filter
    // uses below, which is the one Create's nozzle blockstate uses.
    private static int[] upModelRotation(Direction facing) {
        return switch (facing) {
            case DOWN -> new int[] {180, 0};
            case NORTH -> new int[] {90, 0};
            case SOUTH -> new int[] {90, 180};
            case EAST -> new int[] {90, 90};
            case WEST -> new int[] {90, 270};
            default -> new int[] {0, 0}; // UP
        };
    }

    // Fog eye stem: vanilla's chorus_plant blockstate, block for block. Every face that connects gets
    // an arm model rotated onto it; every face that does not gets one of four randomised end-cap models
    // (the plain one at double weight, as vanilla weights it), which is what gives the plant its knobbly
    // silhouette. The item icon is the authored whole-block model, again as vanilla does it.
    private void fogEyeStem() {
        ModelFile side = models().getExistingFile(modLoc("block/fog_eye_stem_side"));
        ModelFile[] caps = new ModelFile[STEM_CAPS];
        caps[0] = models().getExistingFile(modLoc("block/fog_eye_stem_noside"));
        for (int i = 1; i < STEM_CAPS; i++) {
            caps[i] = models().getExistingFile(modLoc("block/fog_eye_stem_noside" + i));
        }
        MultiPartBlockStateBuilder builder = getMultipartBuilder(ModBlocks.FOG_EYE_STEM.get());
        for (Direction dir : Direction.values()) {
            int x = dir.getAxis().isVertical() ? (dir == Direction.UP ? 270 : 90) : 0;
            int y = dir.getAxis().isHorizontal() ? ((int) dir.toYRot() + 180) % 360 : 0;
            BooleanProperty connected = PipeBlock.PROPERTY_BY_DIRECTION.get(dir);

            builder.part().modelFile(side).rotationX(x).rotationY(y).uvLock(true)
                    .addModel().condition(connected, true).end();

            // nextModel() hands back a fresh builder carrying the ones already configured, so each
            // cap has to be threaded through it rather than piled onto the same builder.
            var cap = builder.part();
            for (int i = 0; i < STEM_CAPS; i++) {
                cap = cap.modelFile(caps[i]).rotationX(x).rotationY(y).uvLock(true)
                        .weight(i == 0 ? 2 : 1); // the plain cap twice as often, as vanilla weights it
                if (i < STEM_CAPS - 1) {
                    cap = cap.nextModel();
                }
            }
            cap.addModel().condition(connected, false).end();
        }
        // No item model: the stem has no BlockItem (it is unobtainable).
    }

    // Puff bush: an axis-pillar log and a cutout-cross sapling (item a flat icon of the same texture).
    private void puffBush() {
        logBlock((net.minecraft.world.level.block.RotatedPillarBlock) ModBlocks.PUFF_BUSH_LOG.get());
        itemModels().withExistingParent("puff_bush_log", modLoc("block/puff_bush_log"));

        ModelFile sapling = models().cross("puff_bush_sapling", modLoc("block/puff_bush_sapling"))
                .renderType("cutout");
        getVariantBuilder(ModBlocks.PUFF_BUSH_SAPLING.get())
                .partialState().setModels(new ConfiguredModel(sapling));
        itemModels().withExistingParent("puff_bush_sapling", mcLoc("item/generated"))
                .texture("layer0", modLoc("block/puff_bush_sapling"));
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
