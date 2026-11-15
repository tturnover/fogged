package com.fogged.mixin;

import java.util.List;
import java.util.Map;

import com.fogged.UsedFilterTags;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.tags.TagKey;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

// Every registry's tags are bound here, on the server as they load and on the client as they arrive, so
// this is where the used filters are put into their wools' tags (see UsedFilterTags).
@Mixin(MappedRegistry.class)
public abstract class MappedRegistryMixin<T> {

    @ModifyVariable(method = "bindTags", at = @At("HEAD"), argsOnly = true)
    private Map<TagKey<T>, List<Holder<T>>> fogged$mirrorUsedFilters(Map<TagKey<T>, List<Holder<T>>> tags) {
        return UsedFilterTags.mirror((MappedRegistry<?>) (Object) this, tags);
    }
}
