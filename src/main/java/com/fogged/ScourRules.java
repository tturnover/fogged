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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The configurable half of the under-fog scour: the blocks {@link Config#SCOURED_BLOCKS} says the murk
 * takes, and everything {@link Config#TRANSFORMS} says it turns into something else.
 *
 * <p>One list drives both halves of a transform. An entry's left side is resolved against the block
 * registry AND the item registry, so a line naming something that is both -- which most blocks are --
 * converts it wherever it is found: placed in the world by {@link FogScour}, or lying on the floor by
 * {@link ItemScour}. Its right side decides what happens to a placed block: a block replaces it in
 * place, an item breaks it and drops instead.
 *
 * <p>Transforms run before {@link FogScour}'s built-in rules, so an entry overrides what the murk
 * would otherwise do to that block rather than only adding to it.
 *
 * <p>Ids (with '*' wildcards) are matched against the registries once per config change -- the scour
 * walks every block in the band, so a regex per block would be the mod's hottest loop -- while tags
 * stay as tag keys and are tested per state: tag contents come from datapacks and can change without
 * the config changing.
 */
public final class ScourRules {
    private ScourRules() {}

    private static List<? extends String> cachedScoured;
    private static List<? extends String> cachedTransforms;

    private static Set<Block> scouredBlocks = Set.of();
    private static List<TagKey<Block>> scouredTags = List.of();

    private static Map<Block, Rule> blockRules = Map.of();
    private static List<TagRule> blockTagRules = List.of();
    private static Map<Item, Rule> itemRules = Map.of();
    private static List<ItemTagRule> itemTagRules = List.of();
    private static List<Shown> shown = List.of();

    /**
     * What something turns into. Exactly one of {@code toBlock} and {@code toItem} says what a PLACED
     * block does: a block is put in its place, an item means the block breaks and drops {@code toCount}
     * of that item. {@code fromCount} and {@code toCount} are about stacks only.
     */
    private record Rule(int fromCount, Block toBlock, Item toItem, int toCount, int minDepth) {}

    private record TagRule(TagKey<Block> tag, Rule rule) {}

    private record ItemTagRule(TagKey<Item> tag, Rule rule) {}

    /**
     * A transform to show in JEI: what may go in, what comes out, and the depth it needs. Exactly one
     * of {@code from} and {@code fromTag} is set -- an id entry resolves to the items it matched, a tag
     * entry stays a tag so whoever displays it can expand it against the datapack in force.
     */
    public record Shown(List<Item> from, TagKey<Item> fromTag, int fromCount,
                        Item to, int toCount, int minDepth) {}

    /**
     * The transforms to display, in config order. Never null; empty when every entry is !silent -- or
     * when the whole list is switched off, since a recipe for something that does not happen is worse
     * than no recipe at all.
     */
    public static List<Shown> shownTransforms() {
        if (!Config.ENABLE_TRANSFORMS.get()) {
            return List.of();
        }
        ensureRules();
        return shown;
    }

    /**
     * Apply the configured rules to one placed block, most specific first. Returns true when this block
     * has been dealt with and the built-in scour should leave it alone.
     */
    public static boolean apply(Level level, BlockPos pos, BlockState state) {
        ensureRules();
        Rule rule = Config.ENABLE_TRANSFORMS.get() ? ruleFor(state) : null;
        if (rule != null) {
            if (!deepEnough(level, rule, pos.getY())) {
                return false; // not far enough down yet: leave it to the rules below
            }
            if (rule.toBlock() != null) {
                if (!state.is(rule.toBlock())) {
                    level.setBlock(pos, carryOver(state, rule.toBlock().defaultBlockState()),
                            Block.UPDATE_ALL);
                }
            } else {
                // The target is an item, so there is nothing to put in the block's place: it breaks,
                // and what the rule names drops where it stood.
                level.destroyBlock(pos, false);
                Block.popResource(level, pos, new ItemStack(rule.toItem(), rule.toCount()));
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
     * What a dropped {@code stack} becomes at world height {@code y}: the stack it turns into and how
     * many of the original that took, or null when no rule applies. The caller does the arithmetic, so
     * it can decide what to do with a remainder.
     */
    public static Conversion convert(Level level, ItemStack stack, double y) {
        if (!Config.ENABLE_TRANSFORMS.get()) {
            return null;
        }
        ensureRules();
        Rule rule = ruleFor(stack);
        if (rule == null || !deepEnough(level, rule, y)) {
            return null;
        }
        Item to = rule.toItem() != null ? rule.toItem() : rule.toBlock().asItem();
        if (to == Item.byId(0) || to == stack.getItem()) {
            return null; // nothing to become, or already it
        }
        if (stack.getCount() < rule.fromCount()) {
            return null; // not enough in the stack to make one
        }
        return new Conversion(rule.fromCount(), to, rule.toCount());
    }

    /** What one application of a rule takes out of a stack, and what it puts back. */
    public record Conversion(int takes, Item to, int gives) {}

    // A depth is measured from the visible surface down, so it moves with the boundary rather than
    // sitting at some fixed Y: fifty blocks under the murk stays fifty blocks under it as the plane
    // rises and falls.
    private static boolean deepEnough(Level level, Rule rule, double y) {
        return rule.minDepth() <= 0
                || Config.breathHeight(level) + Config.PLANE_SURFACE_OFFSET - y >= rule.minDepth();
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

    private static Rule ruleFor(BlockState state) {
        Rule direct = blockRules.get(state.getBlock());
        if (direct != null) {
            return direct;
        }
        for (TagRule t : blockTagRules) {
            if (state.is(t.tag())) {
                return t.rule();
            }
        }
        return null;
    }

    private static Rule ruleFor(ItemStack stack) {
        Rule direct = itemRules.get(stack.getItem());
        if (direct != null) {
            return direct;
        }
        for (ItemTagRule t : itemTagRules) {
            if (stack.is(t.tag())) {
                return t.rule();
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
        List<? extends String> transforms = Config.TRANSFORMS.get();
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
                TagKey<Block> tag = blockTag(s.substring(1));
                if (tag != null) {
                    tags.add(tag);
                }
            } else {
                patterns.add(Config.idGlob(Config.withNamespace(s)));
            }
        }
        scouredTags = List.copyOf(tags);
        scouredBlocks = matchingBlocks(patterns);
    }

    // One entry can feed four maps: it is looked up in both registries, and a '#' tag is registered as
    // both a block tag and an item tag, since a tag id names a tag in each and the entry cannot say
    // which was meant. Whichever exists is what ends up being used.
    private static void rebuildTransforms(List<? extends String> raw) {
        Map<Block, Rule> blocks = new HashMap<>();
        List<TagRule> blockTags = new ArrayList<>();
        Map<Item, Rule> items = new HashMap<>();
        List<ItemTagRule> itemTags = new ArrayList<>();
        List<Shown> display = new ArrayList<>();

        for (String entry : raw) {
            Config.Transform parsed = Config.parseTransform(entry);
            if (parsed == null) {
                Fogged.LOGGER.warn("Fogged: ignoring malformed transforms entry \"{}\" -- expected "
                        + "\"[count] from=[count] to [@depth] [!silent]\".", entry);
                continue;
            }
            Block toBlock = block(parsed.to());
            Item toItem = item(parsed.to());
            if (toBlock == null && toItem == null) {
                Fogged.LOGGER.warn("Fogged: transforms entry \"{}\" names an unknown target \"{}\" -- "
                        + "skipped. (A mod that owns it may simply not be installed.)", entry, parsed.to());
                continue;
            }
            // A block target replaces a placed block; an item target breaks it and drops. Something
            // that is both is treated as a block, which is what a player writing "stone" means.
            Rule rule = new Rule(parsed.fromCount(), toBlock, toBlock == null ? toItem : null,
                    parsed.toCount(), parsed.depth());
            Item shownTo = toItem != null ? toItem : toBlock.asItem();

            // Whether the rule touches PLACED blocks at all. A count above one is about stacks: you
            // cannot take four of a block that is standing there, and you cannot put three in the one
            // space it occupies. Rather than breaking the block to honour a count -- which is not what
            // "turns into stone" means -- such an entry is left to the dropped stacks, and whatever is
            // standing is passed on to the rules below. A count on an ITEM target is the exception: it
            // says how many drop, and breaking is what that entry asks for in the first place.
            boolean forPlaced = parsed.fromCount() == 1 && (toBlock == null || parsed.toCount() == 1);

            if (parsed.from().startsWith("#")) {
                String tagId = parsed.from().substring(1);
                TagKey<Block> blockTag = forPlaced ? blockTag(tagId) : null;
                if (blockTag != null) {
                    blockTags.add(new TagRule(blockTag, rule));
                }
                TagKey<Item> itemTag = itemTag(tagId);
                if (itemTag != null) {
                    itemTags.add(new ItemTagRule(itemTag, rule));
                    if (!parsed.silent()) {
                        display.add(new Shown(List.of(), itemTag, parsed.fromCount(),
                                shownTo, parsed.toCount(), parsed.depth()));
                    }
                }
                continue;
            }

            Pattern glob = Config.idGlob(parsed.from());
            if (forPlaced) {
                for (Block from : matchingBlocks(List.of(glob))) {
                    blocks.putIfAbsent(from, rule);
                }
            }
            List<Item> fromItems = matchingItems(glob);
            for (Item from : fromItems) {
                items.putIfAbsent(from, rule);
            }
            if (!parsed.silent() && !fromItems.isEmpty()) {
                display.add(new Shown(fromItems, null, parsed.fromCount(),
                        shownTo, parsed.toCount(), parsed.depth()));
            }
        }

        blockRules = Map.copyOf(blocks);
        blockTagRules = List.copyOf(blockTags);
        itemRules = Map.copyOf(items);
        itemTagRules = List.copyOf(itemTags);
        shown = List.copyOf(display);
    }

    // Every registered block whose id matches one of these patterns. One walk of the registry per
    // config change, so the per-block test above is a map lookup.
    private static Set<Block> matchingBlocks(List<Pattern> patterns) {
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

    private static List<Item> matchingItems(Pattern pattern) {
        List<Item> found = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (pattern.matcher(BuiltInRegistries.ITEM.getKey(item).toString()).matches()) {
                found.add(item);
            }
        }
        return List.copyOf(found);
    }

    private static Block block(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
    }

    private static Item item(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        return BuiltInRegistries.ITEM.getOptional(key).orElse(null);
    }

    // A tag is never resolved against a registry here: it may be empty, or not exist at all, until a
    // datapack says otherwise, and that can change on a reload without the config changing.
    private static TagKey<Block> blockTag(String id) {
        ResourceLocation key = ResourceLocation.tryParse(Config.withNamespace(id));
        if (key == null) {
            Fogged.LOGGER.warn("Fogged: ignoring \"#{}\" -- not a valid tag id.", id);
            return null;
        }
        return TagKey.create(Registries.BLOCK, key);
    }

    private static TagKey<Item> itemTag(String id) {
        ResourceLocation key = ResourceLocation.tryParse(Config.withNamespace(id));
        return key == null ? null : TagKey.create(Registries.ITEM, key);
    }
}
