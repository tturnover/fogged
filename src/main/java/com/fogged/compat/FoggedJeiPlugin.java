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

        // The mark's drawn content within its 64x64 sheet, and the icon's own size (16x18, a cube).
        private static final int MARK_U = 12;
        private static final int MARK_V = 4;
        private static final int MARK_W = 42;
        private static final int MARK_H = 56;
        private static final int ICON_W = 16;
        private static final int ICON_H = 18;

        private static final int WIDTH = 90;
        private static final int HEIGHT = 60;
        private static final int MARK_X = 24;
        private static final int MARK_Y = 2;
        private static final int SLOT_Y = 21;   // centred against the mark

        private final IDrawable mark;

        MurkTransformCategory(IGuiHelper guiHelper) {
            super(MURK_TRANSFORM,
                    Component.translatable("fogged.jei.murk_transform"),
                    guiHelper.drawableBuilder(ICON_TEXTURE, 0, 0, ICON_W, ICON_H)
                            .setTextureSize(ICON_W, ICON_H)
                            .build(),
                    WIDTH, HEIGHT);
            this.mark = guiHelper.drawableBuilder(MARK_TEXTURE, MARK_U, MARK_V, MARK_W, MARK_H)
                    .setTextureSize(64, 64)
                    .build();
        }

        @Override
        public void setRecipe(IRecipeLayoutBuilder builder, MurkTransform recipe, IFocusGroup focuses) {
            builder.addSlot(RecipeIngredientRole.INPUT, 1, SLOT_Y)
                    .setStandardSlotBackground()
                    .addItemStacks(recipe.from());
            builder.addSlot(RecipeIngredientRole.OUTPUT, WIDTH - 19, SLOT_Y)
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
