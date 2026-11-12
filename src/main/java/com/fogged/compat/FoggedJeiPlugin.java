package com.fogged.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fogged.Config;
import com.fogged.Fogged;
import com.fogged.ScourRules;
import com.fogged.SequenceRules;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.AbstractRecipeCategory;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/**
 * Shows the murk's block conversions as JEI recipes -- the ones in {@link Config#RECIPE_TRANSFORMS},
 * which is the list that exists to be seen. {@link Config#SILENT_TRANSFORMS} stays out of it: a pack
 * may well transform hundreds of blocks through a tag, and a category full of them is noise, not
 * documentation.
 *
 * <p>This class is loaded by JEI itself, which finds it through {@link JeiPlugin}, so nothing here is
 * touched when JEI is absent. It is also the only place in the mod that names a JEI type.
 */
@JeiPlugin
public class FoggedJeiPlugin implements IModPlugin {

    /**
     * One shown conversion: what may go in (a tag's blocks, or the ids an entry matched), what comes
     * out, and how far under the surface the block has to be for it to happen ({@code 0} = anywhere
     * the murk reaches).
     */
    public record MurkTransform(List<ItemStack> from, ItemStack to, int minDepth) {}

    public static final RecipeType<MurkTransform> MURK_TRANSFORM =
            RecipeType.create(Fogged.MODID, "murk_transform", MurkTransform.class);

    /**
     * One shown sequence, a row per block from the top of the pattern down: what may stand there, and
     * what it becomes -- empty for a block that only has to be there.
     */
    public record MurkSequence(List<Row> rows, int minDepth) {}

    public record Row(List<ItemStack> from, ItemStack to) {
        boolean condition() {
            return to.isEmpty();
        }
    }

    public static final RecipeType<MurkSequence> MURK_SEQUENCE =
            RecipeType.create(Fogged.MODID, "murk_sequence", MurkSequence.class);

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        IGuiHelper gui = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(new MurkTransformCategory(gui), new MurkSequenceCategory(gui));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<MurkTransform> recipes = new ArrayList<>();
        for (ScourRules.Shown shown : ScourRules.shownTransforms()) {
            List<ItemStack> from = shown.fromTag() != null
                    ? tagStacks(shown.fromTag(), shown.fromCount())
                    : stacks(shown.from(), shown.fromCount());
            ItemStack to = new ItemStack(shown.to(), shown.toCount());
            // Something with no item cannot be drawn in a slot, and a recipe with an empty side reads
            // as broken rather than informative -- so such an entry simply is not shown.
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }
            recipes.add(new MurkTransform(from, to, shown.minDepth()));
        }
        registration.addRecipes(MURK_TRANSFORM, recipes);
        // Which entries made it in is otherwise invisible: an id nothing registers, a block with no
        // item and a '!silent' entry all look the same from the outside -- an empty category.
        Fogged.LOGGER.debug("Fogged: showing {} of {} transforms in JEI",
                recipes.size(), Config.TRANSFORMS.get().size());

        List<MurkSequence> sequences = new ArrayList<>();
        for (SequenceRules.Shown shown : ScourRules.shownSequences()) {
            MurkSequence sequence = sequence(shown);
            if (sequence != null) {
                sequences.add(sequence);
            }
        }
        registration.addRecipes(MURK_SEQUENCE, sequences);
    }

    // Top of the pattern first, the way the blocks stand, so a column reads as it is built. A part with
    // nothing to draw on its left is not worth a picture of the rest, so such a sequence is left out.
    private static MurkSequence sequence(SequenceRules.Shown shown) {
        List<SequenceRules.ShownPart> parts = new ArrayList<>(shown.parts());
        parts.sort(Comparator.comparingInt((SequenceRules.ShownPart p) -> -p.offset().getY())
                .thenComparingInt(p -> p.offset().getX())
                .thenComparingInt(p -> p.offset().getZ()));
        List<Row> rows = new ArrayList<>();
        for (SequenceRules.ShownPart part : parts) {
            List<ItemStack> from = blockStacks(part.blocks(), part.tags());
            if (from.isEmpty()) {
                return null;
            }
            ItemStack to = part.to() == null || part.to() == Items.AIR ? ItemStack.EMPTY : new ItemStack(part.to());
            rows.add(new Row(from, to));
        }
        return new MurkSequence(List.copyOf(rows), shown.minDepth());
    }

    // What a block is held as -- a crop as its seed, which is what JEI knows it by.
    private static List<ItemStack> blockStacks(List<Block> blocks, List<TagKey<Block>> tags) {
        List<ItemStack> out = new ArrayList<>();
        for (Block block : blocks) {
            addBlock(out, block);
        }
        for (TagKey<Block> tag : tags) {
            BuiltInRegistries.BLOCK.getTag(tag)
                    .ifPresent(holders -> holders.forEach(h -> addBlock(out, h.value())));
        }
        return out;
    }

    private static void addBlock(List<ItemStack> out, Block block) {
        ItemStack stack = new ItemStack(block.asItem());
        if (!stack.isEmpty() && out.stream().noneMatch(s -> s.is(stack.getItem()))) {
            out.add(stack);
        }
    }

    // Tags are expanded here rather than when the config is read: their contents come from the
    // datapacks in force, which are only known once a world is loaded -- which is also when JEI asks.
    private static List<ItemStack> tagStacks(TagKey<Item> tag, int count) {
        List<ItemStack> out = new ArrayList<>();
        BuiltInRegistries.ITEM.getTag(tag)
                .ifPresent(holders -> holders.forEach(h -> out.add(new ItemStack(h.value(), count))));
        return out;
    }

    private static List<ItemStack> stacks(List<Item> items, int count) {
        List<ItemStack> out = new ArrayList<>();
        for (Item item : items) {
            ItemStack stack = new ItemStack(item, count);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    // Input, the mark, output -- the mark being the mod's own drawing of what happens: a block passing
    // down through the boundary and coming out the other side changed. It carries its own arrow, so
    // JEI's horizontal one would only say the same thing twice.
    private static class MurkTransformCategory extends AbstractRecipeCategory<MurkTransform> {
        private static final ResourceLocation MARK_TEXTURE =
                ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/gui/jei/murk_transform.png");
        private static final ResourceLocation ICON_TEXTURE =
                ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/gui/jei/murk_transform_icon.png");

        // Where the drawn content sits within each sheet: both are authored on a square canvas with the
        // art somewhere inside it, so the region is taken rather than the whole file. Both are drawn at
        // the size they were authored -- no padding around them, and nothing scaled.
        private static final int MARK_SHEET = 64;
        private static final int MARK_U = 9;
        private static final int MARK_V = 3;
        private static final int MARK_W = 48;
        private static final int MARK_H = 52;
        private static final int ICON_SHEET = 16;
        private static final int ICON_U = 2;
        private static final int ICON_V = 1;
        private static final int ICON_W = 12;
        private static final int ICON_H = 14;

        // Laid out from the sizes JEI actually draws, not from the 16x16 ingredient areas: a standard
        // slot background is 18x18 around its ingredient (offset -1) and an OUTPUT slot background is
        // 26x26 (offset -5). Positioning both as if they were the same size is what left the row
        // looking shoved to one side, with the output's larger frame hanging over the right edge.
        private static final int PAD = 4;       // margin at the edges, and between each pair of parts
        private static final int IN_BG = 18;
        private static final int OUT_BG = 26;
        private static final int INGREDIENT = 16;

        private static final int WIDTH = PAD + IN_BG + PAD + MARK_W + PAD + OUT_BG + PAD;
        private static final int HEIGHT = MARK_H + 2 * 2;
        private static final int MARK_X = PAD + IN_BG + PAD;
        private static final int MARK_Y = (HEIGHT - MARK_H) / 2;
        private static final int SLOT_Y = (HEIGHT - INGREDIENT) / 2;
        private static final int IN_X = PAD + 1;                        // +1: the 18x18 frame's inset
        private static final int OUT_X = MARK_X + MARK_W + PAD + 5;     // +5: the 26x26 frame's inset

        // The depth mark: the arrow is 6x8 and the number sits beside it on the same rows. It goes in
        // the empty quarter of the mark itself -- under the boundary line, left of the falling arrow --
        // rather than in the corner of the category, so it reads as part of the picture: this far below
        // that line. Nothing is drawn there by the art, so nothing is covered.
        private static final int DEPTH_X = MARK_X;
        private static final int DEPTH_Y = 43;
        private static final int DEPTH_COLOR = 0xFF404040;

        private final IDrawable mark;

        MurkTransformCategory(IGuiHelper guiHelper) {
            super(MURK_TRANSFORM,
                    Component.translatable("fogged.jei.murk_transform"),
                    guiHelper.drawableBuilder(ICON_TEXTURE, ICON_U, ICON_V, ICON_W, ICON_H)
                            .setTextureSize(ICON_SHEET, ICON_SHEET)
                            .build(),
                    WIDTH, HEIGHT);
            this.mark = guiHelper.drawableBuilder(MARK_TEXTURE, MARK_U, MARK_V, MARK_W, MARK_H)
                    .setTextureSize(MARK_SHEET, MARK_SHEET)
                    .build();
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, MurkTransform recipe, IFocusGroup focuses) {
            builder.addSlot(RecipeIngredientRole.INPUT, IN_X, SLOT_Y)
                    .setStandardSlotBackground()
                    .addItemStacks(recipe.from());
            builder.addSlot(RecipeIngredientRole.OUTPUT, OUT_X, SLOT_Y)
                    .setOutputSlotBackground()
                    .addItemStack(recipe.to());
        }

        @Override
        public void draw(MurkTransform recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                double mouseX, double mouseY) {
            mark.draw(graphics, MARK_X, MARK_Y);
            if (recipe.minDepth() > 0) {
                drawDepth(graphics, recipe.minDepth());
            }
        }

        // How deep the block has to be, in the bottom-left corner: an arrow pointing down and the
        // number of blocks. Drawn rather than written out, because the recipe has no room for a
        // sentence and the arrow says which way the number goes.
        private static void drawDepth(GuiGraphics graphics, int blocks) {
            final int x = DEPTH_X;
            final int y = DEPTH_Y;
            // Shaft, then a head three rows tall narrowing to a point.
            graphics.fill(x + 2, y, x + 3, y + 5, DEPTH_COLOR);
            graphics.fill(x, y + 4, x + 5, y + 5, DEPTH_COLOR);
            graphics.fill(x + 1, y + 5, x + 4, y + 6, DEPTH_COLOR);
            graphics.fill(x + 2, y + 6, x + 3, y + 7, DEPTH_COLOR);
            graphics.drawString(Minecraft.getInstance().font, Integer.toString(blocks),
                    x + 7, y, DEPTH_COLOR, false);
        }
    }

    // The same picture as a single conversion, with a column of blocks either side of the mark: what
    // stands there on the left, what it becomes on the right, row for row. A block that only has to be
    // there sits on the left alone, as a catalyst -- needed, and not used up.
    private static class MurkSequenceCategory extends AbstractRecipeCategory<MurkSequence> {
        private static final int PAD = MurkTransformCategory.PAD;
        private static final int SLOT = MurkTransformCategory.IN_BG;
        private static final int MARK_W = MurkTransformCategory.MARK_W;
        private static final int MARK_H = MurkTransformCategory.MARK_H;
        // A category is one size for all its recipes, so it is sized for this many rows: enough for a
        // crop on its soil with room above, without making the common two-block case a tall frame.
        // A taller pattern shows its top rows only.
        private static final int MOST_ROWS = 4;
        private static final int HEIGHT = Math.max(MARK_H + 2 * 2, MOST_ROWS * SLOT + 2 * PAD);
        private static final int MARK_Y = (HEIGHT - MARK_H) / 2;

        private static final int WIDTH = PAD + SLOT + PAD + MARK_W + PAD + SLOT + PAD;
        private static final int MARK_X = PAD + SLOT + PAD;
        private static final int IN_X = PAD + 1;
        private static final int OUT_X = MARK_X + MARK_W + PAD + 1;

        private final IDrawable mark;

        MurkSequenceCategory(IGuiHelper guiHelper) {
            super(MURK_SEQUENCE,
                    Component.translatable("fogged.jei.murk_sequence"),
                    guiHelper.drawableBuilder(MurkTransformCategory.ICON_TEXTURE, MurkTransformCategory.ICON_U,
                                    MurkTransformCategory.ICON_V, MurkTransformCategory.ICON_W,
                                    MurkTransformCategory.ICON_H)
                            .setTextureSize(MurkTransformCategory.ICON_SHEET, MurkTransformCategory.ICON_SHEET)
                            .build(),
                    WIDTH, HEIGHT);
            this.mark = guiHelper.drawableBuilder(MurkTransformCategory.MARK_TEXTURE, MurkTransformCategory.MARK_U,
                            MurkTransformCategory.MARK_V, MARK_W, MARK_H)
                    .setTextureSize(MurkTransformCategory.MARK_SHEET, MurkTransformCategory.MARK_SHEET)
                    .build();
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, MurkSequence recipe, IFocusGroup focuses) {
            int rows = Math.min(recipe.rows().size(), MOST_ROWS);
            int top = (HEIGHT - rows * SLOT) / 2 + 1; // +1: the 18x18 frame's inset
            for (int i = 0; i < rows; i++) {
                Row row = recipe.rows().get(i);
                int y = top + i * SLOT;
                builder.addSlot(row.condition() ? RecipeIngredientRole.CATALYST : RecipeIngredientRole.INPUT, IN_X, y)
                        .setStandardSlotBackground()
                        .addItemStacks(row.from());
                if (!row.condition()) {
                    builder.addSlot(RecipeIngredientRole.OUTPUT, OUT_X, y)
                            .setStandardSlotBackground()
                            .addItemStack(row.to());
                }
            }
        }

        @Override
        public void draw(MurkSequence recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                double mouseX, double mouseY) {
            mark.draw(graphics, MARK_X, MARK_Y);
            if (recipe.minDepth() > 0) {
                graphics.pose().pushPose();
                graphics.pose().translate(0, MARK_Y - MurkTransformCategory.MARK_Y, 0);
                MurkTransformCategory.drawDepth(graphics, recipe.minDepth());
                graphics.pose().popPose();
            }
        }
    }
}
