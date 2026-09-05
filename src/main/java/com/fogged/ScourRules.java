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

/**
 * The configurable half of the under-fog scour: the blocks {@link Config#SCOURED_BLOCKS} says the murk
 * takes, and the swaps {@link Config#BLOCK_TRANSFORMS} says it makes ("from=to", coal ore back to the
 * stone it sat in by default).
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
    private static List<? extends String> cachedTransforms;

    private static Set<Block> scouredBlocks = Set.of();
    private static List<TagKey<Block>> scouredTags = List.of();
    private static Map<Block, BlockState> transformBlocks = Map.of();
    private static List<TagTransform> transformTags = List.of();
    private static List<Shown> shown = List.of();

    private record TagTransform(TagKey<Block> tag, BlockState to) {}

    /**
     * A transform the config asked to be shown as a recipe ("from=to|jei"). Exactly one of {@code from}
     * and {@code fromTag} is set: an id entry resolves to the blocks it matched, a tag entry stays a tag
     * so whoever displays it can expand it against the datapack in force.
     */
    public record Shown(List<Block> from, TagKey<Block> fromTag, Block to) {}

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
        BlockState to = transformFor(state);
        if (to != null) {
            if (!state.is(to.getBlock())) {
                level.setBlock(pos, to, Block.UPDATE_ALL);
            }
            return true; // handled either way: a no-op swap is still the config's answer for this block
        }
        if (isScoured(state)) {
            level.destroyBlock(pos, false); // no drops: the murk takes it, it is not being mined
            return true;
        }
        return false;
    }

    private static BlockState transformFor(BlockState state) {
        BlockState direct = transformBlocks.get(state.getBlock());
        if (direct != null) {
            return direct;
        }
        for (TagTransform t : transformTags) {
            if (state.is(t.tag())) {
                return t.to();
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
        List<? extends String> transforms = Config.BLOCK_TRANSFORMS.get();
        if (transforms != cachedTransforms) {
            cachedTransforms = transforms;
            rebuildTransforms(transforms);
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

    private static void rebuildTransforms(List<? extends String> raw) {
        Map<Block, BlockState> blocks = new HashMap<>();
        List<TagTransform> tags = new ArrayList<>();
        List<Shown> display = new ArrayList<>();
        for (String entry : raw) {
            String[] pair = Config.parseTransformEntry(entry.trim());
            if (pair == null) {
                Fogged.LOGGER.warn("Fogged: ignoring malformed blockTransforms entry \"{}\" -- expected "
                        + "\"from=to\", where 'to' is a single block id.", entry);
                continue;
            }
            Block to = block(pair[1]);
            if (to == null) {
                Fogged.LOGGER.warn("Fogged: blockTransforms entry \"{}\" names an unknown block \"{}\" -- "
                        + "skipped. (A mod that owns it may simply not be installed.)", entry, pair[1]);
                continue;
            }
            BlockState toState = to.defaultBlockState();
            boolean show = Config.TRANSFORM_FLAG_JEI.equals(pair[2]);
            if (pair[0].startsWith("#")) {
                TagKey<Block> tag = tag(pair[0].substring(1));
                if (tag != null) {
                    tags.add(new TagTransform(tag, toState));
                    if (show) {
                        display.add(new Shown(List.of(), tag, to));
                    }
                }
                continue;
            }
            List<Block> from = List.copyOf(matching(List.of(Config.idGlob(pair[0]))));
            for (Block block : from) {
                blocks.put(block, toState);
            }
            if (show && !from.isEmpty()) {
                display.add(new Shown(from, null, to));
            }
        }
        transformBlocks = Map.copyOf(blocks);
        transformTags = List.copyOf(tags);
        shown = List.copyOf(display);
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
