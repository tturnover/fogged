package com.fogged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The murk's multi-block conversions ({@link MurkSequenceRecipe}, and the '+' lines of
 * {@link Config#TRANSFORMS}), resolved against the registries and asked about one block at a time, the
 * way {@link FogScour} walks the world.
 *
 * <p>A pattern is found from any of its changing parts, not only from its first: the sweep reaches a
 * crop and the farmland under it in whichever order it reaches them, and either one has to be enough
 * to turn both. So every changing part is indexed by what may stand there, and a block that is one of
 * them is tried as that part -- in each facing, where the pattern rotates -- with the rest of the
 * pattern read around it. A match turns every part at once.
 *
 * <p>Built by {@link ScourRules} alongside its own rules, and gated by the same switch.
 */
public final class SequenceRules {
    private SequenceRules() {}

    /** What may stand in one part: blocks by id (globs already expanded), and tags tested per state. */
    private record Matcher(Set<Block> blocks, List<TagKey<Block>> tags) {
        boolean test(BlockState state) {
            if (blocks.contains(state.getBlock())) {
                return true;
            }
            for (TagKey<Block> tag : tags) {
                if (state.is(tag)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * One resolved part. Neither target set: a condition, left standing. {@code toBlock}: put in its
     * place. {@code toItem}: the block breaks and drops one.
     */
    private record Part(BlockPos offset, Matcher match, Block toBlock, Item toItem) {
        boolean changes() {
            return toBlock != null || toItem != null;
        }

        // Whether turning this part would do anything -- a block already its target does not.
        boolean wouldChange(BlockState state) {
            return toItem != null || (toBlock != null && !state.is(toBlock));
        }
    }

    record Sequence(List<Part> parts, int minDepth, int facings) {}

    // A part of a sequence, as found from the block standing in it.
    private record Hit(Sequence sequence, int part) {}

    private record TagHit(TagKey<Block> tag, Hit hit) {}

    /** A pattern found standing in the world: which, where its first part is, and which way it faces. */
    record Match(Sequence sequence, BlockPos origin, int facing) {}

    /**
     * One sequence to show in JEI, a part per row in pattern order. A part's blocks are what its ids
     * matched and its tags are left as tags, for whoever draws them to expand against the datapacks in
     * force; {@code to} is null for a condition.
     */
    public record ShownPart(BlockPos offset, List<Block> blocks, List<TagKey<Block>> tags, Item to) {}

    public record Shown(List<ShownPart> parts, int minDepth, boolean rotates) {}

    private static Map<Block, List<Hit>> byBlock = Map.of();
    private static List<TagHit> byTag = List.of();
    private static List<Shown> shown = List.of();

    static List<Shown> shown() {
        return shown;
    }

    // --- Building -------------------------------------------------------------

    /** Replace every sequence with these, in priority order: the first to match a block is the one used. */
    static void rebuild(List<MurkSequenceRecipe> specs) {
        Map<Block, List<Hit>> blocks = new HashMap<>();
        List<TagHit> tags = new ArrayList<>();
        List<Shown> display = new ArrayList<>();

        for (MurkSequenceRecipe spec : specs) {
            List<BlockPos> offsets = spec.offsets();
            List<Part> parts = new ArrayList<>();
            List<ShownPart> shownParts = new ArrayList<>();
            boolean usable = true;
            for (int i = 0; i < spec.pattern().size() && usable; i++) {
                MurkSequenceRecipe.Part raw = spec.pattern().get(i);
                List<Pattern> globs = new ArrayList<>();
                List<TagKey<Block>> partTags = new ArrayList<>();
                for (String from : raw.from()) {
                    String id = from.trim();
                    if (id.startsWith("#")) {
                        TagKey<Block> tag = ScourRules.blockTag(id.substring(1));
                        if (tag != null) {
                            partTags.add(tag);
                        }
                    } else if (!id.isEmpty()) {
                        globs.add(Config.idGlob(Config.withNamespace(id)));
                    }
                }
                Set<Block> matched = ScourRules.matchingBlocks(globs);
                if (matched.isEmpty() && partTags.isEmpty()) {
                    // Nothing could ever stand in this part, so the pattern can never be whole -- most
                    // likely a block from a mod that is not installed.
                    Fogged.LOGGER.warn("Fogged: murk sequence {} has a part nothing matches ({}) -- skipped.",
                            describe(spec), raw.from());
                    usable = false;
                    continue;
                }
                Block toBlock = null;
                Item toItem = null;
                Optional<String> to = raw.to().map(String::trim).filter(s -> !s.isEmpty());
                if (to.isPresent()) {
                    String id = Config.withNamespace(to.get());
                    toBlock = ScourRules.block(id);
                    toItem = toBlock == null ? ScourRules.item(id) : null;
                    if (toBlock == null && toItem == null) {
                        Fogged.LOGGER.warn("Fogged: murk sequence {} names an unknown target \"{}\" -- "
                                + "skipped. (A mod that owns it may simply not be installed.)",
                                describe(spec), to.get());
                        usable = false;
                        continue;
                    }
                }
                parts.add(new Part(offsets.get(i), new Matcher(matched, List.copyOf(partTags)), toBlock, toItem));
                shownParts.add(new ShownPart(offsets.get(i), List.copyOf(matched), List.copyOf(partTags),
                        toItem != null ? toItem : toBlock != null ? toBlock.asItem() : null));
            }
            if (!usable) {
                continue;
            }

            // Only a pattern that reaches sideways has anywhere to turn: a column faces every way at once.
            boolean sideways = parts.stream().anyMatch(p -> p.offset().getX() != 0 || p.offset().getZ() != 0);
            Sequence sequence = new Sequence(List.copyOf(parts), spec.depth(), spec.rotate() && sideways ? 4 : 1);
            for (int i = 0; i < parts.size(); i++) {
                Part part = parts.get(i);
                if (!part.changes()) {
                    continue; // a condition is found from the parts that change, so it is not indexed
                }
                Hit hit = new Hit(sequence, i);
                for (Block block : part.match().blocks()) {
                    blocks.computeIfAbsent(block, b -> new ArrayList<>()).add(hit);
                }
                for (TagKey<Block> tag : part.match().tags()) {
                    tags.add(new TagHit(tag, hit));
                }
            }
            if (!spec.silent()) {
                display.add(new Shown(List.copyOf(shownParts), spec.depth(), sequence.facings() > 1));
            }
        }

        Map<Block, List<Hit>> frozen = new HashMap<>();
        blocks.forEach((block, hits) -> frozen.put(block, List.copyOf(hits)));
        byBlock = Map.copyOf(frozen);
        byTag = List.copyOf(tags);
        shown = List.copyOf(display);
    }

    private static String describe(MurkSequenceRecipe spec) {
        return spec.pattern().stream().map(p -> String.join(",", p.from())).toList().toString();
    }

    // --- Asking ---------------------------------------------------------------

    /** Whether this block could be a changing part of any sequence -- the cheap, palette-level test. */
    static boolean mayTouch(BlockState state) {
        if (byBlock.containsKey(state.getBlock())) {
            return true;
        }
        for (TagHit t : byTag) {
            if (state.is(t.tag())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The first sequence {@code state} at {@code pos} completes, whole and ready to turn, or null. Ready
     * means every part is what it has to be, deep enough and outside breathable air, and at least one
     * of them would actually change.
     */
    static Match find(Level level, BlockPos pos, BlockState state) {
        List<Hit> direct = byBlock.get(state.getBlock());
        if (direct != null) {
            for (Hit hit : direct) {
                Match match = tryHit(level, pos, state, hit);
                if (match != null) {
                    return match;
                }
            }
        }
        for (TagHit t : byTag) {
            if (state.is(t.tag())) {
                Match match = tryHit(level, pos, state, t.hit());
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    private static Match tryHit(Level level, BlockPos pos, BlockState state, Hit hit) {
        Sequence sequence = hit.sequence();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int facing = 0; facing < sequence.facings(); facing++) {
            BlockPos origin = pos.subtract(rotate(sequence.parts().get(hit.part()).offset(), facing));
            if (whole(level, sequence, origin, facing, hit.part(), state, at)) {
                return new Match(sequence, origin, facing);
            }
        }
        return null;
    }

    private static boolean whole(Level level, Sequence sequence, BlockPos origin, int facing,
            int known, BlockState knownState, BlockPos.MutableBlockPos at) {
        boolean anyChange = false;
        for (int i = 0; i < sequence.parts().size(); i++) {
            Part part = sequence.parts().get(i);
            place(at, origin, part.offset(), facing);
            BlockState there;
            if (i == known) {
                there = knownState;
            } else {
                // A part in a chunk that is not loaded is not there: reading it would load the chunk,
                // from inside the sweep, for the sake of one block.
                if (!level.hasChunkAt(at)) {
                    return false;
                }
                there = level.getBlockState(at);
            }
            if (!part.match().test(there)) {
                return false;
            }
            if (part.changes()) {
                if (!ScourRules.deepEnough(level, sequence.minDepth(), at.getY())) {
                    return false;
                }
                anyChange |= part.wouldChange(there);
            }
        }
        if (!anyChange) {
            return false;
        }
        // Breathable air last: it is the dear test, and a pattern only gets this far when it is whole.
        for (Part part : sequence.parts()) {
            if (part.changes() && Shelter.shelters(level, place(at, origin, part.offset(), facing))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Turn every part of a match. The new blocks all go in before anything is told of them, then the
     * neighbours are updated, then whatever breaks into an item breaks: set one at a time, the first
     * change would be judged against the old rest -- a crop told its farmland is now coral pops off as
     * a seed before it is reached.
     */
    static void apply(Level level, Match match) {
        Sequence sequence = match.sequence();
        List<BlockPos> placed = new ArrayList<>();
        List<Part> broken = new ArrayList<>();
        List<BlockPos> brokenAt = new ArrayList<>();
        for (Part part : sequence.parts()) {
            if (!part.changes()) {
                continue;
            }
            BlockPos at = place(new BlockPos.MutableBlockPos(), match.origin(), part.offset(), match.facing())
                    .immutable();
            BlockState old = level.getBlockState(at);
            if (!part.wouldChange(old)) {
                continue;
            }
            if (part.toBlock() != null) {
                BlockState next = ScourRules.carryOver(old, part.toBlock().defaultBlockState());
                level.setBlock(at, next, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                placed.add(at);
            } else {
                broken.add(part);
                brokenAt.add(at);
            }
        }
        for (BlockPos at : placed) {
            BlockState now = level.getBlockState(at);
            level.blockUpdated(at, now.getBlock());
            now.updateNeighbourShapes(level, at, Block.UPDATE_ALL);
        }
        for (int i = 0; i < broken.size(); i++) {
            BlockPos at = brokenAt.get(i);
            level.destroyBlock(at, false);
            Block.popResource(level, at, new ItemStack(broken.get(i).toItem()));
        }
    }

    // --- Facing ---------------------------------------------------------------

    // An offset turned a quarter at a time about the vertical axis through the first part.
    private static BlockPos rotate(BlockPos offset, int facing) {
        return switch (facing & 3) {
            case 1 -> new BlockPos(-offset.getZ(), offset.getY(), offset.getX());
            case 2 -> new BlockPos(-offset.getX(), offset.getY(), -offset.getZ());
            case 3 -> new BlockPos(offset.getZ(), offset.getY(), -offset.getX());
            default -> offset;
        };
    }

    private static BlockPos.MutableBlockPos place(BlockPos.MutableBlockPos out, BlockPos origin,
            BlockPos offset, int facing) {
        BlockPos turned = rotate(offset, facing);
        return out.set(origin.getX() + turned.getX(), origin.getY() + turned.getY(),
                origin.getZ() + turned.getZ());
    }
}
