package com.fogged.compat;

import java.util.ArrayList;
import java.util.List;

import com.fogged.Config;
import com.fogged.Fogged;
import com.fogged.ScourRules;

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

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
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

    /** One shown conversion: what may go in (a tag's blocks, or the ids an entry matched) and what comes out. */
    public record MurkTransform(List<ItemStack> from, ItemStack to) {}

    public static final RecipeType<MurkTransform> MURK_TRANSFORM =
            RecipeType.create(Fogged.MODID, "murk_transform", MurkTransform.class);

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "jei");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new MurkTransformCategory(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        List<MurkTransform> recipes = new ArrayList<>();
        for (ScourRules.Shown shown : ScourRules.shownTransforms()) {
            List<ItemStack> from = shown.fromTag() != null ? tagStacks(shown) : stacks(shown.from());
            ItemStack to = new ItemStack(shown.to());
            // A block with no item cannot be drawn in a slot, and a recipe with an empty side reads as
            // broken rather than informative -- so such an entry simply is not shown.
            if (from.isEmpty() || to.isEmpty()) {
                continue;
            }
            recipes.add(new MurkTransform(from, to));
        }
        registration.addRecipes(MURK_TRANSFORM, recipes);
        // Which entries made it in is otherwise invisible: an id nothing registers, a block with no
        // item and an entry with no '|jei' all look the same from the outside -- an empty category.
        Fogged.LOGGER.debug("Fogged: showing {} of {} recipe transforms in JEI",
                recipes.size(), Config.RECIPE_TRANSFORMS.get().size());
    }

    // Tags are expanded here rather than when the config is read: their contents come from the
    // datapacks in force, which are only known once a world is loaded -- which is also when JEI asks.
    private static List<ItemStack> tagStacks(ScourRules.Shown shown) {
        List<Block> blocks = new ArrayList<>();
        BuiltInRegistries.BLOCK.getTag(shown.fromTag())
                .ifPresent(holders -> holders.forEach(holder -> blocks.add(holder.value())));
        return stacks(blocks);
    }

    private static List<ItemStack> stacks(List<Block> blocks) {
        List<ItemStack> out = new ArrayList<>();
        for (Block block : blocks) {
            ItemStack stack = new ItemStack(block);
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    // One row, read like a Create processing recipe: what the murk takes, an arrow, what it leaves --
    // and under the arrow, in place of Create's flame, the thing that does the work. Here that is the
    // murk itself: a block sunk under the boundary, drawn in the colours the plane is actually
    // configured with, so the picture says "put it under the fog" without a word of text.
    private static class MurkTransformCategory extends AbstractRecipeCategory<MurkTransform> {
        private static final int WIDTH = 100;
        private static final int HEIGHT = 36;
        private static final int SLOT_Y = 4;
        private static final int ARROW_X = 40;
        private static final int GLYPH_X = 42;
        private static final int GLYPH_Y = 22;

        private final IDrawable arrow;

        MurkTransformCategory(IGuiHelper guiHelper) {
            super(MURK_TRANSFORM,
                    Component.translatable("fogged.jei.murk_transform"),
                    new MurkGlyph(),
                    WIDTH, HEIGHT);
            this.arrow = guiHelper.getRecipeArrow();
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, MurkTransform recipe, IFocusGroup focuses) {
            builder.addSlot(RecipeIngredientRole.INPUT, 1, SLOT_Y)
                    .setStandardSlotBackground()
                    .addItemStacks(recipe.from());
            builder.addSlot(RecipeIngredientRole.OUTPUT, 79, SLOT_Y)
                    .setOutputSlotBackground()
                    .addItemStack(recipe.to());
        }

        @Override
        public void draw(MurkTransform recipe, IRecipeSlotsView slots, GuiGraphics graphics,
                double mouseX, double mouseY) {
            arrow.draw(graphics, ARROW_X, SLOT_Y + 1);
            GLYPH.draw(graphics, GLYPH_X, GLYPH_Y);
        }
    }

    private static final MurkGlyph GLYPH = new MurkGlyph();

    /**
     * The "put it under the fog" mark: a pale block dropping through the boundary into the murk under
     * it, the top half above the line and the bottom half swallowed.
     *
     * <p>Drawn rather than textured, from {@link Config#planeColor()} and {@link Config#foamColor()},
     * so a pack that recolours the murk recolours this too and the two never disagree. Alpha is forced
     * opaque: the plane's own alpha is a render setting, not a palette.
     */
    private static class MurkGlyph implements IDrawable {
        private static final int SIZE = 16;
        private static final int SURFACE_Y = 7;   // where the boundary line sits within the glyph
        private static final int BLOCK_X = 4;
        private static final int BLOCK_W = 8;
        private static final int BLOCK_TOP = 2;
        private static final int BLOCK_BOTTOM = 13;

        @Override
        public int getWidth() {
            return SIZE;
        }

        @Override
        public int getHeight() {
            return SIZE;
        }

        @Override
        public void draw(GuiGraphics graphics, int x, int y) {
            int murk = opaque(Config.planeColor());
            int foam = opaque(Config.foamColor());

            // The murk below the boundary, then the boundary itself as the brighter foam line.
            graphics.fill(x, y + SURFACE_Y, x + SIZE, y + SIZE, murk);
            graphics.fill(x, y + SURFACE_Y - 1, x + SIZE, y + SURFACE_Y, foam);

            // The block: solid where it is still in the air, and only outlined where the murk has it,
            // which is what the murk does to anything below the line -- you see the shape, not the thing.
            graphics.fill(x + BLOCK_X, y + BLOCK_TOP, x + BLOCK_X + BLOCK_W, y + SURFACE_Y - 1, 0xFFDDDDDD);
            graphics.fill(x + BLOCK_X, y + SURFACE_Y, x + BLOCK_X + BLOCK_W, y + BLOCK_BOTTOM, 0x66FFFFFF);
        }

        private static int opaque(float[] rgba) {
            int r = Math.round(Mth.clamp(rgba[0], 0.0F, 1.0F) * 255.0F);
            int g = Math.round(Mth.clamp(rgba[1], 0.0F, 1.0F) * 255.0F);
            int b = Math.round(Mth.clamp(rgba[2], 0.0F, 1.0F) * 255.0F);
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
    }
}
