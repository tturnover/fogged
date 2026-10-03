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
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import com.fogged.registry.ModTags;

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

    // The same lines, from the datapacks in force rather than from the config (see
    // MurkTransformRecipe). Replaced wholesale on a reload, which is also what makes the rules stale.
    private static List<String> dataTransforms = List.of();
    private static boolean dataChanged;

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
        if (Config.ENABLE_SCOUR.get() && isScoured(state)) {
            level.destroyBlock(pos, false); // no drops: the murk takes it, it is not being mined
            return true;
        }
        return false;
    }

    /**
     * Whether {@link #apply} would change this block where it stands: a transform whose target it is
     * not already, or a scoured block. The sweep asks this before it commits to a block, so nothing is
     * announced (see FogScour's steaming) that then turns out to be a no-op.
     */
    public static boolean wants(Level level, BlockState state, int y) {
        ensureRules();
        Rule rule = Config.ENABLE_TRANSFORMS.get() ? ruleFor(state) : null;
        if (rule != null) {
            return deepEnough(level, rule, y) && (rule.toBlock() == null || !state.is(rule.toBlock()));
        }
        return Config.ENABLE_SCOUR.get() && isScoured(state);
    }

    /** Whether any rule names this block at all, at any depth -- the palette-level test of a section. */
    public static boolean mayTouch(BlockState state) {
        ensureRules();
        return ruleFor(state) != null || isScoured(state);
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

    /**
     * Blocks the scour is never allowed to take, whatever else says so: the mod's own
     * {@code #fogged:scour_immune} tag. It beats the scoured list and, more to the point,
     * {@code scourAllPlants}, which judges by what a block IS and so cannot otherwise be argued with
     * one plant at a time. Transforms are left alone by it -- those name their target outright.
     */
    public static boolean immune(BlockState state) {
        return state.is(ModTags.SCOUR_IMMUNE);
    }

    private static boolean isScoured(BlockState state) {
        if (immune(state)) {
            return false;
        }
        // The mod's own tag is asked whatever the config says. It is where the murk's own idea of what
        // it wilts lives (shipped in the Plant Scouring datapack, and open to any mod that wants its
        // plant taken), so it cannot depend on a line surviving in someone's config file; the list is
        // for what one world adds on top.
        if (state.is(ModTags.SCOURED)) {
            return true;
        }
        if (scouredBlocks.contains(state.getBlock())) {
            return true;
        }
        for (TagKey<Block> tag : scouredTags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return Config.SCOUR_ALL_PLANTS.getAsBoolean() && isPlant(state);
    }

    // Anything that grows, by what it is: the classes every plant of every mod ends up extending,
    // and the vanilla tags for the few that do not.
    private static boolean isPlant(BlockState state) {
        Block block = state.getBlock();
        return block instanceof BushBlock            // flowers, saplings, grasses, crops, mushrooms, fungi, lily pads, sea grass
                || block instanceof LeavesBlock
                || block instanceof VineBlock
                || block instanceof GrowingPlantBlock // kelp, weeping and twisting vines, cave vines
                || block instanceof SugarCaneBlock
                || block instanceof BambooStalkBlock
                || block instanceof BambooSaplingBlock
                || block instanceof CactusBlock
                || block instanceof CocoaBlock
                || block instanceof SporeBlossomBlock
                || block instanceof HangingRootsBlock
                || block instanceof BigDripleafBlock
                || block instanceof BigDripleafStemBlock
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.LEAVES);
    }

    /**
     * The conversions the datapacks in force define, as config lines (see
     * {@link MurkTransformRecipe#toEntry()}). Called on a datapack reload and on the recipe sync that
     * follows it, by {@link MurkTransformLoader}; the rules are rebuilt the next time anything asks.
     */
    public static void setDataTransforms(List<String> entries) {
        dataTransforms = List.copyOf(entries);
        dataChanged = true;
    }

    // Rebuilt only when the lists themselves change: the config's (replaced wholesale on a reload) or
    // the datapacks' (replaced wholesale on theirs).
    private static void ensureRules() {
        List<? extends String> scoured = Config.SCOURED_BLOCKS.get();
        if (scoured != cachedScoured) {
            cachedScoured = scoured;
            rebuildScoured(scoured);
        }
        List<? extends String> transforms = Config.TRANSFORMS.get();
        if (transforms != cachedTransforms || dataChanged) {
            cachedTransforms = transforms;
            dataChanged = false;
            // Config first: an entry a player wrote beats a datapack's for the same block, since the
            // rules are taken first-come (see rebuildTransforms) and the config is the one place
            // someone can argue with what a mod ships.
            List<String> all = new ArrayList<>(transforms);
            all.addAll(dataTransforms);
            rebuildTransforms(all);
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

            // An entry may name several sources at once. They share one rule, and one JEI recipe: the
            // input slot cycles through them, which is what a family of copper looks like anyway.
            List<Item> fromItems = new ArrayList<>();
            for (String from : parsed.from()) {
                if (from.startsWith("#")) {
                    String tagId = from.substring(1);
                    TagKey<Block> blockTag = forPlaced ? blockTag(tagId) : null;
                    if (blockTag != null) {
                        blockTags.add(new TagRule(blockTag, rule));
                    }
                    TagKey<Item> itemTag = itemTag(tagId);
                    if (itemTag != null) {
                        itemTags.add(new ItemTagRule(itemTag, rule));
                        if (!parsed.silent()) {
                            // A tag is its own recipe: its contents are only known once a world is up.
                            display.add(new Shown(List.of(), itemTag, parsed.fromCount(),
                                    shownTo, parsed.toCount(), parsed.depth()));
                        }
                    }
                    continue;
                }
                Pattern glob = Config.idGlob(from);
                if (forPlaced) {
                    for (Block block : matchingBlocks(List.of(glob))) {
                        blocks.putIfAbsent(block, rule);
                    }
                }
                for (Item item : matchingItems(glob)) {
                    if (items.putIfAbsent(item, rule) == null) {
                        fromItems.add(item);
                    }
                }
            }
            if (!parsed.silent() && !fromItems.isEmpty()) {
                display.add(new Shown(List.copyOf(fromItems), null, parsed.fromCount(),
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
