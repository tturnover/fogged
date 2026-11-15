package com.fogged;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * Puts each block's used filter ({@link UsedFilters}) into every block tag that block is in, as the
 * tags are bound (MappedRegistryMixin) -- vanilla's, other mods' and datapacks' alike, whatever they are
 * when they load. So shears cut a dirty wool as fast, and anything asking whether a block is wool still
 * finds it is.
 */
public final class UsedFilterTags {

    @SuppressWarnings("unchecked")
    public static <T> Map<TagKey<T>, List<Holder<T>>> mirror(MappedRegistry<?> registry, Map<TagKey<T>, List<Holder<T>>> tags) {
        if (registry != BuiltInRegistries.BLOCK) {
            return tags;
        }
        // Each original's holder -> its used filter's.
        Map<Holder<Block>, Holder<Block>> stand = new HashMap<>();
        UsedFilters.byOriginal().forEach((id, used) -> BuiltInRegistries.BLOCK.getHolder(id)
                .ifPresent(original -> stand.put(original, used.builtInRegistryHolder())));
        if (stand.isEmpty()) {
            return tags;
        }
        Map<TagKey<T>, List<Holder<T>>> out = new HashMap<>(tags.size());
        for (Map.Entry<TagKey<T>, List<Holder<T>>> e : tags.entrySet()) {
            List<Holder<T>> members = e.getValue();
            List<Holder<T>> added = null;
            for (Holder<T> h : members) {
                Holder<Block> used = stand.get(h);
                if (used != null && !members.contains(used)) {
                    if (added == null) {
                        added = new ArrayList<>(members);
                    }
                    added.add((Holder<T>) (Object) used);
                }
            }
            out.put(e.getKey(), added == null ? members : List.copyOf(added));
        }
        return out;
    }

    private UsedFilterTags() {}
}
