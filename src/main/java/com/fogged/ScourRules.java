package com.fogged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The configurable half of the under-fog scour: the blocks {@link Config#SCOURED_BLOCKS} says the murk
 * takes, and the swaps it makes ("from=to"): {@link Config#SILENT_TRANSFORMS}, which just happen, and
 * {@link Config#RECIPE_TRANSFORMS}, which are also shown in JEI. Copper weathering all the way through
 * is a silent default; coal ore going back to stone is a shown one.
 *
 * <p>Both run before {@link FogScour}'s built-in rules, so a config entry overrides what the murk would
 * otherwise do to that block, and a transform wins over a removal for the same block.
 *
 * <p>Entries are ids (with '*' wildcards, resolved against the block registry once per config change --
 * the scour walks every block in the band, so a regex per block would be the mod's hottest loop) or
 * '#' tags, which stay as tag keys and are tested per state: tag contents come from datapacks and can
 * change under us, where the registry cannot.
 */
public final class ScourRules {
    private ScourRules() {}

    private static List<? extends String> cachedScoured;
    private static List<? extends String> cachedSilent;
    private static List<? extends String> cachedRecipes;

    private static Set<Block> scouredBlocks = Set.of();
    private static List<TagKey<Block>> scouredTags = List.of();
    private static Map<Block, Transform> transformBlocks = Map.of();
    private static List<TagTransform> transformTags = List.of();
    private static List<Shown> shown = List.of();

    /** What a block turns into, and how far under the surface it has to be first (0 = anywhere). */
    private record Transform(Block to, int minDepth) {}

    private record TagTransform(TagKey<Block> tag, Transform transform) {}

    /**
     * A transform from the shown list, for JEI. Exactly one of {@code from}
     * and {@code fromTag} is set: an id entry resolves to the blocks it matched, a tag entry stays a tag
     * so whoever displays it can expand it against the datapack in force.
     */
    public record Shown(List<Block> from, TagKey<Block> fromTag, Block to, int minDepth) {}

    /** The transforms flagged for display, in config order. Never null; empty when none are flagged. */
    public static List<Shown> shownTransforms() {
        ensureRules();
        return shown;
    }

    /**
     * Apply the configured rules to one block, most specific first. Returns true when this block has
     * been dealt with and the built-in scour should leave it alone.
     */
    public static boolean apply(Level level, BlockPos pos, BlockState state) {
        ensureRules();
        Transform transform = transformFor(state);
        if (transform != null) {
            // A depth is measured from the visible surface down, so it moves with the boundary rather
            // than sitting at some fixed Y: fifty blocks under the murk stays fifty blocks under it as
            // the plane rises and falls. Above the depth the block is simply left to the rules below.
            if (transform.minDepth() > 0
                    && Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET - pos.getY() < transform.minDepth()) {
                return false;
            }
            Block to = transform.to();
            if (!state.is(to)) {
                level.setBlock(pos, carryOver(state, to.defaultBlockState()), Block.UPDATE_ALL);
            }
            return true; // handled either way: a no-op swap is still the config's answer for this block
        }
        if (isScoured(state)) {
            level.destroyBlock(pos, false); // no drops: the murk takes it, it is not being mined
            return true;
        }
        return false;
    }

    /**
     * The new state, wearing every property the old one had that it also has: a stair keeps its facing,
     * shape and half, a slab which half it is, a door its hinge and whether it is open. Without this a
     * transform snaps everything it touches to a default state, which for copper stairs and doors --
     * the whole point of the copper defaults -- rearranges the build it is weathering.
     */
    private static BlockState carryOver(BlockState from, BlockState to) {
        BlockState out = to;
        for (Property<?> property : from.getProperties()) {
            if (out.hasProperty(property)) {
                out = copy(from, out, property);
            }
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState from, BlockState to, Property<T> property) {
        return to.setValue(property, from.getValue(property));
    }

    /**
     * What a dropped item of {@code block} should become at world height {@code y}, or null when
     * nothing applies -- the same rules the blocks themselves go through, depth included, so a stack
     * lying in the murk keeps up with the world around it rather than surviving what its placed form
     * cannot.
     */
    public static Block transformForItem(Level level, Block block, double y) {
        ensureRules();
        Transform transform = transformFor(block.defaultBlockState());
        if (transform == null || transform.to() == block) {
            return null;
        }
        if (transform.minDepth() > 0
                && Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET - y < transform.minDepth()) {
            return null;
        }
        return transform.to();
    }

    private static Transform transformFor(BlockState state) {
        Transform direct = transformBlocks.get(state.getBlock());
        if (direct != null) {
            return direct;
        }
        for (TagTransform t : transformTags) {
            if (state.is(t.tag())) {
                return t.transform();
            }
        }
        return null;
    }

    private static boolean isScoured(BlockState state) {
        if (scouredBlocks.contains(state.getBlock())) {
            return true;
        }
        for (TagKey<Block> tag : scouredTags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    // Rebuilt only when the config lists themselves change (they are replaced wholesale on a reload).
    private static void ensureRules() {
        List<? extends String> scoured = Config.SCOURED_BLOCKS.get();
        if (scoured != cachedScoured) {
            cachedScoured = scoured;
            rebuildScoured(scoured);
        }
        List<? extends String> silent = Config.SILENT_TRANSFORMS.get();
        List<? extends String> recipes = Config.RECIPE_TRANSFORMS.get();
        if (silent != cachedSilent || recipes != cachedRecipes) {
            cachedSilent = silent;
            cachedRecipes = recipes;
            rebuildTransforms(silent, recipes);
        }
    }

    private static void rebuildScoured(List<? extends String> raw) {
        List<Pattern> patterns = new ArrayList<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String entry : raw) {
            String s = entry.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("#")) {
                TagKey<Block> tag = tag(s.substring(1));
                if (tag != null) {
                    tags.add(tag);
                }
            } else {
                patterns.add(Config.idGlob(Config.withNamespace(s)));
            }
        }
        scouredTags = List.copyOf(tags);
        scouredBlocks = matching(patterns);
    }

    // Both lists build one set of rules; only the second contributes to what JEI is shown. A block in
    // both is the silent entry's, since that one is read first.
    private static void rebuildTransforms(List<? extends String> silent, List<? extends String> recipes) {
        Map<Block, Transform> blocks = new HashMap<>();
        List<TagTransform> tags = new ArrayList<>();
        List<Shown> display = new ArrayList<>();
        readTransforms(silent, false, blocks, tags, display);
        readTransforms(recipes, true, blocks, tags, display);
        transformBlocks = Map.copyOf(blocks);
        transformTags = List.copyOf(tags);
        shown = List.copyOf(display);
    }

    private static void readTransforms(List<? extends String> raw, boolean show,
                                       Map<Block, Transform> blocks, List<TagTransform> tags,
                                       List<Shown> display) {
        for (String entry : raw) {
            String[] pair = Config.parseTransformEntry(entry.trim());
            if (pair == null) {
                Fogged.LOGGER.warn("Fogged: ignoring malformed transform entry \"{}\" -- expected "
                        + "\"from=to\", where 'to' is a single block id.", entry);
                continue;
            }
            Block to = block(pair[1]);
            if (to == null) {
                Fogged.LOGGER.warn("Fogged: transform entry \"{}\" names an unknown block \"{}\" -- "
                        + "skipped. (A mod that owns it may simply not be installed.)", entry, pair[1]);
                continue;
            }
            int minDepth = Integer.parseInt(pair[2]);
            Transform transform = new Transform(to, minDepth);
            if (pair[0].startsWith("#")) {
                TagKey<Block> tag = tag(pair[0].substring(1));
                if (tag != null) {
                    tags.add(new TagTransform(tag, transform));
                    if (show) {
                        display.add(new Shown(List.of(), tag, to, minDepth));
                    }
                }
                continue;
            }
            List<Block> from = List.copyOf(matching(List.of(Config.idGlob(pair[0]))));
            for (Block block : from) {
                blocks.putIfAbsent(block, transform);
            }
            if (show && !from.isEmpty()) {
                display.add(new Shown(from, null, to, minDepth));
            }
        }
    }

    // Every registered block whose id matches one of these patterns. One walk of the registry per
    // config change, so the per-block test above is a set lookup.
    private static Set<Block> matching(List<Pattern> patterns) {
        if (patterns.isEmpty()) {
            return Set.of();
        }
        Set<Block> found = new HashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            String id = BuiltInRegistries.BLOCK.getKey(block).toString();
            for (Pattern p : patterns) {
                if (p.matcher(id).matches()) {
                    found.add(block);
                    break;
                }
            }
        }
        return Set.copyOf(found);
    }

    private static Block block(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
    }

    // A tag is never resolved against a registry here: it may be empty, or not exist at all, until a
    // datapack says otherwise, and that can change on a reload without the config changing.
    private static TagKey<Block> tag(String id) {
        ResourceLocation key = ResourceLocation.tryParse(Config.withNamespace(id));
        if (key == null) {
            Fogged.LOGGER.warn("Fogged: ignoring \"#{}\" -- not a valid tag id.", id);
            return null;
        }
        return TagKey.create(Registries.BLOCK, key);
    }
}
