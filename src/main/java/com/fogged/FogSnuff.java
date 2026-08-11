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
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Clearable;
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
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The fire-burning <em>devices</em> half of the under-fog scour (see {@link FogScour}): furnaces,
 * campfires, blaze burners, balloon burners, portable engines -- whatever {@link Config#SNUFFED_DEVICES}
 * lists. Loose fire is simply removed; a device instead gets drowned so it cannot come back:
 *
 * <ol>
 *   <li>the block is unlit -- a {@code lit_} block swapped for its unlit sibling (Create's
 *       {@code lit_blaze_burner} -> {@code blaze_burner}), otherwise {@code lit} / {@code powered}
 *       cleared;</li>
 *   <li>its burn timer is zeroed, so whatever it was still running on is gone;</li>
 *   <li>its fuel is ejected, so the next tick cannot simply relight it. Without this a device just
 *       re-lights between sweeps and keeps working under the murk.</li>
 * </ol>
 *
 * <p>Only vanilla furnaces are touched through real types; every other device is reached by name
 * through reflection, so the mods that own them stay optional and none of them is a build dependency
 * (the same approach as {@link CreateCompatibility}). A device whose mod is missing never matches.
 */
public final class FogSnuff {
    private FogSnuff() {}

    // ---- which blocks count as devices ----

    // Resolved once per config change: matching the id per block would run a registry lookup and a regex
    // for every block in the scour band, which is by far the hottest loop in the mod.
    private static List<? extends String> cachedRaw;
    private static Set<Block> devices = Set.of();

    private static void ensureDevices() {
        List<? extends String> raw = Config.SNUFFED_DEVICES.get();
        if (raw == cachedRaw) {
            return;
        }
        cachedRaw = raw;
        List<Pattern> patterns = new ArrayList<>();
        for (String s : raw) {
            patterns.add(glob(Config.withNamespace(s.trim())));
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
        devices = found;
    }

    // "simulated:*_portable_engine" -> a regex matching that id shape. Everything but '*' is literal.
    private static Pattern glob(String s) {
        StringBuilder sb = new StringBuilder();
        for (String part : s.split("\\*", -1)) {
            if (sb.length() > 0) {
                sb.append(".*");
            }
            sb.append(Pattern.quote(part));
        }
        return Pattern.compile(sb.toString());
    }

    /** Whether this block is one of the configured fire-burning devices. */
    public static boolean isDevice(BlockState state) {
        ensureDevices();
        return !devices.isEmpty() && devices.contains(state.getBlock());
    }

    // ---- drowning one device ----

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

    // The unlit form of a device's state: the sibling block for a "lit_" block (whose lit-ness is the
    // block itself, not a property), else the same state with its lit / powered flags cleared.
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

    // Carry over whatever properties the two blocks share (facing, waterlogging, ...) so swapping the
    // lit block for its sibling keeps the device pointing the way it was placed.
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

        // Anything else: a burn timer to zero (fuel burners) and/or a redstone-fed output to cut
        // (burners driven by a signal rather than by fuel; those then stay down because the murk also
        // isolates their redstone input -- see isolated).
        boolean burner = zeroInt(be, "setCurrentBurnTime");
        boolean stopped = burner | zeroInt(be, "setSignalStrength");
        // A fuel burner also has its fuel taken, or it just lights again off the next item. Dropping it
        // needs an item handler; a device that exposes none has its fuel destroyed instead -- the murk
        // has to win, and the alternative is a device that never actually goes out.
        if (burner && !dropFuel(level, pos) && be instanceof Clearable clearable) {
            clearable.clearContent();
        }
        if (stopped) {
            be.setChanged();
        }
        return stopped;
    }

    // A furnace keeps its remaining burn time in a private field, but writes it out as "BurnTime", so
    // the block entity's own save/load is the supported way to clear it. The fuel slot is emptied first
    // so it is not written back by the round-trip.
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

    // Pull everything out of the device's item handler and drop it at its feet. False when the device
    // exposes no handler, or the handler refused to hand anything over.
    private static boolean dropFuel(Level level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        if (handler == null) {
            return false;
        }
        boolean dropped = false;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.extractItem(slot, Integer.MAX_VALUE, false);
            if (!stack.isEmpty()) {
                Block.popResource(level, pos, stack);
                dropped = true;
            }
        }
        return dropped;
    }

    // ---- redstone isolation ----

    /**
     * True when {@code pos} holds a listed device drowned by the murk, i.e. one whose redstone input the
     * fog cuts (see {@link com.fogged.mixin.SignalGetterMixin}). A burner fed by a signal rather than by
     * fuel has nothing to take away, so isolating its input is what keeps it off: it reads no power for
     * as long as it is under the fog, and picks its old signal back up once the boundary rises past it.
     */
    public static boolean isolated(SignalGetter getter, BlockPos pos) {
        ensureDevices();
        if (devices.isEmpty() || !(getter instanceof Level level) || !Config.SUBMERGE_WORLD.get()) {
            return false;
        }
        if (!devices.contains(level.getBlockState(pos).getBlock())) {
            return false;
        }
        // World-space height, so a device riding a Sable sub-level is judged where it actually floats.
        double activeSurfaceY = FogBand.surfaceY(level) - Config.SUBMERGE_SKIP.getAsInt();
        return PlaneSensor.worldY(level, pos) < activeSurfaceY;
    }

    // ---- reflection into third-party devices ----

    // Call a no-frills int setter (setCurrentBurnTime / setSignalStrength) with 0, if the device has one
    // and is not already at zero. False when there is no such setter -- i.e. this is not that kind of
    // device.
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

    // Reflection lookups are cached per block-entity class: the scour re-snuffs the same devices every
    // sweep, and a miss (the common case -- most devices have neither setter) is cached too.
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
