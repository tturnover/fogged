package com.fogged;

import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.fogged.registry.ModRecipes;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * One murk conversion as a datapack file, so that a mod or a pack can ship what the murk turns its
 * blocks into instead of asking a player to type it into the config:
 *
 * <pre>{@code
 * data/<namespace>/recipe/<name>.json
 * {
 *   "type": "fogged:murk_transform",
 *   "from": ["minecraft:copper_block", "#minecraft:copper_ores"],
 *   "count": 1,
 *   "to": "minecraft:oxidized_copper",
 *   "result_count": 1,
 *   "depth": 0,
 *   "silent": false
 * }
 * }</pre>
 *
 * <p>The fields are the config line's, under names a JSON file can carry: {@code from} takes ids,
 * {@code #tags} and {@code *} globs and may name several at once; {@code to} is one id, a block to put
 * in its place or a non-block item to break it into; {@code count} and {@code result_count} are about
 * dropped stacks; {@code depth} is how far under the surface it has to be; {@code silent} keeps it out
 * of JEI. {@link com.fogged.ScourRules} reads it through {@link #toEntry()}, which writes the recipe
 * back out as that same config line -- one parser for both sources, so a datapack entry cannot behave
 * differently from a configured one.
 *
 * <p>Nothing here is craftable: the murk is not a workbench, and the recipe exists for what a recipe
 * carries (a datapack file that reloads, reaches every client and is read by JEI) rather than for what
 * it makes. So {@link #matches} is never true and the recipe never appears in the recipe book.
 */
public record MurkTransformRecipe(List<String> from, int count, String to, int resultCount,
                                  int depth, boolean silent) implements Recipe<RecipeInput> {

    public static final MapCodec<MurkTransformRecipe> CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    Codec.STRING.listOf().fieldOf("from").forGetter(MurkTransformRecipe::from),
                    Codec.intRange(1, 64).optionalFieldOf("count", 1).forGetter(MurkTransformRecipe::count),
                    Codec.STRING.fieldOf("to").forGetter(MurkTransformRecipe::to),
                    Codec.intRange(1, 64).optionalFieldOf("result_count", 1).forGetter(MurkTransformRecipe::resultCount),
                    Codec.intRange(0, 512).optionalFieldOf("depth", 0).forGetter(MurkTransformRecipe::depth),
                    Codec.BOOL.optionalFieldOf("silent", false).forGetter(MurkTransformRecipe::silent)
            ).apply(instance, MurkTransformRecipe::new));

    /**
     * This recipe written as one {@code transforms} config line, which is what actually drives the
     * murk. Keeping the two in one grammar means a datapack file and a config line are the same rule,
     * with the same wildcards, the same tag handling and the same warnings when they are wrong.
     */
    public String toEntry() {
        StringBuilder line = new StringBuilder();
        if (count != 1) {
            line.append(count).append(' ');
        }
        line.append(String.join(",", from)).append('=');
        if (resultCount != 1) {
            line.append(resultCount).append(' ');
        }
        line.append(to);
        if (depth > 0) {
            line.append(" @").append(depth);
        }
        if (silent) {
            line.append(" !silent");
        }
        return line.toString();
    }

    // --- Recipe, none of which the murk uses ---------------------------------

    @Override
    public boolean matches(RecipeInput input, net.minecraft.world.level.Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(RecipeInput input, HolderLookup.Provider registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return false;
    }

    /** What the conversion gives, where that is an item at all -- JEI and the recipe book ask. */
    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        ResourceLocation id = ResourceLocation.tryParse(to);
        if (id == null) {
            return ItemStack.EMPTY;
        }
        return BuiltInRegistries.ITEM.getOptional(id)
                .map(item -> new ItemStack(item, resultCount))
                .orElse(ItemStack.EMPTY);
    }

    /** Keeps it out of the recipe book, which has nothing to show for a rule nobody crafts. */
    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.MURK_TRANSFORM_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.MURK_TRANSFORM.get();
    }

    /**
     * Read and written through the same codec on both sides: the recipe is a handful of short strings,
     * so there is nothing to gain from a hand-packed network form, and one definition cannot drift
     * from the other.
     */
    public static final class Serializer implements RecipeSerializer<MurkTransformRecipe> {
        private static final StreamCodec<RegistryFriendlyByteBuf, MurkTransformRecipe> STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());

        @Override
        public MapCodec<MurkTransformRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, MurkTransformRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
