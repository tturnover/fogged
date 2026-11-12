package com.fogged;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import com.fogged.registry.ModRecipes;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;

/**
 * A murk conversion of several blocks at once, decided by how they stand together rather than by what
 * each is alone: a crop on farmland, a lantern hanging under a chain, a door beside its frame.
 *
 * <pre>{@code
 * data/<namespace>/recipe/<name>.json
 * {
 *   "type": "fogged:murk_sequence",
 *   "pattern": [
 *     { "from": "minecraft:farmland",  "to": "minecraft:dead_brain_coral_block" },
 *     { "from": "#minecraft:crops",    "to": "minecraft:dead_brain_coral" }
 *   ],
 *   "depth": 0,
 *   "rotate": false,
 *   "silent": false
 * }
 * }</pre>
 *
 * <p>Each part of the {@code pattern} is one block: {@code from} is what has to stand there (ids,
 * {@code #tags} and {@code *} globs, one or a list), {@code to} what it becomes -- a block in its place,
 * or a non-block item it breaks into. A part with no {@code to} is a <i>condition</i>: it has to be
 * there for the rest to turn, and is left as it is. Parts sit at an {@code offset} [x, y, z] from the
 * first; one that gives none sits straight on top of the part before it, so a column is written as a
 * plain list from the bottom up. {@code rotate} lets a pattern with sideways offsets match facing any of
 * the four ways. {@code depth} and {@code silent} are the single conversion's.
 *
 * <p>The whole pattern turns at once, or not at all, and before any single-block rule gets a word in:
 * otherwise the crop would wilt on its own first and there would be no sequence left to find. The
 * {@code transforms} config list takes the same thing for a column, written with '+':
 * {@code farmland+#crops=dead_brain_coral_block+dead_brain_coral} (see {@link Config#parseSequence}).
 *
 * <p>As with {@link MurkTransformRecipe}, nothing here is craftable; the recipe is only how the rule
 * reloads, reaches clients and reaches JEI.
 */
public record MurkSequenceRecipe(List<Part> pattern, int depth, boolean rotate, boolean silent)
        implements Recipe<RecipeInput> {

    /** Most parts a pattern may have, and furthest a part may sit from the first along any axis. */
    public static final int MAX_PARTS = 16;
    public static final int MAX_REACH = 8;

    /**
     * One block of a pattern. {@code offset} is empty for "straight on top of the part before" (the
     * first part's is the origin); {@code to} is empty for a condition that is left as it stands.
     */
    public record Part(Optional<BlockPos> offset, List<String> from, Optional<String> to) {
        // "from" as one string or a list of them: one block is what most parts name, and a list of one
        // around it is noise. Kept in here, ahead of CODEC, so the two initialise in the right order.
        private static final Codec<List<String>> ONE_OR_MORE = Codec.withAlternative(
                Codec.STRING.listOf(1, 64), Codec.STRING.xmap(List::of, list -> list.get(0)));

        public static final Codec<Part> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BlockPos.CODEC.optionalFieldOf("offset").forGetter(Part::offset),
                ONE_OR_MORE.fieldOf("from").forGetter(Part::from),
                Codec.STRING.optionalFieldOf("to").forGetter(Part::to)
        ).apply(instance, Part::new));
    }

    public static final MapCodec<MurkSequenceRecipe> CODEC = RecordCodecBuilder.<MurkSequenceRecipe>mapCodec(instance ->
            instance.group(
                    Part.CODEC.listOf(1, MAX_PARTS).fieldOf("pattern").forGetter(MurkSequenceRecipe::pattern),
                    Codec.intRange(0, 512).optionalFieldOf("depth", 0).forGetter(MurkSequenceRecipe::depth),
                    Codec.BOOL.optionalFieldOf("rotate", false).forGetter(MurkSequenceRecipe::rotate),
                    Codec.BOOL.optionalFieldOf("silent", false).forGetter(MurkSequenceRecipe::silent)
            ).apply(instance, MurkSequenceRecipe::new))
            .validate(MurkSequenceRecipe::validate);

    // A pattern that changes nothing is a mistake rather than a rule, and one that reaches across half a
    // chunk would have the sweep reading blocks it has no business loading.
    private static DataResult<MurkSequenceRecipe> validate(MurkSequenceRecipe recipe) {
        if (recipe.pattern().stream().noneMatch(p -> p.to().isPresent())) {
            return DataResult.error(() -> "murk_sequence: no part of the pattern has a \"to\"");
        }
        for (BlockPos offset : recipe.offsets()) {
            if (Math.abs(offset.getX()) > MAX_REACH || Math.abs(offset.getY()) > MAX_REACH
                    || Math.abs(offset.getZ()) > MAX_REACH) {
                return DataResult.error(() -> "murk_sequence: a part sits more than " + MAX_REACH
                        + " blocks from the first");
            }
        }
        return DataResult.success(recipe);
    }

    /**
     * Where each part sits relative to the first, in pattern order: its own offset where it gives one,
     * otherwise one block above the part before it.
     */
    public List<BlockPos> offsets() {
        List<BlockPos> out = new ArrayList<>(pattern.size());
        BlockPos previous = null;
        for (Part part : pattern) {
            BlockPos at = part.offset().orElse(previous == null ? BlockPos.ZERO : previous.above());
            out.add(at);
            previous = at;
        }
        return out;
    }

    // --- Recipe, none of which the murk uses (see MurkTransformRecipe) --------

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

    @Override
    public ItemStack getResultItem(HolderLookup.Provider registries) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipes.MURK_SEQUENCE_SERIALIZER.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipes.MURK_SEQUENCE.get();
    }

    /** Through the codec on both sides, as {@link MurkTransformRecipe.Serializer} is and for the same reason. */
    public static final class Serializer implements RecipeSerializer<MurkSequenceRecipe> {
        private static final StreamCodec<RegistryFriendlyByteBuf, MurkSequenceRecipe> STREAM_CODEC =
                ByteBufCodecs.fromCodecWithRegistries(CODEC.codec());

        @Override
        public MapCodec<MurkSequenceRecipe> codec() {
            return CODEC;
        }

        @Override
        public StreamCodec<RegistryFriendlyByteBuf, MurkSequenceRecipe> streamCodec() {
            return STREAM_CODEC;
        }
    }
}
