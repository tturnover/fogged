package com.fogged;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Clearable;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.SignalGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The fire-burning <em>devices</em> half of the under-fog scour ({@link FogScour}): whatever
 * {@link Config#SNUFFED_DEVICES} lists is unlit, its burn timer zeroed and its fuel ejected. Taking the
 * fuel is the part that makes it stick -- a device left holding any just relights between sweeps.
 *
 * <p>Cold Sweat's own burners are in that tag too, as optional entries: with the mod absent they name
 * nothing, and with it there they are snuffed like any other device.
 *
 * <p>Only vanilla furnaces are touched through real types; everything else is reached by name through
 * reflection, so the mods owning them stay optional (as in {@link CreateCompatibility}).
 */
public final class FogSnuff {
    private FogSnuff() {}

    // Cold Sweat's hearth is held still by its own mixin rather than doused here, but its fuel slot is
    // still emptied on the sweep; ColdSweatCompatibility names the mod's classes, so it is only called
    // when the mod is there to load them.
    private static final boolean COLD_SWEAT = ModList.get().isLoaded("cold_sweat");


    // Resolved once per config change: matching ids per block would run a registry lookup and a regex
    // for every block in the scour band, the hottest loop in the mod. Tags are not resolved here --
    // what they hold comes from datapacks and changes on a reload without the config changing -- so
    // they are kept as keys and tested per state.
    private static List<? extends String> cachedRaw;
    private static Set<Block> devices = Set.of();
    private static List<TagKey<Block>> deviceTags = List.of();

    private static void ensureDevices() {
        List<? extends String> raw = Config.SNUFFED_DEVICES.get();
        if (raw == cachedRaw) {
            return;
        }
        cachedRaw = raw;
        List<Pattern> patterns = new ArrayList<>();
        List<TagKey<Block>> tags = new ArrayList<>();
        for (String entry : raw) {
            String s = entry.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (s.startsWith("#")) {
                ResourceLocation key = ResourceLocation.tryParse(Config.withNamespace(s.substring(1)));
                if (key == null) {
                    Fogged.LOGGER.warn("Fogged: ignoring \"{}\" in snuffedDevices -- not a valid tag id.", s);
                    continue;
                }
                tags.add(TagKey.create(Registries.BLOCK, key));
            } else {
                patterns.add(Config.idGlob(Config.withNamespace(s)));
            }
        }
        Set<Block> found = new HashSet<>();
        if (!patterns.isEmpty()) {
            for (Block block : BuiltInRegistries.BLOCK) {
                String id = BuiltInRegistries.BLOCK.getKey(block).toString();
                for (Pattern p : patterns) {
                    if (p.matcher(id).matches()) {
                        found.add(block);
                        break;
                    }
                }
            }
        }
        devices = found;
        deviceTags = List.copyOf(tags);
    }

    // Nothing is listed at all: the whole snuff is off, and the callers can say so without looking at
    // the block in front of them.
    private static boolean nothingListed() {
        return devices.isEmpty() && deviceTags.isEmpty();
    }

    private static boolean listed(BlockState state) {
        if (devices.contains(state.getBlock())) {
            return true;
        }
        for (TagKey<Block> tag : deviceTags) {
            if (state.is(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this block is one of the configured fire-burning devices. */
    public static boolean isDevice(BlockState state) {
        ensureDevices();
        return listed(state);
    }

    /** Unlight a device, zero its burn timer and eject its fuel. */
    public static void snuff(Level level, BlockPos pos, BlockState state) {
        BlockState off = unlit(state);
        boolean changed = off != state;
        if (changed) {
            level.setBlock(pos, off, Block.UPDATE_ALL);
        }
        changed |= stopBlockEntity(level, pos);
        if (changed) {
            level.levelEvent(LevelEvent.SOUND_EXTINGUISH_FIRE, pos, 0);
        }
    }

    // A "lit_" block carries its lit-ness as the block itself rather than as a property, so it is swapped
    // for its unlit sibling (create:lit_blaze_burner -> create:blaze_burner) instead of having a flag cleared.
    private static BlockState unlit(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id.getPath().startsWith("lit_")) {
            Block unlitBlock = BuiltInRegistries.BLOCK.getOptional(
                    ResourceLocation.fromNamespaceAndPath(id.getNamespace(), id.getPath().substring(4)))
                    .orElse(null);
            if (unlitBlock != null && unlitBlock != state.getBlock()) {
                return copyShared(state, unlitBlock.defaultBlockState());
            }
        }
        BlockState off = state;
        if (off.hasProperty(BlockStateProperties.LIT) && off.getValue(BlockStateProperties.LIT)) {
            off = off.setValue(BlockStateProperties.LIT, false);
        }
        if (off.hasProperty(BlockStateProperties.POWERED) && off.getValue(BlockStateProperties.POWERED)) {
            off = off.setValue(BlockStateProperties.POWERED, false);
        }
        return off;
    }

    // Keeps facing / waterlogging / ... across the sibling swap.
    private static BlockState copyShared(BlockState from, BlockState to) {
        for (Property<?> property : from.getProperties()) {
            if (to.hasProperty(property)) {
                to = copyOne(from, to, property);
            }
        }
        return to;
    }

    private static <T extends Comparable<T>> BlockState copyOne(BlockState from, BlockState to, Property<T> p) {
        return to.setValue(p, from.getValue(p));
    }

    /** Stop the device's block entity: zero its burn timer / output, then empty its fuel. */
    private static boolean stopBlockEntity(Level level, BlockPos pos) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) {
            return false;
        }
        if (be instanceof AbstractFurnaceBlockEntity furnace) {
            return douseFurnace(level, pos, furnace);
        }

        // A burn timer to zero (fuel burners) and/or a redstone-fed output to cut; the latter stay down
        // because the murk isolates their input too (see isolated). Cold Sweat's hearth and boiler are
        // neither: their fuel is a number no setter reaches, and they are held still by their own
        // mixins instead (see snuffedAt), keeping that fuel -- here they lose their lit flag and
        // whatever was waiting in the fuel slot.
        boolean burner = zeroInt(be, "setCurrentBurnTime");
        boolean stopped = burner | zeroInt(be, "setSignalStrength")
                | (COLD_SWEAT && ColdSweatCompatibility.ejectFuel(level, pos, be));
        // Dropping the fuel needs an item handler or a container; a device exposing neither has it
        // destroyed instead, since the alternative is a device that never actually goes out.
        if (burner && !dropFuel(level, pos, be) && be instanceof Clearable clearable) {
            clearable.clearContent();
        }
        if (stopped) {
            be.setChanged();
        }
        return stopped;
    }

    // litTime is private but is written out as "BurnTime", so the block entity's own save/load is the
    // supported way to clear it. The fuel slot is emptied first, or the round-trip writes it back.
    private static boolean douseFurnace(Level level, BlockPos pos, AbstractFurnaceBlockEntity furnace) {
        boolean changed = false;
        ItemStack fuel = furnace.getItem(1); // SLOT_FUEL
        if (!fuel.isEmpty()) {
            Block.popResource(level, pos, fuel.copy());
            furnace.setItem(1, ItemStack.EMPTY);
            changed = true;
        }
        CompoundTag tag = furnace.saveWithoutMetadata(level.registryAccess());
        if (tag.getShort("BurnTime") != 0) {
            tag.putShort("BurnTime", (short) 0);
            furnace.loadWithComponents(tag, level.registryAccess());
            changed = true;
        }
        if (changed) {
            furnace.setChanged();
        }
        return changed;
    }

    // False when the device exposes neither a handler nor a container, or neither handed anything over.
    // The container is tried second: a modded device is a Container far more often than it registers
    // an item handler, and a hearth or boiler holds its fuel that way.
    private static boolean dropFuel(Level level, BlockPos pos, BlockEntity be) {
        boolean dropped = false;
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler != null) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.extractItem(slot, Integer.MAX_VALUE, false);
                if (!stack.isEmpty()) {
                    Block.popResource(level, pos, stack);
                    dropped = true;
                }
            }
        }
        if (!dropped && be instanceof Container container) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    Block.popResource(level, pos, stack.copy());
                    container.setItem(slot, ItemStack.EMPTY);
                    dropped = true;
                }
            }
        }
        return dropped;
    }

    /**
     * True when {@code pos} holds a listed device whose redstone input the fog cuts (see
     * {@link com.fogged.mixin.SignalGetterMixin}). A burner fed by a signal rather than by fuel has
     * nothing to take away, so cutting its input is what keeps it off -- and it picks the signal back up
     * once the boundary rises past it.
     */
    public static boolean isolated(SignalGetter getter, BlockPos pos) {
        return getter instanceof Level level && snuffedAt(level, pos);
    }

    /**
     * True when {@code pos} holds a listed device the murk is keeping out: under the active band, with
     * the extinguishing on. The one test behind everything that holds a device down between sweeps --
     * its redstone input (see {@link #isolated}), and a Cold Sweat hearth's tick (see
     * ColdSweatHearthMixin) -- so they can never disagree with the sweep itself about which devices
     * are the murk's.
     */
    public static boolean snuffedAt(Level level, BlockPos pos) {
        ensureDevices();
        if (nothingListed() || !Config.ENABLE_WORLD_CHANGES.get() || !Config.ENABLE_EXTINGUISH.get()
                || !Config.dimensionEnabled(level)) {
            return false;
        }
        if (!listed(level.getBlockState(pos))) {
            return false;
        }
        if (BreatheSpheres.shelters(level, pos)) {
            return false; // a filter's air keeps a device lit, and its redstone with it
        }
        // World-space height, so a device riding a Sable sub-level is judged where it actually floats.
        double activeSurfaceY = FogBand.surfaceY(level) - Config.WORLD_CHANGE_SKIP.getAsInt();
        return PlaneSensor.worldY(level, pos) < activeSurfaceY;
    }

    // Calls setCurrentBurnTime / setSignalStrength with 0. False when the device has no such setter,
    // i.e. it is not that kind of device.
    private static boolean zeroInt(BlockEntity be, String setter) {
        Method set = method(be.getClass(), setter, int.class);
        if (set == null) {
            return false;
        }
        try {
            Method get = method(be.getClass(), "get" + setter.substring(3));
            if (get != null && ((Number) get.invoke(be)).intValue() == 0) {
                return false;
            }
            set.invoke(be, 0);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    // Cached per block-entity class -- the scour re-snuffs the same devices every sweep. Misses (the
    // common case) are cached too.
    private record MethodKey(Class<?> owner, String name) {}

    private static final Map<MethodKey, Method> METHODS = new ConcurrentHashMap<>();
    private static final Method ABSENT;

    static {
        try {
            ABSENT = Object.class.getMethod("hashCode");
        } catch (NoSuchMethodException e) {
            throw new AssertionError(e);
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        Method found = METHODS.computeIfAbsent(new MethodKey(owner, name), key -> {
            try {
                return key.owner().getMethod(key.name(), params);
            } catch (NoSuchMethodException e) {
                return ABSENT;
            }
        });
        return found == ABSENT ? null : found;
    }
}
