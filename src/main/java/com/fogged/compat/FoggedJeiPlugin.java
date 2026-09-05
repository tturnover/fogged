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

    // Input, the mark, output -- the mark being the mod's own drawing of what happens: a block passing
    // down through the boundary and coming out the other side changed. It carries its own arrow, so
    // JEI's horizontal one would only say the same thing twice.
    private static class MurkTransformCategory extends AbstractRecipeCategory<MurkTransform> {
        private static final ResourceLocation MARK_TEXTURE =
                ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/gui/jei/murk_transform.png");
        private static final ResourceLocation ICON_TEXTURE =
                ResourceLocation.fromNamespaceAndPath(Fogged.MODID, "textures/gui/jei/murk_transform_icon.png");

        // Where the drawn content sits within each sheet: both are authored on a square canvas with the
        // art somewhere inside it, so the region is taken rather than the whole file -- padding is
        // added here instead, where it can be seen next to everything else.
        private static final int MARK_SHEET = 64;
        private static final int MARK_U = 11;
        private static final int MARK_V = 3;
        private static final int MARK_W = 43;
        private static final int MARK_H = 52;
        private static final int ICON_SHEET = 32;
        private static final int ICON_U = 8;
        private static final int ICON_V = 7;
        private static final int ICON_W = 16;
        private static final int ICON_H = 18;
        private static final int ICON_PAD = 4;  // breathing room around the cube in the category tab

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

        private final IDrawable mark;

        MurkTransformCategory(IGuiHelper guiHelper) {
            super(MURK_TRANSFORM,
                    Component.translatable("fogged.jei.murk_transform"),
                    guiHelper.drawableBuilder(ICON_TEXTURE, ICON_U, ICON_V, ICON_W, ICON_H)
                            .setTextureSize(ICON_SHEET, ICON_SHEET)
                            .addPadding(ICON_PAD, ICON_PAD, ICON_PAD, ICON_PAD)
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
        }
    }
}
